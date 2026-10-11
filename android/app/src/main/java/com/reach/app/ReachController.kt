package com.reach.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.reach.app.claude.ClaudeClient
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.DetectorGate
import com.reach.app.grounding.ObjectGrounder
import com.reach.app.grounding.RecentCandidates
import com.reach.app.grounding.TargetLock
import com.reach.app.grounding.TargetPick
import com.reach.app.grounding.TargetSpec
import com.reach.app.grounding.pickByPosition
import com.reach.app.grounding.pickTarget
import com.reach.app.hand.HandResult
import com.reach.app.hand.HandTracker
import com.reach.app.spatial.*
import com.reach.app.speech.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * State machine:
 *   IDLE -> (utterance) -> INTERPRETING -> TARGET_LOCKED -> REACHING -> DONE
 * Claude is called exactly once per utterance (INTERPRETING). The per-frame
 * loop (onFrame) is 100% local: grounder + hand tracker + SpatialEngine.
 * The detector (~150 ms) runs on its own thread so it never stalls fingertip tracking.
 */
private const val GROUND_INTERVAL_MS = 300L
/** A detection whose frame is older than this when it finishes is dropped, never applied. */
private const val STALE_MS = 600L

enum class Mode { IDLE, INTERPRETING, TARGET_LOCKED, REACHING, DONE }

class ReachController(
    private val grounder: ObjectGrounder,
    private val handTracker: HandTracker,
    private val claude: ClaudeClient,
    private val speaker: Speaker,
    private val scope: CoroutineScope,
    private val onUi: (List<Candidate>, Int, HandResult, String) -> Unit
) {
    @Volatile var mode = Mode.IDLE; private set
    private val spatial = SpatialEngine()
    private val throttle = GuidanceThrottle()
    private val busy = AtomicBoolean(false)
    private val lock = TargetLock()

    @Volatile private var targetName: String = ""
    /** Set when an offline request matched several objects; the next "left/right" answers it. */
    @Volatile private var pendingSpec: TargetSpec? = null
    @Volatile private var latestCandidates: List<Candidate> = emptyList()
    private val recent = RecentCandidates()
    /** latestCandidates plus objects that blinked out within the last second; what requests resolve against. */
    @Volatile private var selectable: List<Candidate> = emptyList()
    private val detectExec = Executors.newSingleThreadExecutor()
    private val gate = DetectorGate(GROUND_INTERVAL_MS, STALE_MS)
    @Volatile private var detFrameMs = -1L // frame time of the detections currently applied

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
                // Bitmaps are fresh per frame and never recycled, so handing this one over is safe.
                try { detectExec.execute { runDetector(frame, tsMs) } }
                catch (e: RejectedExecutionException) { return } // closing
            }

            val t1 = System.nanoTime()
            val hand = handTracker.detect(frame, tsMs)
            handNs += System.nanoTime() - t1
            val handPt = hand.index?.let { Pair(it.x, it.y) }

            if (mode == Mode.REACHING) {
                val cue = spatial.update(
                    SpatialInput(
                        targetBox = lock.boxAt(tsMs)?.let { RectF(it.left, it.top, it.right, it.bottom) },
                        handX = handPt?.first, handY = handPt?.second,
                        handSize = if (hand.present) hand.handSize else null,
                        timestampMs = tsMs
                    )
                )
                throttle.filter(cue, tsMs)?.let { spoken ->
                    Log.i("REACH", "cue=$spoken target=${lock.box?.let { "%.2f,%.2f".format(it.centerX(), it.centerY()) }} hand=${handPt?.let { "%.2f,%.2f".format(it.first, it.second) }}")
                    speaker.sayGuidance(spoken)
                    if (spoken == Guidance.REACHED) mode = Mode.DONE
                }
            }

            logFrames++
            if (tsMs - logAt >= 1000) {
                val runs = detRuns.getAndSet(0); val ns = detNs.getAndSet(0)
                Log.i("REACH", "fps=$logFrames handMs=${handNs / logFrames / 1_000_000} det/s=$runs " +
                    "groundMs=${if (runs > 0) ns / runs / 1_000_000 else 0} detAgeMs=${if (detFrameMs < 0) -1 else tsMs - detFrameMs} " +
                    "stale=${gate.staleDropped} mode=$mode target=$targetName#${lock.id} ${lock.status} " +
                    "hand=${if (hand.present) "score=%.2f size=%.2f".format(hand.score, hand.handSize) else null} " +
                    "cands=" + latestCandidates.joinToString { "#${it.id} ${it.label}/${it.beverage}/${"%.2f".format(it.score)}" })
                if (hand.present) Log.i("REACH", "tips " + hand.tips.joinToString(" ") {
                    "${it.finger}=%.3f,%.3f,z%.3f${if (it.inFrame) "" else "(off)"}".format(it.x, it.y, it.z) +
                        (it.visibility?.let { v -> " vis%.2f".format(v) } ?: "") + (it.presence?.let { p -> " pres%.2f".format(p) } ?: "")
                })
                logAt = tsMs; logFrames = 0; handNs = 0
            }
            onUi(latestCandidates, if (lock.active) lock.id else -1, hand,
                "mode=$mode target=${targetName.ifEmpty { "-" }} ${lock.status}")
        } finally {
            busy.set(false)
        }
    }

    /** Detector thread. Results are applied only if the gate says they are still fresh. */
    private fun runDetector(frame: Bitmap, frameMs: Long) {
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
        selectable = recent.update(cands, frameMs)
        detFrameMs = frameMs
        if (mode == Mode.REACHING || mode == Mode.TARGET_LOCKED) lock.update(cands, frameMs)
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
            mode == Mode.TARGET_LOCKED && "guide" in lower -> {
                spatial.reset(); throttle.reset()
                mode = Mode.REACHING
                speaker.say("Guiding. Move your hand into view.")
            }
            answered != null -> {
                pendingSpec = null
                lockOn(answered.second, answered.first.name, answered.second.score.toDouble(), offline = true)
            }
            else -> {
                mode = Mode.INTERPRETING
                val snapshot = selectable
                scope.launch {
                    val r = claude.interpret(text, snapshot)
                    pendingSpec = r.ambiguousSpec
                    val chosen = snapshot.firstOrNull { it.id == r.candidateId }
                    Log.i("REACH", "interpreted \"$text\" -> target=${r.target} id=${r.candidateId} clarify=${r.needsClarification} " +
                        "claude=${r.fromClaude} snapshot=${snapshot.map { "#${it.id} ${it.label}" }} said=\"${r.question}\"")
                    if (r.needsClarification || chosen == null) {
                        speaker.say(r.question.ifBlank { "I'm not sure which object you mean." })
                        mode = Mode.IDLE
                    } else {
                        lockOn(chosen, r.target, r.confidence, offline = !r.fromClaude)
                    }
                }
            }
        }
    }

    private fun lockOn(c: Candidate, name: String, confidence: Double, offline: Boolean) {
        targetName = name.ifBlank { c.label }
        lock.lock(c, SystemClock.uptimeMillis())
        mode = Mode.TARGET_LOCKED
        val src = if (offline) " Offline mode." else ""
        speaker.say("Found the $targetName, ${(confidence * 100).toInt()} percent sure.$src Say guide me when ready.")
    }

    fun close() {
        detectExec.shutdown()
        detectExec.awaitTermination(1, TimeUnit.SECONDS) // never close the model under a running detection
        grounder.close(); handTracker.close()
    }

    fun reset() {
        mode = Mode.IDLE; targetName = ""; pendingSpec = null; lock.clear()
        spatial.reset(); throttle.reset()
        speaker.say("Reset.")
    }
}
