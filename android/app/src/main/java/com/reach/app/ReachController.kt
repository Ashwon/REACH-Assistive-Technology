package com.reach.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.reach.app.claude.ClaudeClient
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.DetectorGate
import com.reach.app.grounding.ObjectGrounder
import com.reach.app.grounding.RecentCandidates
import com.reach.app.grounding.TargetPick
import com.reach.app.grounding.TargetSpec
import com.reach.app.grounding.pickByPosition
import com.reach.app.grounding.pickTarget
import com.reach.app.hand.HandTracker
import com.reach.app.speech.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Threads and I/O around [ReachCore], which owns every state transition and every guidance line.
 * Claude is called exactly once per search utterance. The per-frame loop (onFrame) is 100% local:
 * hand tracker + detector + core. The detector (~150 ms) runs on its own thread so it never stalls
 * fingertip tracking.
 */
private const val GROUND_INTERVAL_MS = 300L
/** A detection whose frame is older than this when it finishes is dropped, never applied. */
private const val STALE_MS = 600L

/** "got it" and friends: the user telling us they have the object. */
private val GOT_IT = listOf("got it", "have it", "holding it", "touching it", "found it", "reached")
/** "set my hand": (re)start choosing the user's hand. */
private val HAND_SETUP = listOf("my hand", "set hand", "select hand", "hand setup")

/** Spoken/typed commands that are not a search request (kept out of the search field). */
fun isCommand(text: String) = text.lowercase().let { t -> "guide" in t || (GOT_IT + HAND_SETUP).any { it in t } }

class ReachController(
    private val grounder: ObjectGrounder,
    private val handTracker: HandTracker,
    private val claude: ClaudeClient,
    speaker: Speaker,
    private val scope: CoroutineScope,
    private val onUi: (ReachSnapshot) -> Unit
) {
    private val core = ReachCore({ speaker.say(it) }) { Log.i("REACH", it) }
    val mode get() = core.mode
    private val busy = AtomicBoolean(false)

    /** Set when an offline request matched several objects; the next "left/right" answers it. */
    @Volatile private var pendingSpec: TargetSpec? = null
    @Volatile private var latestCandidates: List<Candidate> = emptyList()
    private val recent = RecentCandidates()
    /** Confirmed detections plus those that blinked out within the last second; what requests resolve against. */
    @Volatile private var selectable: List<Candidate> = emptyList()
    private val detectExec = Executors.newSingleThreadExecutor()
    private val gate = DetectorGate(GROUND_INTERVAL_MS, STALE_MS)
    @Volatile private var detFrameMs = -1L // frame time of the detections currently applied
    private var interpretJob: Job? = null // main thread

    // Debug log accumulators: camera thread, except the det* ones written by the detector thread.
    private var logAt = 0L
    private var logFrames = 0
    private var handNs = 0L
    private val detNs = AtomicLong(); private val detRuns = AtomicInteger()

    /** Called from the camera thread for every frame. Must stay fast. */
    fun onFrame(frame: Bitmap, tsMs: Long) {
        if (!busy.compareAndSet(false, true)) return
        try {
            if (gate.tryStart(tsMs)) {
                val token = core.session // the result is applied only if no transition happened meanwhile
                // Bitmaps are fresh per frame and never recycled, so handing this one over is safe.
                try { detectExec.execute { runDetector(frame, tsMs, token) } }
                catch (e: RejectedExecutionException) { return } // closing
            }

            val t1 = System.nanoTime()
            val hands = handTracker.detectAll(frame, tsMs)
            handNs += System.nanoTime() - t1
            val s = core.onFrame(hands, latestCandidates, tsMs)

            logFrames++
            if (tsMs - logAt >= 1000) {
                val runs = detRuns.getAndSet(0); val ns = detNs.getAndSet(0)
                Log.i("REACH", "fps=$logFrames handMs=${handNs / logFrames / 1_000_000} det/s=$runs " +
                    "groundMs=${if (runs > 0) ns / runs / 1_000_000 else 0} detAgeMs=${if (detFrameMs < 0) -1 else tsMs - detFrameMs} " +
                    "stale=${gate.staleDropped} mode=${s.mode} target=${s.target}#${s.targetId} ${s.track} " +
                    (if (s.mode == Mode.GUIDING) "guide=2D cue=${s.cue} err=%+.3f,%+.3f ".format(core.ex, core.ey) else "") +
                    "hands=${hands.size} sel=${s.handStatus}" +
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
    }

    /** Called once per recognized utterance. */
    fun onUtterance(text: String) {
        val lower = text.lowercase()
        val pending = pendingSpec
        val answered = pending?.let { spec ->
            (pickTarget(spec, selectable) as? TargetPick.Ambiguous)
                ?.let { pickByPosition(it.options, lower) }?.let { spec to it }
        }
        when {
            HAND_SETUP.any { it in lower } -> beginHandSetup()
            GOT_IT.any { it in lower } && core.confirmReached() -> {}
            "guide" in lower && core.startGuidance(SystemClock.uptimeMillis()) -> {} // also restarts / retries guidance
            answered != null -> {
                pendingSpec = null
                core.lockOn(answered.second, answered.first.name, answered.second.score.toDouble(), offline = true, SystemClock.uptimeMillis())
            }
            else -> {
                val token = core.beginSearch()
                val snapshot = selectable
                interpretJob?.cancel()
                interpretJob = scope.launch {
                    val r = claude.interpret(text, snapshot)
                    val chosen = if (r.needsClarification) null else snapshot.firstOrNull { it.id == r.candidateId }
                    Log.i("REACH", "interpreted \"$text\" -> target=${r.target} id=${r.candidateId} clarify=${r.needsClarification} " +
                        "claude=${r.fromClaude} snapshot=${snapshot.map { "#${it.id} ${it.label}" }} said=\"${r.question}\"")
                    if (core.onSearchResult(token, chosen, r.target, r.confidence, !r.fromClaude, r.question, SystemClock.uptimeMillis()))
                        pendingSpec = r.ambiguousSpec
                    else Log.i("REACH", "dropped stale interpretation of \"$text\"")
                }
            }
        }
    }

    /** The "I've got it" button. */
    fun confirmReached() = core.confirmReached()

    /** The "Set my hand" button, and the first step after the camera starts. */
    fun beginHandSetup(intro: String = "") = core.beginHandSetup(SystemClock.uptimeMillis(), intro)

    fun close() {
        detectExec.shutdown()
        detectExec.awaitTermination(1, TimeUnit.SECONDS) // never close the model under a running detection
        grounder.close(); handTracker.close()
    }

    /** Main thread. Also cancels a request still waiting on the backend, so it cannot lock on afterwards. */
    fun reset() {
        interpretJob?.cancel()
        pendingSpec = null
        core.cancel()
    }
}
