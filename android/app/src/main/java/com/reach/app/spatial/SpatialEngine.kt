package com.reach.app.spatial

import com.reach.app.grounding.Box
import kotlin.math.abs

/**
 * V4 — Deterministic spatial engine. No ML, no network, no Claude; O(1) per camera frame.
 *
 * Axes: normalized coordinates of the upright CameraX analysis frame, the one bitmap that both the
 * detector boxes and the hand landmarks are computed on. x: 0 = left edge .. 1 = right edge,
 * y: 0 = top .. 1 = bottom. The rear camera is chest-mounted facing forward, so image right is the
 * user's right and image up is up (while the phone is upright).
 *
 * Error = locked target box centre − index fingertip (landmark 8). ex > 0 → "Move right",
 * ey > 0 → "Move down". Horizontal is corrected first.
 *
 * 2D (the only mode this pipeline can feed today): the fingertip is "lined up" when it is over the
 * middle of the target's box, i.e. on the camera's line of sight to the target. That carries no distance,
 * so 2D guidance stops at "Lined up. Reach forward slowly." and never says "reached".
 * Depth: only when the caller passes metric depth for the target and the fingertip, both sampled from one
 * depth map registered to this same frame, can "Move closer", "Stop" and "reached" be produced.
 */
enum class Guidance { LEFT, RIGHT, UP, DOWN, LINED_UP_2D, CLOSER, STOP, REACHED, HAND_COVERS_TARGET, HAND_LOST, TARGET_LOST, NONE }

/** The words for a cue, shared by the voice and the screen so both always say the same thing. */
fun Guidance.phrase(target: String): String? = when (this) {
    Guidance.LEFT -> "Move left."
    Guidance.RIGHT -> "Move right."
    Guidance.UP -> "Move up."
    Guidance.DOWN -> "Move down."
    Guidance.LINED_UP_2D -> "Lined up. Reach forward slowly."
    Guidance.CLOSER -> "Move closer."
    Guidance.STOP -> "Stop."
    Guidance.REACHED -> "${target.replaceFirstChar { it.uppercase() }} reached."
    Guidance.HAND_COVERS_TARGET -> "Your hand is covering the $target. Reach forward slowly."
    Guidance.HAND_LOST -> "I can't see your hand."
    Guidance.TARGET_LOST -> "I can't see the $target. Hold still."
    Guidance.NONE -> null
}

/** Metric depth (m) of the target and the index fingertip from one depth map registered to the analysis frame. */
data class DepthSample(val targetM: Float, val tipM: Float) {
    val valid get() = targetM.isFinite() && tipM.isFinite() && targetM in MIN_M..MAX_M && tipM in MIN_M..MAX_M

    companion object { const val MIN_M = 0.15f; const val MAX_M = 3f }
}

data class SpatialInput(
    val target: Box?,                 // locked target, null once it is lost / stale
    val tipX: Float?, val tipY: Float?, // index fingertip, null if no hand
    val depth: DepthSample? = null,
    val timestampMs: Long
)

class SpatialEngine {

    companion object {
        const val ALPHA = 0.5f            // EMA weight of the newest frame; at ~13 fps settles in ~4 frames
        const val CONFIRM_MS = 300L       // a changed decision must hold this long before it is reported
        const val HAND_GRACE_MS = 500L    // hand dropout tolerated before "I can't see your hand"
        const val FIRST_HAND_MS = 4000L   // after "guide me", time to bring the hand into view
        const val CONTACT_ENTER_M = 0.03f // |target depth − fingertip depth| to count as touching
        const val CONTACT_EXIT_M = 0.06f
        const val REACH_HOLD_MS = 500L    // contact must hold this long (with valid depth throughout)

        // Box-relative thresholds (normalized frame units) so "lined up" means over the target itself.
        fun exitX(w: Float) = maxOf(0.03f, 0.35f * w)                // inside the central 70% of the width
        fun enterX(w: Float) = exitX(w) + maxOf(0.03f, 0.25f * w)
        fun exitY(h: Float) = maxOf(0.03f, 0.30f * h)                // inside the central 60% of the height
        fun enterY(h: Float) = exitY(h) + maxOf(0.04f, 0.15f * h)

        /** Beyond enter: correct towards that side. Inside exit: lined up. In between: keep what we had. */
        fun hysteresis(active: Int, e: Float, enter: Float, exit: Float) = when {
            e > enter -> 1
            e < -enter -> -1
            abs(e) < exit -> 0
            else -> active
        }
    }

