package com.reach.app

import android.graphics.Bitmap
import android.graphics.RectF
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

    @Volatile private var targetBeverage: String = ""
    @Volatile private var targetBox: RectF? = null
    @Volatile private var latestCandidates: List<Candidate> = emptyList()
    private var frameCount = 0

    /** Called from the camera thread for every frame. Must stay fast. */
    fun onFrame(frame: Bitmap, tsMs: Long) {
        if (!busy.compareAndSet(false, true)) return
        try {
            frameCount++
            // Ground every 3rd frame (~5 Hz at 15 fps) — detector is the slow part.
            if (frameCount % 3 == 0 || latestCandidates.isEmpty()) {
                latestCandidates = grounder.ground(frame, tsMs)
                if (mode == Mode.REACHING || mode == Mode.TARGET_LOCKED) {
                    // Re-associate target by beverage class (cheap tracking).
                    targetBox = latestCandidates.firstOrNull { it.beverage == targetBeverage }?.box
                        ?: targetBox // keep last box briefly; SpatialEngine handles loss
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

            val tid = latestCandidates.firstOrNull { it.beverage == targetBeverage }?.id ?: -1
            onUi(latestCandidates, tid, handPt, "mode=$mode target=$targetBeverage")
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
                        targetBeverage = r.target
                        targetBox = snapshot.firstOrNull { it.id == r.candidateId }?.box
                        mode = Mode.TARGET_LOCKED
                        val conf = (r.confidence * 100).toInt()
                        val src = if (r.fromClaude) "" else " Offline mode."
                        speaker.say("Found the ${r.target}, $conf percent sure.$src Say guide me when ready.")
                    }
                }
            }
        }
    }

    fun reset() {
        mode = Mode.IDLE; targetBeverage = ""; targetBox = null
        spatial.reset(); throttle.reset()
        speaker.say("Reset.")
    }
}
