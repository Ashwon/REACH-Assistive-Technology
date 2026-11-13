package com.reach.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.reach.app.claude.ClaudeClient
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.CropEmbedder
import com.reach.app.grounding.DetectorGate
import com.reach.app.grounding.IdMatch
import com.reach.app.grounding.ObjectGrounder
import com.reach.app.grounding.ObjectStore
import com.reach.app.grounding.RecentCandidates
import com.reach.app.grounding.SupportedClasses
import com.reach.app.grounding.TargetLock
import com.reach.app.grounding.TargetPick
import com.reach.app.grounding.clarifyQuestion
import com.reach.app.grounding.pickTarget
import com.reach.app.grounding.sideWord
import com.reach.app.haptic.HapticLink
import com.reach.app.hand.HandSelector
import com.reach.app.hand.HandTracker
import com.reach.app.hand.Side
import com.reach.app.spatial.DepthFrame
import com.reach.app.spatial.GuidanceThrottle
import com.reach.app.spatial.SpatialEngine
import com.reach.app.speech.Speaker
import com.reach.app.talk.Act
import com.reach.app.talk.Conversation
import com.reach.app.talk.Tutorial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan

/**
 * Threads and I/O around [ReachCore], which owns every state transition and every guidance line.
 * Claude is called exactly once per search utterance. The per-frame loop (onFrame) is 100% local:
 * hand tracker + detector + core. The detector (~150 ms) runs on its own thread so it never stalls
 * fingertip tracking.
 */
private const val GROUND_INTERVAL_MS = 45L
/** A detection whose frame is older than this when it finishes is dropped, never applied. */
private const val STALE_MS = 2200L
/** How long a spoken/typed search scans incoming detector frames while the user looks around before giving up. */
private const val SEARCH_WAIT_MS = 60_000L

/** Spoken/typed commands that are not an object request (kept out of the request field). */
fun isCommand(text: String) = Conversation().handle(text, emptyList(), -1, canGuide = true, nowMs = 0L) !is Act.Search