    private var startedAt = -1L
    private var lastHandMs = -1L
    private var lastTarget: Box? = null
    var ex = 0f; private set   // smoothed errors, exposed for the debug log
    var ey = 0f; private set
    private var smoothed = false
    private var activeX = 0; private var activeY = 0
    private var gap = 0f; private var gapValid = false
    private var inContact = false; private var contactSince = -1L
    private var pending = Guidance.NONE; private var pendingSince = 0L
    private var current = Guidance.NONE

    fun reset() {
        startedAt = -1L; lastHandMs = -1L; lastTarget = null
        ex = 0f; ey = 0f; smoothed = false; activeX = 0; activeY = 0
        gapValid = false; inContact = false; contactSince = -1L
        pending = Guidance.NONE; pendingSince = 0L; current = Guidance.NONE
    }

    fun update(inp: SpatialInput): Guidance {
        val now = inp.timestampMs
        if (startedAt < 0) startedAt = now
        val raw = decide(inp, now)
        if (raw != pending) { pending = raw; pendingSince = now }
        if (now - pendingSince >= CONFIRM_MS) current = pending // one-frame glitches never reach the speaker
        return current
    }

    private fun decide(inp: SpatialInput, now: Long): Guidance {
        val tx = inp.tipX; val ty = inp.tipY
        val hand = tx != null && ty != null
        if (hand) {
            if (lastHandMs < 0 || now - lastHandMs > HAND_GRACE_MS) smoothed = false // hand is back: don't blend old positions
            lastHandMs = now
        }

        val box = inp.target
        if (box == null) {
            val last = lastTarget ?: return Guidance.NONE
            // Target gone while the visible fingertip is over where it was: the hand is in front of it.
            return if (hand && last.contains(tx!!, ty!!)) Guidance.HAND_COVERS_TARGET else Guidance.TARGET_LOST
        }
        lastTarget = box

        if (!hand) {
            val waited = if (lastHandMs >= 0) now - lastHandMs > HAND_GRACE_MS else now - startedAt > FIRST_HAND_MS
            return if (waited) Guidance.HAND_LOST else Guidance.NONE
        }

        val rx = box.centerX() - tx!!; val ry = box.centerY() - ty!!
        if (!smoothed) { ex = rx; ey = ry; smoothed = true } else { ex += ALPHA * (rx - ex); ey += ALPHA * (ry - ey) }
        val w = box.right - box.left; val h = box.bottom - box.top
        activeX = hysteresis(activeX, ex, enterX(w), exitX(w))
        activeY = hysteresis(activeY, ey, enterY(h), exitY(h))
        val dir = when { activeX > 0 -> Guidance.RIGHT; activeX < 0 -> Guidance.LEFT
                         activeY > 0 -> Guidance.DOWN; activeY < 0 -> Guidance.UP; else -> null }
        val d = inp.depth
        if (dir != null || d == null || !d.valid) {
            if (d == null || !d.valid) gapValid = false
            inContact = false; contactSince = -1L // contact needs alignment and valid depth without a break
            return dir ?: Guidance.LINED_UP_2D
        }

        val g = d.targetM - d.tipM // > 0: fingertip is still in front of (closer to the camera than) the target
        gap = if (gapValid) gap + ALPHA * (g - gap) else g
        gapValid = true
        inContact = abs(gap) <= if (inContact) CONTACT_EXIT_M else CONTACT_ENTER_M
        if (!inContact) { contactSince = -1L; return if (gap > 0) Guidance.CLOSER else Guidance.STOP }
        if (contactSince < 0) contactSince = now
        return if (now - contactSince >= REACH_HOLD_MS) Guidance.REACHED else Guidance.STOP
    }
}

/**
 * Rate limiter for spoken guidance: a changed cue at most every minGapMs, the same cue repeated at most
 * every repeatMs. STOP and REACHED are urgent and skip the gap. quiet() lets an announcement finish.
 */
class GuidanceThrottle(
    private val minGapMs: Long = 1100,
    private val repeatMs: Long = 2600
) {
    private var lastCue: Guidance = Guidance.NONE
    private var lastSpokenAt = 0L
    private var quietUntil = 0L

    fun filter(cue: Guidance, now: Long): Guidance? {
        if (cue == Guidance.NONE) return null
        val urgent = cue == Guidance.STOP || cue == Guidance.REACHED
        if (!urgent && now < quietUntil) return null
        val gap = now - lastSpokenAt
        val changed = cue != lastCue
        if ((changed && (urgent || gap >= minGapMs)) || (!changed && gap >= repeatMs)) {
            lastCue = cue; lastSpokenAt = now
            return cue
        }
        return null
    }

    fun quiet(untilMs: Long) { quietUntil = untilMs }

    fun reset() { lastCue = Guidance.NONE; lastSpokenAt = 0L; quietUntil = 0L }
}
