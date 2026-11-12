package com.reach.app

import com.reach.app.grounding.Box
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.IdMatch
import com.reach.app.grounding.SupportedClasses
import com.reach.app.grounding.TargetLock
import com.reach.app.grounding.TrackStatus
import com.reach.app.grounding.sideWord
import com.reach.app.haptic.HapticCommand
import com.reach.app.haptic.HapticCue
import com.reach.app.haptic.HapticPolicy
import com.reach.app.hand.HandResult
import com.reach.app.hand.HandSelector
import com.reach.app.hand.HandSelector.SlotState
import com.reach.app.hand.Side
import com.reach.app.spatial.DepthFrame
import com.reach.app.spatial.DepthMeter
import com.reach.app.spatial.DepthReading
import com.reach.app.spatial.DepthSample
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.GuidanceThrottle
import com.reach.app.spatial.SpatialEngine
import com.reach.app.spatial.SpatialInput
import com.reach.app.spatial.TurnMonitor
import com.reach.app.spatial.handsOver
import com.reach.app.spatial.phrase
import com.reach.app.spatial.tipToTargetM
import kotlin.math.abs

/**
 *   IDLE -> SEARCHING -> TARGET_LOCKED -> GUIDING -> REACHED
 *                                          |    ^
 *                                          v    | "guide me" (deliberate retry)
 *                                        TRACKING_LOST (the target, past every grace period)
 * A new search can start from any state; cancel returns to IDLE.
 * The user's two hands are a separate track of state (HandSelector): enrolled once by a deliberate gesture, then
 * followed; only a hand whose identity cannot be recovered safely is asked for again. Inside GUIDING, directions
 * pause (never redirect) while the target is hidden, the guided hand is uncertain, or the user has turned away.
 */
enum class Mode { IDLE, SEARCHING, TARGET_LOCKED, GUIDING, REACHED, TRACKING_LOST }

/** One enrolled hand as the screen shows it. [hand] is NONE unless TRACKED and visible this frame. */
data class HandView(val side: Side, val state: SlotState, val trackId: Int = -1, val label: String? = null,
                    val score: Float = 0f, val missingMs: Long = 0, val reason: String = "",
                    val hand: HandResult = HandResult.NONE, val last: HandResult? = null)

/** The state the screen shows, built once per camera frame from the same values guidance runs on. */
data class ReachSnapshot(
    val mode: Mode,
    val target: String,          // what the user asked for ("bottle", "coke"); "" when nothing is locked
    val track: TrackStatus,      // of the locked target
    val targetId: Int,           // -1 when nothing is locked
    val candidates: List<Candidate>,
    val hand: HandResult,        // the guided hand while it is TRACKED and in view, NONE otherwise
    val cue: Guidance,           // engine output while guiding, NONE otherwise
    val left: HandView = HandView(Side.LEFT, SlotState.NOT_ENROLLED),
    val right: HandView = HandView(Side.RIGHT, SlotState.NOT_ENROLLED),
    val guided: Side? = null,    // which hand voice guidance follows
    val enrolling: Side? = null,
    val enrollStatus: HandSelector.Status = HandSelector.Status.NONE,
    val otherHands: List<HandResult> = emptyList(), // hands in view that are not a tracked enrolled hand
    val depthSource: Boolean = false,     // frames come with a depth map (ARCore mode)
    val depth: DepthReading? = null,      // this frame's distance measurement while guiding with depth
    val leftM: Float? = null, val rightM: Float? = null, // 3D fingertip-to-target distances, only when measured
    val turn: TurnMonitor.State = TurnMonitor.State.UNKNOWN,
    val turnDeg: Float? = null,           // turn needed to face where the target was (+ right), when known
    val haptic: HapticCommand = HapticCommand.STOP,
    val targetBox: Box? = null,           // last known box of the locked target (identity, may be stale)
    val identity: String? = null,         // the registered object the lock is meant to be ("work laptop"), if any
    val identityVerified: Boolean = true, // its photo matched the CURRENT track (false after the track id changed)
    val frameW: Int = 0, val frameH: Int = 0, // analysis frame size, for the overlay transform
    val band: HapticCue? = null           // the vibration cue requested this frame (requested, not proof it was felt)
) {
    fun hands(side: Side) = if (side == Side.LEFT) left else right
}