class ReachController(
    private val grounder: ObjectGrounder,
    private val handTracker: HandTracker,
    private val claude: ClaudeClient,
    speaker: Speaker,
    /** The vibration band (BleBand): gets ReachCore's cue every frame, without ever blocking this pipeline. */
    val haptics: HapticLink,
    private val scope: CoroutineScope,
    /** Registered objects' photo matching; null when the embedder model is not installed. Detector thread only. */
    private val embedder: CropEmbedder?,
    private val store: ObjectStore,
    private val onUi: (ReachSnapshot) -> Unit
) {
    private val core = ReachCore(
        say = { speaker.say(it) },
        log = { Log.i("REACH", it) },
        depthReachedValidated = false,
        haptic = { haptics.request(it) },
        handConfig = HandSelector.Config(
            graceMs = 60_000L,
            trackTtlMs = 4000L,
            recentMs = 950L,
            gate = 0.45f,
            shortGapMs = 4000L,
            reacquireGate = 0.45f,
            reacquireMinRatio = 0.4f,
            reacquireMaxRatio = 2.5f,
            minReacquireScore = 0.25f,
            returnConfirmMs = 100L,
            autoEnroll = true,
            holdMissingMs = 750L
        ),
        lock = TargetLock(
            freshMs = 2800L,
            occludedGraceMs = 18_000L,
            missingGraceMs = 10_000L,
            reacquireDist = 0.45f,
            minLookSimilarity = 0.25f,
            allowUnconfirmedReacquire = true
        ),
        spatial = SpatialEngine(
            depthReachedValidated = false,
            confirmMs = 100L,
            alpha = 0.65f,
            handGraceMs = 2400L,
            firstHandMs = 5000L,
            allow2dReach = false,
            maxExitX = 0.10f,
            maxExitY = 0.09f
        ),
        throttle = GuidanceThrottle(
            minGapMs = 850L,
            repeatMs = 2400L,
            statusRepeatMs = 4500L,
            perStatusCooldownMs = 8500L
        ),
        introQuietMs = 1200L,
        relockQuietMs = 700L,
        boxTurnDeg = 45f,
        singleHandSetup = true,
        autoResumeGuidance = true
    )
    val mode get() = core.mode
    private val busy = AtomicBoolean(false)
    val photoMatching get() = embedder != null
    /** The user's registered objects (persisted on the phone only). */
    val memory = store.load()
    /** Conversation and short-lived task memory. Main thread only. */
    private val talk = Conversation(memory = memory)

    @Volatile private var latestCandidates: List<Candidate> = emptyList()
    private val recent = RecentCandidates(graceMs = 3000L)
    /** Confirmed detections plus those that blinked out within the last second; what requests resolve against. */
    @Volatile private var selectable: List<Candidate> = emptyList()
    private val detectExec = Executors.newSingleThreadExecutor()
    /** The detector thread as a dispatcher: photo matching runs there, never on the camera thread. */
    private val detDispatcher = detectExec.asCoroutineDispatcher()
    private val gate = DetectorGate(GROUND_INTERVAL_MS, STALE_MS)
    @Volatile private var detFrameMs = -1L // frame time of the detections currently applied
    private var interpretJob: Job? = null // main thread

    // Debug log accumulators: camera thread, except the det* ones written by the detector thread.
    private var logAt = 0L
    private var logFrames = 0
    private var handNs = 0L
    private val detNs = AtomicLong(); private val detRuns = AtomicInteger()
    /** Set by the screen: frames come from the depth camera mode (even when a frame has no depth map yet). */
    @Volatile var depthMode = false
    /** Set by the screen: horizontal field of view of the bound camera (degrees), for the target's bearing. */
    var fovDeg: Float
        get() = core.fovDeg
        set(v) { core.fovDeg = v }
    /** Set by the screen: the phone is chest-mounted, so its rotation is the user's body turning. */
    var chestMounted: Boolean
        get() = core.chestMounted
        set(v) { core.chestMounted = v }
        /** The last 1 s pipeline summary, for the diagnostics panel. */
    @Volatile var stats = ""; private set

    /** Called from the camera thread for every frame. Must stay fast. */
    fun onFrame(frame: Bitmap, tsMs: Long, depth: DepthFrame? = null) {
        if (!busy.compareAndSet(false, true)) return
        try {
            if (gate.tryStart(tsMs)) {
                val token = core.session // the result is applied only if no transition happened meanwhile
                // Bitmaps are fresh per frame and never recycled, so handing this one over is safe.
                try { detectExec.execute { runDetector(frame, tsMs, token) } }
                catch (e: RejectedExecutionException) { return } // closing
            }

            // ARCore's own intrinsics give the depth camera's field of view exactly (upright width = sensor height when rotated).
            depth?.let { d -> val i = d.intrinsics; val (w, f) = if (d.rotation % 180 == 0) i.w to i.fx else i.h to i.fy
                core.fovDeg = Math.toDegrees(2 * atan(w / 2.0 / f)).toFloat() }
            val t1 = System.nanoTime()
            val hands = handTracker.detectAll(frame, tsMs)
            handNs += System.nanoTime() - t1
            val s = core.onFrame(hands, latestCandidates, tsMs, depthFrame = depth, depthSource = depthMode)
                .copy(frameW = frame.width, frameH = frame.height)

            logFrames++
            if (tsMs - logAt >= 1000) {
                val runs = detRuns.getAndSet(0); val ns = detNs.getAndSet(0)
                stats = "fps=$logFrames handMs=${handNs / logFrames / 1_000_000} det/s=$runs frame=${frame.width}x${frame.height}" +
                    (depth?.let { " depthMap=${it.w}x${it.h} skewMs=${(it.frameNs - it.depthNs) / 1_000_000}" } ?: " depth=off") +
                    (s.depth?.let { " depth=${it.reason} targetZ=${it.targetM} tipZest=${it.tipM} arTipZ=${it.arTipM}" } ?: "")
                Log.i("REACH", "$stats " +
                    "groundMs=${if (runs > 0) ns / runs / 1_000_000 else 0} detAgeMs=${if (detFrameMs < 0) -1 else tsMs - detFrameMs} " +
                    "stale=${gate.staleDropped} mode=${s.mode} target=${s.target}#${s.targetId} ${s.track} " +
                    (if (s.mode == Mode.GUIDING) "guide=${if (s.depth?.sample != null) "3D" else "2D"} cue=${s.cue} err=%+.3f,%+.3f ".format(core.ex, core.ey) else "") +
                    "hands=${hands.size} L=${s.left.state}#${s.left.trackId} R=${s.right.state}#${s.right.trackId} guided=${s.guided} " +
                    "hdg=%.0f turn=${s.turn}${s.turnDeg?.let { "(%+.0f°)".format(it) } ?: ""} closer=${s.haptic} band=${s.band}".format(core.headingDeg()) +
                    (if (s.hand.present) " score=%.2f size=%.2f palm=%.2f,%.2f".format(s.hand.score, s.hand.handSize, s.hand.palmX, s.hand.palmY) else "") +
                    s.otherHands.joinToString("") { " other=size%.2f@%.2f,%.2f".format(it.handSize, it.palmX, it.palmY) } +
                    " cands=" + s.candidates.joinToString { "#${it.id} ${it.label}/${it.beverage}/${"%.2f".format(it.score)}${if (it.sure) "" else "?"}" })
                if (s.hand.present) Log.i("REACH", "tips " + s.hand.tips.joinToString(" ") {
                    "${it.finger}=%.3f,%.3f,z%.3f${if (it.inFrame) "" else "(off)"}".format(it.x, it.y, it.z) +
                        (it.visibility?.let { v -> " vis%.2f".format(v) } ?: "") + (it.presence?.let { p -> " pres%.2f".format(p) } ?: "")
                })
                logAt = tsMs; logFrames = 0; handNs = 0
            }
            onUi(s)
        } finally {
            busy.set(false)
        }
    }

    /** Detector thread. Results are applied only if the gate says they are still fresh. */
    private fun runDetector(frame: Bitmap, frameMs: Long, token: Int) {
        val t0 = System.nanoTime()
        var cands: List<Candidate>? = null
        try { cands = grounder.ground(frame, frameMs) }
        catch (e: Exception) { Log.w("REACH", "detector failed", e) }
        finally {
            detNs.addAndGet(System.nanoTime() - t0); detRuns.incrementAndGet()
            if (!gate.finish(frameMs, SystemClock.uptimeMillis())) cands = null
        }
        if (cands == null) return
        latestCandidates = cands
        // A detection seen in a single frame is never offered as a target (see CandidateTracker).
        selectable = recent.update(cands.filter { it.confirmed }, frameMs)
        detFrameMs = frameMs
        core.onDetections(cands, frameMs, token)
        // A registered target on a new track: compare its photo again, only while the target itself is in view.
        core.identityCheckDue()?.let { (name, id) ->
            val obj = memory.get(name)
            if (obj != null && cands.any { it.id == id }) core.onIdentityCheck(name, id, memory.match(obj, cands, ::embedOf))
        }
    }

    /** Detector thread only. */
    private fun embedOf(c: Candidate): FloatArray? = c.crop?.let { crop -> embedder?.embed(crop) }

    /** Called once per recognized utterance. The conversation layer decides what it means; nothing here is per frame. */
    fun onUtterance(text: String) {
        val now = SystemClock.uptimeMillis()
        val locked = if (core.lock.active) core.lock.id else -1
        val canGuide = core.lock.active && core.mode != Mode.IDLE && core.mode != Mode.SEARCHING
        val act = talk.handle(text, selectable, locked, canGuide, now)
        Log.i("REACH", "utterance \"$text\" -> $act task=${talk.task}")
        when (act) {
            is Act.Cancel -> reset()
            is Act.Done -> if (!core.confirmReached()) core.announce("There's nothing to confirm yet.", now)
            is Act.Hands -> core.beginHandSetup(now, "", act.side)
            is Act.UseHand -> core.useHand(act.side, now)
            is Act.Where -> core.announce(core.whereIsTarget(now), now)
            is Act.Help -> help()
            is Act.Guide -> if (!core.startGuidance(now)) core.announce("Tell me what to find first, like: find my bottle.", now)
            is Act.Say -> core.announce(act.line, now)
            is Act.Lock -> {
                interpretJob?.cancel()
                if (core.lockOn(act.c, act.name, act.c.score.toDouble(), offline = true, now, act.prefix)) {
                    talk.onLocked(SupportedClasses.resolve(act.name), act.c.id, now)
                    core.startGuidance(now)
                }
            }
            is Act.Search -> search(act.text)
            is Act.Register -> register(act, now)
            is Act.Forget -> {
                memory.remove(act.name); store.save(memory); store.deletePhotos(act.name)
                if (core.identity == act.name) reset()
                core.announce("I've forgotten your ${act.name}.", now)
            }
            is Act.FindRegistered -> findRegistered(act)
        }
    }

    /** "Remember this laptop as my work laptop": the locked target, else the one settled object of that class in view. */
    private fun register(act: Act.Register, now: Long) {
        val emb = embedder ?: return core.announce("I can't save objects: the image matching model isn't installed.", now)
        val pool = selectable.filter { it.confirmed && it.conflict == null && act.spec?.labels?.contains(it.label) != false }
        val c = pool.firstOrNull { core.lock.active && it.id == core.lock.id } ?: pool.singleOrNull()
        val what = act.spec?.name ?: "object"
        when {
            act.spec?.supported == false -> core.announce(SupportedClasses.cannotFind(act.spec), now)
            c == null && pool.isEmpty() -> core.announce("I can't see a $what right now. Point the camera at it and try again.", now)
            c == null -> core.announce("I see ${pool.size} ${what}s. Find the one you mean first, then say remember this as your ${act.name}.", now)
            c.crop == null || c.lowLight -> core.announce("It's too dark to take a good photo of it. Turn on a light and try again.", now)
            else -> scope.launch {
                val e = withContext(detDispatcher) { emb.embed(c.crop) }
                val n = memory.add(act.name, c.label, e).refs.size
                store.save(memory, act.name, c.crop)
                Log.i("REACH", "registered ${act.name} = ${c.label}#${c.id} photos=$n")
                core.announce("Saved this ${c.label} as your ${act.name}" + (if (n > 1) ", photo $n. " else ". ") +
                    "I'll compare what I see with this photo, but a photo can't prove it's the same one. " +
                    "Saving it again from another side helps.", SystemClock.uptimeMillis())
            }
        }
    }

    /** "Find my work laptop": photo match among the settled candidates of its class. Never a claim from the class alone. */
    private fun findRegistered(act: Act.FindRegistered) {
        val token = core.beginSearch()
        val snapshot = selectable
        val name = act.obj.name; val label = act.spec.name
        interpretJob?.cancel()
        interpretJob = scope.launch {
            // No embedder: the same rule with no pixels, so never a match (the user chooses, or "can't see").
            val r = withContext(detDispatcher) { memory.match(act.obj, snapshot) { c -> if (embedder == null) null else embedOf(c) } }
            Log.i("REACH", "find registered $name -> $r")
            val now = SystemClock.uptimeMillis()
            when {
                r is IdMatch.Match -> if (core.onSearchResult(token, r.c, label, r.c.score.toDouble(), offline = true, "", now,
                        prefix = "The $label ${r.c.box.sideWord()} looks like your $name from its saved photo. ", registered = name) &&
                    core.mode == Mode.TARGET_LOCKED) {
                    talk.onLocked(act.spec, r.c.id, now)
                    core.startGuidance(now)
                }
                // "find my laptop": the photo did not settle it, so it is an ordinary request with no ownership claim
                !act.explicit -> if (core.session == token) search(act.text)
                r is IdMatch.Unsure -> if (core.onSearchResult(token, null, label, 0.0, true,
                        if (r.options.size == 1) "I see a $label, but I can't confirm it's your $name from its photo. Say yes to use it anyway."
                        else "I can't tell which $label is your $name from its photo. ${clarifyQuestion(act.spec, r.options.size)}", now))
                    talk.onClarify(act.spec, r.options, now)
                else -> core.onSearchResult(token, null, label, 0.0, true, "I can't see a $label right now, so I can't look for your $name. Try panning slowly.", now)
            }
        }
    }

    private fun search(text: String) {
        val token = core.beginSearch()
        interpretJob?.cancel()
        interpretJob = scope.launch {
            val wantedSpec = SupportedClasses.resolve(text)
            val startWait = SystemClock.uptimeMillis()
            var lastPromptAt = startWait
            var announcedLookAround = false
            var snapshot = selectable
            if (wantedSpec != null && wantedSpec.supported && snapshot.none { wantedSpec.matches(it) }) {
                while (core.session == token && core.mode == Mode.SEARCHING &&
                    SystemClock.uptimeMillis() - startWait < SEARCH_WAIT_MS
                ) {
                    delay(120)
                    if (core.session != token || core.mode != Mode.SEARCHING) return@launch
                    val nowLoop = SystemClock.uptimeMillis()
                    val cur = selectable
                    if (cur.any { wantedSpec.matches(it) }) {
                        snapshot = cur
                        break
                    }
                    // Also check latestCandidates after 250ms so a clear single sighting while panning locks quickly
                    if (nowLoop - startWait >= 250L) {
                        val rawMatch = latestCandidates.filter { wantedSpec.matches(it) && it.conflict == null && (!it.lowLight || it.score >= 0.35f) }
                        if (rawMatch.size == 1) {
                            snapshot = cur + rawMatch.map { it.copy(confirmed = true) }
                            break
                        }
                    }
                    if (!announcedLookAround && nowLoop - startWait >= 900L) {
                        announcedLookAround = true
                        lastPromptAt = nowLoop
                        core.announce("Looking for your ${wantedSpec.name}. Slowly look around with the camera.", nowLoop)
                    } else if (announcedLookAround && nowLoop - lastPromptAt >= 9000L) {
                        lastPromptAt = nowLoop
                        core.announce("Still looking for the ${wantedSpec.name}. Keep panning slowly.", nowLoop)
                    }
                }
            }
            val r = claude.interpret(text, snapshot)
            var chosen = if (r.needsClarification) null else snapshot.firstOrNull { it.id == r.candidateId }
            val asked = SupportedClasses.resolve(r.target).takeIf { r.target.isNotBlank() } ?: wantedSpec
            // Several of that class in view and nothing local singles this one out: never take the interpreter's pick.
            val local = asked?.let { pickTarget(it, snapshot) }
            var question = r.question
            var clarify = (local as? TargetPick.Ambiguous)?.let { asked to it.options }
            val same = chosen?.let { c -> snapshot.filter { it.label == c.label && it.confirmed } }.orEmpty()
            if (chosen != null && !(local is TargetPick.One && local.c.id == chosen.id) && same.size >= 2) {
                val spec = asked?.takeIf { it.supported && chosen!!.label in it.labels } ?: SupportedClasses.resolve(chosen.label)!!
                question = "I can help. " + clarifyQuestion(spec, same.size)
                clarify = spec to same; chosen = null
            }
            Log.i("REACH", "interpreted \"$text\" -> target=${r.target} id=${r.candidateId} clarify=${r.needsClarification} " +
                "claude=${r.fromClaude} snapshot=${snapshot.map { "#${it.id} ${it.label}" }} said=\"${r.question}\"")
            val now = SystemClock.uptimeMillis()
            if (!core.onSearchResult(token, chosen, r.target, r.confidence, !r.fromClaude, question, now)) {
                Log.i("REACH", "dropped stale interpretation of \"$text\""); return@launch
            }
            if (chosen != null && core.mode == Mode.TARGET_LOCKED) {
                talk.onLocked(asked, chosen.id, now)
                core.startGuidance(now)
            }
            // Whoever asked "which one?" (Claude, offline, or this guard), the answer is resolved locally against these options.
            else clarify?.let { (spec, options) -> talk.onClarify(spec, options, now) }
        }
    }

    /** The help button, "help", and the first launch. Describes the real state of hands and haptics. */
    fun help() = core.announce(Tutorial.text(haptics.connected, depthMode, core.bothHandsUsable()), SystemClock.uptimeMillis())

    /** Sensor thread: camera heading (degrees, relative frame). */
    fun onHeading(deg: Float, tsMs: Long) = core.onHeading(deg, tsMs)

    /** The "I've got it" button. */
    fun confirmReached() = core.confirmReached()

    /** A different lens or camera mode: the old target and hand positions are meaningless in the new frames. */
    fun onCameraChanged(intro: String) {
        interpretJob?.cancel(); talk.reset()
        latestCandidates = emptyList(); selectable = emptyList()
        core.onCameraChanged(SystemClock.uptimeMillis(), intro)
    }

    /** The hand button, and the first step after the camera starts: enrol both hands (left, then right). */
    fun beginHandSetup(intro: String = "", side: Side? = null) = core.beginHandSetup(SystemClock.uptimeMillis(), intro, side)

    fun close() {
        detectExec.shutdown()
        detectExec.awaitTermination(1, TimeUnit.SECONDS) // never close the model under a running detection
        grounder.close(); handTracker.close(); embedder?.close()
    }

    /** Main thread. Also cancels a request still waiting on the backend, so it cannot lock on afterwards. */
    fun reset() {
        interpretJob?.cancel()
        talk.reset()
        core.cancel()
        haptics.stop() // the Stop button: nothing queued may keep playing
    }
}
