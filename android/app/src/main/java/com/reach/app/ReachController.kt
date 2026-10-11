package com.reach.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.reach.app.claude.ClaudeClient
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.ObjectGrounder
import com.reach.app.hand.HandTracker
import com.reach.app.spatial.*
import com.reach.app.speech.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * State machine:
 *   IDLE -> (utterance) -> INTERPRETING -> TARGET_LOCKED -> REACHING -> DONE
 * Claude is called exactly once per utterance (INTERPRETING). The per-frame
 * loop (onFrame) is 100% local: grounder + hand tracker + SpatialEngine.
 */
enum class Mode { IDLE, INTERPRETING, TARGET_LOCKED, REACHING, DONE }

private const val TARGET_HOLD_MS = 1500L

class ReachController(
    private val grounder: ObjectGrounder,
    private val handTracker: HandTracker,
    private val claude: ClaudeClient,
    private val speaker: Speaker,
    private val scope: CoroutineScope,
    private val onUi: (List<Candidate>, Int, Pair<Float, Float>?, String) -> Unit
) {
    @Volatile var mode = Mode.IDLE; private set
    private val spatial = SpatialEngine()
    private val throttle = GuidanceThrottle()
    private val busy = AtomicBoolean(false)

    // Blank when the color heuristic said "unknown": then the target is tracked by position only.
    @Volatile private var targetBeverage: String = ""
    @Volatile private var targetName: String = ""
    @Volatile private var targetBox: RectF? = null
    @Volatile private var targetId = -1
    @Volatile private var latestCandidates: List<Candidate> = emptyList()
    private var lastTargetSeenMs = 0L
    private var frameCount = 0
    private var logAt = 0L
    private var logFrames = 0

    /** Same beverage class, nearest to where the target last was; else nearest by position alone. */
    private fun matchTarget(cands: List<Candidate>): Candidate? {
        val prev = targetBox
        fun dist(c: Candidate) = prev?.let {
            Math.hypot((c.box.centerX() - it.centerX()).toDouble(), (c.box.centerY() - it.centerY()).toDouble())
        } ?: 0.0
        val pool = cands.filter { targetBeverage.isNotEmpty() && it.beverage == targetBeverage }
            .ifEmpty { if (prev == null) emptyList() else cands.filter { dist(it) < 0.25 } }
        return pool.minByOrNull { dist(it) }
    }

    /** Called from the camera thread for every frame. Must stay fast. */
    fun onFrame(frame: Bitmap, tsMs: Long) {
        if (!busy.compareAndSet(false, true)) return
        try {
            frameCount++
            // Ground every 3rd frame (~5 Hz at 15 fps) — detector is the slow part.
            if (frameCount % 3 == 0 || latestCandidates.isEmpty()) {
                latestCandidates = grounder.ground(frame, tsMs)
                if (mode == Mode.REACHING || mode == Mode.TARGET_LOCKED) {
                    val m = matchTarget(latestCandidates)
                    if (m != null) { targetBox = m.box; targetId = m.id; lastTargetSeenMs = tsMs }
                    // Hold the last box briefly (a reaching hand can occlude the can), then drop it
                    // so SpatialEngine reports UNCERTAIN.
                    else if (tsMs - lastTargetSeenMs > TARGET_HOLD_MS) { targetBox = null; targetId = -1 }
                }
            }

            val hand = handTracker.detect(frame, tsMs)
            val handPt = if (hand.present) Pair(hand.indexTipX, hand.indexTipY) else null

            if (mode == Mode.REACHING) {
                val cue = spatial.update(
                    SpatialInput(
                        targetBox = targetBox,
                        handX = handPt?.first, handY = handPt?.second,
                        handSize = if (hand.present) hand.handSize else null,
                        timestampMs = tsMs
                    )
                )
                throttle.filter(cue, tsMs)?.let { spoken ->
                    speaker.sayGuidance(spoken)
                    if (spoken == Guidance.REACHED) mode = Mode.DONE
                }
            }

            logFrames++
            if (tsMs - logAt >= 1000) {
                Log.i("REACH", "fps=$logFrames mode=$mode target=$targetName/$targetBeverage box=${targetBox != null} " +
                    "hand=${handPt?.let { "%.2f,%.2f size=%.2f".format(it.first, it.second, hand.handSize) }} " +
                    "cands=" + latestCandidates.joinToString { "${it.label}/${it.beverage}/${"%.2f".format(it.score)}@${"%.2f".format(it.box.centerX())},${"%.2f".format(it.box.centerY())}" })
                logAt = tsMs; logFrames = 0
            }
            onUi(latestCandidates, targetId, handPt, "mode=$mode target=$targetName")
        } finally {
            busy.set(false)
        }
    }

    /** Called once per recognized utterance. */
    fun onUtterance(text: String) {
        when {
            mode == Mode.TARGET_LOCKED && "guide" in text.lowercase() -> {
                spatial.reset(); throttle.reset()
                mode = Mode.REACHING
                speaker.say("Guiding. Move your hand into view.")
            }
            else -> {
                mode = Mode.INTERPRETING
                val snapshot = latestCandidates
                scope.launch {
                    val r = claude.interpret(text, snapshot)
                    if (r.needsClarification || r.candidateId < 0) {
                        speaker.say(r.question.ifBlank { "I'm not sure which object you mean." })
                        mode = Mode.IDLE
                    } else {
                        val chosen = snapshot.firstOrNull { it.id == r.candidateId }
                        targetName = r.target
                        targetBeverage = chosen?.beverage?.takeIf { it != "unknown" } ?: ""
                        targetBox = chosen?.box
                        targetId = chosen?.id ?: -1
                        lastTargetSeenMs = SystemClock.uptimeMillis()
                        mode = Mode.TARGET_LOCKED
                        val conf = (r.confidence * 100).toInt()
                        val src = if (r.fromClaude) "" else " Offline mode."
                        speaker.say("Found the ${r.target}, $conf percent sure.$src Say guide me when ready.")
                    }
                }
            }
        }
    }

    fun close() { grounder.close(); handTracker.close() }

    fun reset() {
        mode = Mode.IDLE; targetBeverage = ""; targetName = ""; targetBox = null; targetId = -1
        spatial.reset(); throttle.reset()
        speaker.say("Reset.")
    }
}