/**
 * The guidance state machine, pure Kotlin so it is unit-tested. Every state change and every spoken line it
 * produces happen under one lock, so a transition and the speech it allows cannot interleave across the
 * camera, detector, sensor and main threads.
 *
 * Every transition bumps [session]. Async work (a detector run, a Claude interpretation) carries the session
 * it started in and is dropped once the session has moved on, so a late result can never restart, redirect
 * or re-lock guidance. REACHED and TRACKING_LOST end directional speech; only the user leaves them, by
 * "guide me" or a new search. Detection never touches the hands, and the hands never touch the target.
 *
 * Reached needs evidence: depth contact from SpatialEngine (only once its fingertip-depth estimate is validated,
 * see SpatialEngine.DEPTH_REACHED_VALIDATED), or the user saying / tapping that they have the object.
 * The band ([haptic], every frame) gets one Haptic Language cue from the same state voice guidance runs on (see
 * [bandCue]): directions only while the target is tracked and the guided hand is usable, forward / near only with valid
 * depth this frame and the fingertip lined up, HOLD once per pause, REACHED only in REACHED. Separately, the two enrolled
 * hands are compared with valid 3D distances (HapticPolicy) to choose the guided hand; that comparison never drives a motor.
 *
 * A registered object ("my work laptop") is bound to the lock as [identity], verified for one tracker id only. When the
 * lock follows the object to a new id (occlusion, re-detection), directions pause until the controller has matched the
 * reference photo again ([onIdentityCheck]); a different object matching better, or [ID_CHECKS] unsure checks in a
 * row, drops the target. A tracker id is never an identity.
 */
class ReachCore(private val say: (String) -> Unit, private val log: (String) -> Unit = {},
                depthReachedValidated: Boolean = SpatialEngine.DEPTH_REACHED_VALIDATED,
                private val haptic: (HapticCue?) -> Unit = {},
                handConfig: HandSelector.Config = HandSelector.Config()) {

    companion object {
        /** Let "guiding..." / "got your hand" finish before direction cues resume. */
        const val INTRO_QUIET_MS = 3000L
        const val RELOCK_QUIET_MS = 1500L
        /** Facing where the target was again for this long without re-finding it: ask the user to scan. */
        const val RETURN_FIND_MS = 5000L
        /** The camera turned more than this since the target was last detected: its box no longer guides. */
        const val BOX_TURN_DEG = 15f
        /** Depth source present but no valid distance this long while guiding: say direction-only, once. */
        const val NO_DEPTH_SAY_MS = 1500L
        /** Unsure photo matches in a row (detector runs with the target in view) before a registered target is dropped. */
        const val ID_CHECKS = 3
        fun handSetup(side: Side) = "Raise your ${side.word} hand, then close it into a fist."
        /** Rough TTS duration, so a long announcement is not cut off by the next prompt. */
        fun speechMs(line: String) = 60L * line.length
        /** Cues that pause guidance (or say stop): the band holds. */
        private val HOLD_CUES = setOf(Guidance.TURNED_AWAY, Guidance.IDENTITY_CHECK, Guidance.HAND_UNCERTAIN, Guidance.STOP,
            Guidance.BACK, Guidance.HAND_SHOW, Guidance.HAND_TOO_FAR, Guidance.HAND_FIST, Guidance.HAND_AMBIGUOUS, Guidance.HAND_SAME)
    }

    @Volatile var mode = Mode.IDLE; private set
    @Volatile var session = 0; private set
    @Volatile var targetName = ""; private set
    /** The registered object the locked target is meant to be, or null for an ordinary category target. */
    @Volatile var identity: String? = null; private set
    private var verifiedId = -1
    private var idFails = 0
    /** Wording only: device rotation means body rotation only when the phone is chest-mounted. */
    @Volatile var chestMounted = true
    /** Horizontal field of view of the analysis frame (deg), for the target's bearing. */
    @Volatile var fovDeg = 75f
    val lock = TargetLock()
    private val spatial = SpatialEngine(depthReachedValidated)
    private val meter = DepthMeter()
    private val turn = TurnMonitor()
    private val policy = HapticPolicy()
    private var band: HapticCue? = null
    private var depthOk = false   // this frame's depth sample is valid
    private var refoundAt = -1L   // when "found again" was said, after an announced loss
    @Volatile private var depthSource = false
    private var noDepthSince = -1L
    private var saidNoDepth = false
    private var lastReading: DepthReading? = null
    private var dists: Pair<Float?, Float?> = null to null
    private val throttle = GuidanceThrottle()
    /** Hand-setup prompts outside guidance: said when they change, not repeated. */
    private val setupThrottle = GuidanceThrottle(statusRepeatMs = Long.MAX_VALUE / 4)
    private val hands = HandSelector(handConfig)
    private val enrollQueue = ArrayDeque<Side>()
    private var guided: Side? = null
    private var preferred: Side? = null
    private var lastCue = Guidance.NONE
    private var introPending = false
    private var lastTrack = TrackStatus.NONE
    private var lastTurn = TurnMonitor.State.UNKNOWN
    private var lossAnnounced = false
    private var returnedAt = -1L
    private val slotStates = HashMap<Side, SlotState>()
    val ex get() = spatial.ex
    val ey get() = spatial.ey

    /** "Set up my hands" (both, left first) or one side: enrol by the gesture. Independent of any object search. */
    @Synchronized fun beginHandSetup(nowMs: Long, intro: String = "", side: Side? = null) {
        enrollQueue.clear()
        if (side == null) enrollQueue.addAll(listOf(Side.LEFT, Side.RIGHT)) else enrollQueue.add(side)
        enrollNext(nowMs, intro)
    }

    private fun enrollNext(nowMs: Long, intro: String) {
        val side = enrollQueue.removeFirstOrNull() ?: return
        val line = intro + handSetup(side)
        hands.beginEnrollment(side, nowMs, heardAtMs = nowMs + speechMs(line))
        setupThrottle.reset(); setupThrottle.filter(Guidance.HAND_SHOW, nowMs) // the line below already says it
        setupThrottle.quiet(nowMs + speechMs(line))
        say(line)
    }

    /** A user-requested line (help, where, a clarification): spoken, and guidance cues wait until it is finished. */
    @Synchronized fun announce(line: String, nowMs: Long) {
        throttle.quiet(nowMs + speechMs(line)); setupThrottle.quiet(nowMs + speechMs(line))
        say(line)
    }

    @Synchronized fun bothHandsUsable() = usable(Side.LEFT) && usable(Side.RIGHT)

    /** Filtered camera heading (deg), NaN before the first sensor sample. Diagnostics. */
    @Synchronized fun headingDeg() = turn.heading

    /** "Guide my left hand": voice guidance follows that hand from now on (if it is enrolled). */
    @Synchronized fun useHand(side: Side, nowMs: Long) {
        preferred = side
        if (mode != Mode.GUIDING) return
        guided = side; spatial.reset(); throttle.quiet(nowMs + RELOCK_QUIET_MS)
        if (usable(side)) say("Guiding your ${side.word} hand.")
        else beginHandSetup(nowMs, "I need to confirm your ${side.word} hand. ", side)
    }

    /** The camera (lens or ARCore/CameraX) changed: boxes and hand positions from the old one mean nothing now. */
    @Synchronized fun onCameraChanged(nowMs: Long, intro: String) {
        forgetTarget()
        enter(Mode.IDLE)
        hands.clear()
        beginHandSetup(nowMs, intro)
    }

    /** Main thread. Returns the token the interpretation must hand back to [onSearchResult]. */
    @Synchronized fun beginSearch(): Int {
        forgetTarget() // a new request never leaves guidance pointed at the previous object
        enter(Mode.SEARCHING)
        return session
    }

    /** Result of the interpretation started by [beginSearch]; ignored unless that search is still current. */
    @Synchronized fun onSearchResult(token: Int, chosen: Candidate?, name: String, confidence: Double,
                                     offline: Boolean, question: String, nowMs: Long, prefix: String = "",
                                     registered: String? = null): Boolean {
        if (token != session || mode != Mode.SEARCHING) return false // cancelled or superseded
        if (chosen == null) {
            say(prefix + question.ifBlank { "I'm not sure which object you mean." })
            enter(Mode.IDLE)
        } else lockOn(chosen, name, confidence, offline, nowMs, prefix, registered)
        return true
    }

    /** Locks only a settled detection of the requested class, and says how sure it is. A detector score is never
     *  "verified". [prefix] is said first in the same utterance (e.g. how a clarification answer was resolved). */
    @Synchronized fun lockOn(c: Candidate, name: String, confidence: Double, offline: Boolean, nowMs: Long,
                             prefix: String = "", registered: String? = null): Boolean {
        val wanted = name.ifBlank { c.label }
        val spec = SupportedClasses.resolve(wanted)
        val accepted = spec?.labels ?: setOf(c.label)
        val refusal = when {
            // e.g. Claude matched "laptop bag" to a laptop: an unsupported request is never re-mapped to another class
            spec != null && !spec.supported -> SupportedClasses.cannotFind(spec)
            c.label in SupportedClasses.REFUSED -> "I only help find everyday objects."
            c.label !in accepted -> "That looks like a ${c.label} to me, not a $wanted. Ask again, or move the camera for a better view."
            !c.confirmed -> "I only saw something like a $wanted for a moment. Hold the camera still and ask again."
            c.conflict != null && c.conflict !in accepted ->
                "I can't tell if that's a ${c.label} or a ${c.conflict}. Move the camera a little and ask again."
            // Replayed underexposed A15 frames produced only junk boxes: a weak score in the dark is not an object.
            c.lowLight && c.score < Candidate.STRONG_SCORE ->
                "It's too dark for me to tell if that's a $wanted. Turn on a light or move closer, and ask again."
            else -> null
        }
        if (refusal != null) { say(prefix + refusal); enter(Mode.IDLE); return false }
        targetName = registered ?: wanted
        identity = registered; verifiedId = c.id; idFails = 0 // a photo match, for this track only
        // The box is a measurement of its own frame, not of now: Claude may have taken seconds to answer.
        lock.lock(c, if (c.frameMs >= 0) c.frameMs else nowMs); turn.clear(); lossAnnounced = false; returnedAt = -1L
        enter(Mode.TARGET_LOCKED)
        val src = if (offline) " Offline mode." else ""
        say(prefix + when {
            c.sure -> "Found the $wanted, ${(confidence * 100).toInt()} percent sure.$src Say guide me when ready."
            else -> "I might see the $wanted, but I'm not sure${if (c.lowLight) " because it's dark" else ""}.$src " +
                "Say guide me to try it, or move the camera for a better view."
        })
        return true
    }

    private fun forgetTarget() { lock.clear(); targetName = ""; turn.clear(); identity = null; verifiedId = -1; refoundAt = -1L }

    private fun identityVerified() = identity == null || verifiedId == lock.id

    /** Detector thread: the registered object and tracker id whose photo match is due, or null. */
    @Synchronized fun identityCheckDue(): Pair<String, Int>? = identity?.takeIf {
        lock.active && verifiedId != lock.id && (mode == Mode.TARGET_LOCKED || mode == Mode.GUIDING)
    }?.let { it to lock.id }

    /** The photo match for [identityCheckDue]; ignored if the target or its track changed meanwhile. */
    @Synchronized fun onIdentityCheck(name: String, lockId: Int, result: IdMatch) {
        if (identity != name || lock.id != lockId || verifiedId == lockId) return
        log("identity check $name#$lockId -> $result")
        val drop = when {
            result is IdMatch.Match && result.c.id == lockId -> {
                verifiedId = lockId; idFails = 0
                if (mode == Mode.GUIDING) say("This still looks like your $name. Continuing.")
                null
            }
            result is IdMatch.Match -> "Another ${lock.label} looks more like your $name than this one."
            result is IdMatch.Unsure && ++idFails >= ID_CHECKS -> "I can't confirm this is your $name from its photo."
            else -> null // not in view: nothing to compare yet
        } ?: return
        say("$drop I've stopped. Say find my $name to look again, or tell me which one.")
        forgetTarget(); enter(Mode.IDLE)
    }

    /** "guide me": start, restart or retry guidance to the locked target. Directions start only once the guided
     *  hand is enrolled and in view; if no hand can be used, this asks for the gesture first. */
    @Synchronized fun startGuidance(nowMs: Long): Boolean {
        if (!lock.active || mode == Mode.IDLE || mode == Mode.SEARCHING) return false
        spatial.reset(); throttle.reset(); meter.reset()
        lastCue = Guidance.NONE; introPending = true; returnedAt = -1L; refoundAt = -1L
        noDepthSince = -1L; saidNoDepth = false; lastReading = null
        val order = listOfNotNull(preferred, guided, Side.RIGHT, Side.LEFT).distinct()
        guided = order.firstOrNull { hands.slot(it).state == SlotState.TRACKED } ?: order.firstOrNull { usable(it) }
            ?: order.firstOrNull { hands.slot(it).enrolled }
        enter(Mode.GUIDING)
        val g = guided
        val start = if (depthSource) "Guiding by direction. Distance is an estimate." else "Guiding by direction only, not distance."
        when {
            g == null -> beginHandSetup(nowMs, "$start First, let's set up your hands. ")
            !usable(g) -> beginHandSetup(nowMs, "$start First, I need to confirm your ${g.word} hand. ", g)
            else -> say("$start Guiding your ${g.word} hand. Hold it out.")
        }
        return true
    }

    /** Enrolled and not waiting for recovery: its identity is known (it may be briefly out of view or crowded). */
    private fun usable(side: Side) = hands.slot(side).state.let {
        it == SlotState.TRACKED || it == SlotState.MISSING || it == SlotState.UNCERTAIN
    }

    /** The user says or taps that they have the object: the only "reached" evidence 2D guidance has. */
    @Synchronized fun confirmReached(): Boolean {
        if (mode != Mode.GUIDING && mode != Mode.TRACKING_LOST) return false
        reach()
        return true
    }

    @Synchronized fun cancel() {
        forgetTarget()
        enter(Mode.IDLE)
        policy.force(HapticCommand.STOP)
        band = null; haptic(null)
        say("Reset.")
    }

    /** Detector thread. [token] is the session when the frame was taken; older results are dropped. */
    @Synchronized fun onDetections(cands: List<Candidate>, frameMs: Long, token: Int) {
        if (token != session) return
        if (mode != Mode.TARGET_LOCKED && mode != Mode.GUIDING && mode != Mode.TRACKING_LOST) return
        val seen = lock.update(cands, frameMs) ?: return
        turn.seenTarget((seen.box.centerX() - 0.5f) * fovDeg, frameMs) // the target's bearing: where to turn back to
    }

    /** Sensor thread: camera heading in degrees (relative frame from the game rotation vector). */
    @Synchronized fun onHeading(deg: Float, tsMs: Long) = turn.onHeading(deg, tsMs)

    /**
     * Camera thread, every frame. [depthFrame]: the depth map registered to this frame, null when there is none
     * (2D camera mode, or depth mode while ARCore has none ready). [depthSource]: the camera mode provides depth.
     * [depth] injects a ready measurement instead (tests).
     */
    @Synchronized fun onFrame(detected: List<HandResult>, candidates: List<Candidate>, tsMs: Long,
                              depth: DepthSample? = null, depthFrame: DepthFrame? = null,
                              depthSource: Boolean = depthFrame != null): ReachSnapshot {
        this.depthSource = depthSource
        hands.update(detected, tsMs)
        trackHandIdentity(tsMs)
        if (introPending) { throttle.quiet(tsMs + INTRO_QUIET_MS); introPending = false }
        lock.box?.let { lock.noteHands(handsOver(it, detected), tsMs) }
        val track = lock.statusAt(tsMs)
        trackTarget(track, tsMs)

        lastReading = null; dists = null to null; depthOk = false
        if (mode == Mode.GUIDING) guide(detected, track, tsMs, depth, depthFrame)
        else setupPrompt()?.let { p -> setupThrottle.filter(p, tsMs)?.let { say(p.phrase(targetName, enrollWord())!!) } }
        hapticFrame(track, tsMs)
        val m = mode
        fun view(side: Side) = hands.slot(side).let { s ->
            HandView(side, s.state, s.trackId, s.label, s.last?.score ?: 0f, s.missingMs(tsMs), s.reason, hands.hand(side), s.last)
        }
        val g = guided
        return ReachSnapshot(m, targetName, track, if (lock.active) lock.id else -1, candidates,
            if (m == Mode.GUIDING && g != null) hands.hand(g) else HandResult.NONE,
            if (m == Mode.GUIDING || m == Mode.REACHED) lastCue else Guidance.NONE,
            view(Side.LEFT), view(Side.RIGHT), g, hands.enrolling, hands.status, hands.others, depthSource, lastReading,
            dists.first, dists.second, turn.state, turn.toTarget(tsMs), policy.current, lock.box,
            identity, identityVerified(), band = band)
    }

    /** Announces enrolments, continues a two-hand setup, and pauses guidance when the guided hand needs recovery. */
    private fun trackHandIdentity(tsMs: Long) {
        hands.enrolled?.let { side ->
            log("hand enrolled ${side.word}: ${hands.describe(tsMs)}")
            val got = "Got your ${side.word} hand. "
            if (enrollQueue.isNotEmpty()) enrollNext(tsMs, got)
            else say(got + if (usable(Side.LEFT) && usable(Side.RIGHT)) "Both hands are set." else "Keep it in view.")
            if (mode == Mode.GUIDING && guided?.let(::usable) != true) guided = side
            throttle.quiet(tsMs + RELOCK_QUIET_MS)
        }
        for (side in Side.values()) {
            val s = hands.slot(side)
            val before = slotStates.put(side, s.state)
            if (before == s.state) continue
            if (before != null) log("hand ${side.word}: $before -> ${s.state}${if (s.reason.isEmpty()) "" else " (${s.reason})"} ${hands.describe(tsMs)}")
            if (mode != Mode.GUIDING || side != guided) continue
            when (s.state) {
                SlotState.UNCERTAIN -> say(Guidance.HAND_UNCERTAIN.phrase(targetName, "${side.word} hand")!!)
                SlotState.NEEDS_RECOVERY -> if (hands.enrolling == null) {
                    beginHandSetup(tsMs, "I can't be sure which is your ${side.word} hand. Guidance paused. ", side)
                }
                else -> {}
            }
            throttle.quiet(tsMs + RELOCK_QUIET_MS)
        }
    }

    /** Target-state speech: turning away and back, losses past every grace period, finding it again. */
    private fun trackTarget(track: TrackStatus, tsMs: Long) {
        val active = lock.active && (mode == Mode.TARGET_LOCKED || mode == Mode.GUIDING || mode == Mode.TRACKING_LOST)
        val t = turn.step(tsMs)
        if (active && t != lastTurn) {
            log("turn $lastTurn -> $t toTarget=${turn.toTarget(tsMs)}")
            if (t == TurnMonitor.State.AWAY) {
                say(turnLine(tsMs)); lossAnnounced = true; returnedAt = -1L
                throttle.mark(Guidance.TURNED_AWAY, tsMs); throttle.quiet(tsMs + RELOCK_QUIET_MS)
            } else if (t == TurnMonitor.State.ALIGNED && lastTurn == TurnMonitor.State.AWAY && track != TrackStatus.TRACKING) {
                lock.relaxPosition(); returnedAt = tsMs // the object may be anywhere in view now; look again
                say("Hold there. I'm looking for the $targetName.")
            }
        }
        lastTurn = t
        if (active && track == TrackStatus.TRACKING && lastTrack != TrackStatus.TRACKING) {
            if (lossAnnounced) {
                log("found again $targetName#${lock.id} (refinds=${lock.reacquisitions})")
                say("Found the $targetName again." + if (mode == Mode.TRACKING_LOST) " Say guide me to continue." else "")
                throttle.quiet(tsMs + RELOCK_QUIET_MS); refoundAt = tsMs
            }
            lossAnnounced = false; returnedAt = -1L
        }
        lastTrack = track
    }

    /** The target box as a measurement: fresh, not turned away, and the camera has not rotated noticeably since it
     *  was seen (a box is a measurement of the image it came from). Null otherwise; identity is kept elsewhere. */
    private fun freshBox(tsMs: Long): Box? {
        if (turn.state == TurnMonitor.State.AWAY) return null
        if (turn.turnedSinceSeen(tsMs)?.let { abs(it) > BOX_TURN_DEG } == true) return null
        return lock.boxAt(tsMs)
    }

    private fun turnLine(tsMs: Long): String {
        val d = turn.toTarget(tsMs)
        val way = d?.let { if (it > 0) "right" else "left" }
        return if (chestMounted) "You may have turned away from the $targetName. Turn back toward your previous direction" +
            (way?.let { ", to your $it." } ?: ".")
        else "The camera may have turned away from the $targetName. Point it back toward the previous direction" +
            (way?.let { ", to the $it." } ?: ".")
    }

    /** "Where was it?": only what is known now — what the camera sees, or the direction the target was last seen in. */
    @Synchronized fun whereIsTarget(nowMs: Long): String {
        if (!lock.active) return "I'm not tracking anything right now. Tell me what to find."
        val b = freshBox(nowMs)
        if (b != null) return "I can see the $targetName now, ${b.sideWord()} of the camera view."
        val d = turn.toTarget(nowMs)
        val ago = lock.unseenMs(nowMs) / 1000
        val who = if (chestMounted) "you" else "the camera"
        return when {
            d == null -> "I last saw the $targetName $ago seconds ago. I can't tell which way $who turned. Slowly scan the area."
            abs(d) < 30f -> "When I last saw the $targetName, it was in front of $who. Hold still while I look for it."
            abs(d) > 135f -> "When I last saw the $targetName, it was behind $who. Turn around slowly, to the ${if (d > 0) "right" else "left"}."
            else -> "When I last saw the $targetName, it was to the ${if (d > 0) "right" else "left"}. Turn ${if (d > 0) "right" else "left"} slowly and I'll look for it."
        }
    }

    private fun enrollWord() = hands.enrolling?.let { "${it.word} hand" } ?: "hand"

    private fun setupPrompt(): Guidance? = when (hands.status) {
        HandSelector.Status.SHOW -> Guidance.HAND_SHOW
        HandSelector.Status.TOO_FAR -> Guidance.HAND_TOO_FAR
        HandSelector.Status.CONFIRM -> Guidance.HAND_FIST
        HandSelector.Status.AMBIGUOUS -> Guidance.HAND_AMBIGUOUS
        HandSelector.Status.SAME_AS_OTHER -> Guidance.HAND_SAME
        HandSelector.Status.NONE -> null
    }

    private fun guide(detected: List<HandResult>, track: TrackStatus, tsMs: Long, injected: DepthSample?, frame: DepthFrame?) {
        // Guiding needs a usable hand: take one that is enrolled, else keep asking (the prompt expires; renew it).
        // A guided hand that needs recovery stays the guided one: its guidance pauses until the user confirms it.
        if (guided == null) guided = listOfNotNull(preferred, Side.RIGHT, Side.LEFT).firstOrNull(::usable)
        val g = guided
        if (hands.enrolling == null && (g == null || !usable(g))) beginHandSetup(tsMs, "", g)
        val hand = g?.let { hands.hand(it) } ?: HandResult.NONE
        val tip = hand.index // all five tips and this one come from the one guided hand
        val away = turn.state == TurnMonitor.State.AWAY
        val box = freshBox(tsMs)
        val reading = frame?.let { meter.measure(it, box, hand, detected, tsMs) }
            ?: if (depthSource) DepthReading(null, null, null, null, "no depth map yet") else null
        lastReading = reading
        val targetM = reading?.targetM
        if (frame != null && box != null && targetM != null) dists =
            hands.hand(Side.LEFT).takeIf { it.present }?.let { tipToTargetM(frame, box, targetM, it) } to
            hands.hand(Side.RIGHT).takeIf { it.present }?.let { tipToTargetM(frame, box, targetM, it) }
        val depth = injected ?: reading?.sample
        depthOk = depth?.valid == true
        if (depthSource && depth?.valid != true && hand.present) {
            if (noDepthSince < 0) noDepthSince = tsMs
            if (!saidNoDepth && tsMs - noDepthSince >= NO_DEPTH_SAY_MS) {
                saidNoDepth = true
                log("no depth: ${reading?.reason}")
                say("Distance unavailable. Direction only.")
                throttle.quiet(tsMs + RELOCK_QUIET_MS)
            }
        } else noDepthSince = -1L
        val engine = spatial.update(SpatialInput(box, tip?.x, tip?.y, depth, tsMs, occluded = track == TrackStatus.OCCLUDED))
        val word = g?.let { "${it.word} hand" } ?: "hand"
        val cue = setupPrompt() ?: when {
            away -> Guidance.TURNED_AWAY
            !identityVerified() -> Guidance.IDENTITY_CHECK
            g != null && hands.slot(g).state == SlotState.UNCERTAIN -> Guidance.HAND_UNCERTAIN
            else -> engine // no usable hand: no direction (the engine has no fingertip)
        }
        lastCue = cue
        if (cue == Guidance.REACHED) { reach(); return }

        if (!away && track == TrackStatus.LOST) {
            if (returnedAt < 0) return lose(if (lock.wasOccluded)
                "The $targetName is blocked from view. Move your hand or the camera slightly. I'm still looking for it."
                else "I can't see the $targetName anymore. Move the camera slowly. I'm still looking for it.")
            if (tsMs - returnedAt >= RETURN_FIND_MS) return lose("I can't find the $targetName from here. Slowly scan the area. I'm still looking for it.")
        }

        throttle.filter(cue, tsMs)?.let { spoken ->
            log("cue=$spoken target=${box?.let { "%.2f,%.2f".format(it.centerX(), it.centerY()) }} track=$track " +
                "tip=${tip?.let { "%.2f,%.2f".format(it.x, it.y) }} err=%+.3f,%+.3f guided=$g ${hands.describe(tsMs)}".format(spatial.ex, spatial.ey) +
                (reading?.let { " depth=${it.reason} targetZ=${it.targetM} tipZest=${it.tipM} arTipZ=${it.arTipM} d3=${it.sample?.dist3dM}" } ?: "") +
                " dL=${dists.first} dR=${dists.second}")
            say(when {
                spoken == Guidance.TURNED_AWAY -> turnLine(tsMs)
                hands.enrolling != null && spoken == setupPrompt() -> spoken.phrase(targetName, enrollWord())!! // the hand being enrolled
                else -> spoken.phrase(targetName, word)!!
            })
        }
    }

    /** Every frame: the band's cue, and (guiding, valid 3D distances only) which hand is closer, to pick the guided hand. */
    private fun hapticFrame(track: TrackStatus, tsMs: Long) {
        val g = guided
        val cmd = when (mode) {
            Mode.IDLE, Mode.SEARCHING, Mode.TARGET_LOCKED -> policy.force(HapticCommand.STOP)
            Mode.REACHED -> policy.force(HapticCommand.REACHED)
            Mode.TRACKING_LOST -> policy.force(HapticCommand.TRACKING_LOST)
            Mode.GUIDING -> when {
                turn.state == TurnMonitor.State.AWAY || g == null || hands.slot(g).state == SlotState.NEEDS_RECOVERY ->
                    policy.force(HapticCommand.TRACKING_LOST)
                track != TrackStatus.TRACKING || lastCue == Guidance.HAND_UNCERTAIN || lastCue == Guidance.IDENTITY_CHECK ->
                    policy.force(HapticCommand.EQUAL_OR_UNCERTAIN)
                else -> policy.compare(dists.first, dists.second, tsMs)
            }
        }
        cmd?.let { log("closer $it") }
        // One hand is reliably closer: unless the user chose a hand, voice guidance follows that hand.
        val closer = when (cmd) { HapticCommand.LEFT_HAND_CLOSER -> Side.LEFT; HapticCommand.RIGHT_HAND_CLOSER -> Side.RIGHT; else -> null }
        if (closer != null && closer != g && preferred == null && hands.slot(closer).state == SlotState.TRACKED) {
            guided = closer; spatial.reset(); throttle.quiet(tsMs + RELOCK_QUIET_MS)
            say("Your ${closer.word} hand is closer. Guiding your ${closer.word} hand.")
        }
        val cue = bandCue(track, tsMs)
        if (cue != band) log("band $band -> $cue")
        band = cue
        haptic(cue)
    }

    /**
     * The Haptic Language cue for this frame, from the state voice guidance runs on, highest priority first. A pause or
     * "stop" is HOLD (played once, then nothing until a direction is valid again); a hidden target or hand gives nothing;
     * up / down / "lined up" (2D) have no vibration. Forward and near need this frame's depth to be valid and the
     * fingertip lined up now: a held or smoothed cue alone never produces them.
     */
    private fun bandCue(track: TrackStatus, tsMs: Long): HapticCue? = when (mode) {
        Mode.IDLE, Mode.TARGET_LOCKED -> null
        Mode.SEARCHING -> HapticCue.SEARCH
        Mode.REACHED -> HapticCue.REACHED
        Mode.TRACKING_LOST -> if (refoundAt >= 0) HapticCue.REACQUIRED else HapticCue.TARGET_LOST
        Mode.GUIDING -> {
            val g = guided
            val gap = spatial.gapM
            when {
                turn.state == TurnMonitor.State.AWAY || g == null || !usable(g) || hands.enrolling != null || lastCue in HOLD_CUES ->
                    HapticCue.HOLD
                refoundAt >= 0 && tsMs - refoundAt < RELOCK_QUIET_MS -> HapticCue.REACQUIRED
                track != TrackStatus.TRACKING -> if (returnedAt >= 0) HapticCue.SEARCH else null
                lastCue == Guidance.LEFT -> HapticCue.MOVE_LEFT
                lastCue == Guidance.RIGHT -> HapticCue.MOVE_RIGHT
                !depthOk || !spatial.aligned || gap == null -> null
                lastCue == Guidance.CLOSER -> HapticCue.FORWARD
                lastCue == Guidance.NEAR || lastCue == Guidance.AT_TARGET -> HapticCue.near(gap)
                else -> null
            }
        }
    }

    /** Terminal for guidance: one confirmation; the flush in the speaker drops anything still queued. */
    private fun reach() {
        enter(Mode.REACHED)
        lastCue = Guidance.REACHED
        policy.force(HapticCommand.REACHED)
        say(Guidance.REACHED.phrase(targetName)!!)
    }

    private fun lose(line: String) {
        enter(Mode.TRACKING_LOST)
        lastCue = Guidance.NONE; lossAnnounced = true; returnedAt = -1L; refoundAt = -1L
        policy.force(HapticCommand.TRACKING_LOST)
        say(line)
    }

    private fun enter(m: Mode) {
        if (m != mode) log("mode $mode -> $m")
        mode = m; session++
    }
}
