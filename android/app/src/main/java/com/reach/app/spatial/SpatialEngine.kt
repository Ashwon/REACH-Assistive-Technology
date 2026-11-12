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
 * Alignment (lateral/vertical) and distance are separate measurements:
 * - Alignment is always 2D: the fingertip is "lined up" when it is over the middle of the target's box, i.e. on
 *   the camera's line of sight to the target reference point (the box centre). It works with or without depth.
 * - Distance needs a valid [DepthSample] (target and fingertip Z in one camera frame, see Depth.kt). Only then,
 *   and only once lined up, the forward gap gives "Move closer" / "Almost there" / "Too far, pull back".
 *   Without valid depth, guidance stops at "Lined up. Reach forward slowly." and no distance is invented.
 * "Reached" from depth additionally needs [depthReachedValidated]: the fingertip Z is an estimate (hand size), so
 * until it has passed the tape-measure validation on the phone, contact only says "you should be at it" and the
 * user confirms with "I've got it".
 */
enum class Guidance {
    LEFT, RIGHT, UP, DOWN, LINED_UP_2D, CLOSER, NEAR, BACK, AT_TARGET, STOP, REACHED, HAND_COVERS_TARGET, HAND_LOST,
    HAND_SHOW, HAND_TOO_FAR, HAND_FIST, HAND_AMBIGUOUS, HAND_SAME, // enrolling one of the user's hands (HandSelector)
    HAND_UNCERTAIN, // another hand is too close to the guided one: paused
    TURNED_AWAY,    // the camera (the user, when chest-mounted) turned away from where the target was
    IDENTITY_CHECK, // a registered target is on a new track: paused until its photo matches again
    TARGET_LOST, NONE;

    /** Cues that move the hand. Everything else is a status line and is repeated less often. */
    val directional get() = this == LEFT || this == RIGHT || this == UP || this == DOWN || this == CLOSER ||
        this == NEAR || this == BACK
}

/** The words for a cue, shared by the voice and the screen so both always say the same thing. [hand]: "right hand". */
fun Guidance.phrase(target: String, hand: String = "hand"): String? = when (this) {
    Guidance.LEFT -> "Move left."
    Guidance.RIGHT -> "Move right."
    Guidance.UP -> "Move up."
    Guidance.DOWN -> "Move down."
    Guidance.LINED_UP_2D -> "Lined up. Reach forward slowly."
    Guidance.CLOSER -> "Move closer."
    Guidance.NEAR -> "Almost there. Slowly."
    Guidance.BACK -> "Too far. Pull back a little."
    Guidance.AT_TARGET -> "You should be at the $target. Feel for it, and tap I've got it."
    Guidance.STOP -> "Stop."
    Guidance.REACHED -> "${target.replaceFirstChar { it.uppercase() }} reached."
    // 2D cannot tell covering from touching, so the user says when they have it (no depth in this pipeline).
    Guidance.HAND_COVERS_TARGET -> "Your hand is covering the $target. If you're touching it, tap I've got it."
    Guidance.HAND_LOST -> "I can't see your $hand."
    Guidance.HAND_SHOW -> "Raise your $hand in front of the camera."
    Guidance.HAND_TOO_FAR -> "Bring your $hand closer to the camera."
    Guidance.HAND_FIST -> "Now close that hand into a fist."
    Guidance.HAND_AMBIGUOUS -> "I saw more than one hand close. Only your $hand: open it, then close it into a fist."
    Guidance.HAND_SAME -> "That looks like your other hand. Raise your $hand, then close it into a fist."
    Guidance.HAND_UNCERTAIN -> "Another hand is close to your $hand. Paused."
    Guidance.TURNED_AWAY -> "You may have turned away from the $target. Turn back toward your previous direction."
    Guidance.TARGET_LOST -> "I can't see the $target. Hold still."
    Guidance.IDENTITY_CHECK -> "Checking this is your $target. Hold still."
    Guidance.NONE -> null
}

/**
 * Z (m, along the camera axis) of the target reference point and of the index fingertip, in one camera frame,
 * plus their 3D distance (NaN when unknown). tipM is an estimate (see Depth.kt handDepthM).
 */
data class DepthSample(val targetM: Float, val tipM: Float, val dist3dM: Float = Float.NaN) {
    val valid get() = targetM.isFinite() && tipM.isFinite() && targetM in MIN_M..MAX_M && tipM in MIN_M..MAX_M

    companion object { const val MIN_M = 0.15f; const val MAX_M = 3f }
}

data class SpatialInput(
    val target: Box?,                 // locked target, null once it is lost / stale
    val tipX: Float?, val tipY: Float?, // index fingertip, null if no hand
    val depth: DepthSample? = null,
    val timestampMs: Long,
    val occluded: Boolean = false     // the target is not detected while a hand is over its last box
)

class SpatialEngine(private val depthReachedValidated: Boolean = DEPTH_REACHED_VALIDATED) {

    companion object {
        /** Flip only after the fingertip-depth estimate passes the tape-measure check in the README. */
        const val DEPTH_REACHED_VALIDATED = false
        const val ALPHA = 0.5f            // EMA weight of the newest frame; at ~13 fps settles in ~4 frames
        const val CONFIRM_MS = 300L       // a changed decision must hold this long before it is reported
        const val HAND_GRACE_MS = 500L    // hand dropout tolerated before "I can't see your hand"
        const val FIRST_HAND_MS = 4000L   // after "guide me", time to bring the hand into view
        // Forward gap zones (target Z − fingertip Z), with ZONE_MARGIN hysteresis. Starting values: the fingertip Z is a
        // hand-size estimate (~±15%), so contact is a band, not a touch. Calibrate on the phone.
        const val NEAR_M = 0.15f          // gap > NEAR_M: "Move closer"
        const val CONTACT_M = 0.05f       // |gap| <= CONTACT_M: at the target
        const val BEYOND_M = -0.08f       // gap < BEYOND_M: fingertip past the target, "pull back"
        const val ZONE_MARGIN = 0.03f
        const val CONTACT_3D_M = 0.10f    // and the 3D distance (incl. lateral) must be this small for "reached"
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
    /** This frame: a fingertip and the target were both measured and the fingertip is lined up with it. */
    var aligned = false; private set
    /** Smoothed forward gap (target Z - fingertip Z, m), only while valid depth has been measured without a break. */
    val gapM get() = gap.takeIf { gapValid }
    private var smoothed = false
    private var activeX = 0; private var activeY = 0
    private var gap = 0f; private var gapValid = false
    private var zone = Zone.FAR
    private var contactSince = -1L
    private var pending = Guidance.NONE; private var pendingSince = 0L
    private var current = Guidance.NONE

    fun reset() {
        startedAt = -1L; lastHandMs = -1L; lastTarget = null
        ex = 0f; ey = 0f; smoothed = false; activeX = 0; activeY = 0; aligned = false
        gapValid = false; zone = Zone.FAR; contactSince = -1L
        pending = Guidance.NONE; pendingSince = 0L; current = Guidance.NONE
    }

    fun update(inp: SpatialInput): Guidance {
        val now = inp.timestampMs
        if (startedAt < 0) startedAt = now
        val raw = decide(inp, now)
        // No fingertip this frame: a held direction must not outlive the hand it was measured from.
        if ((inp.tipX == null || inp.tipY == null) && current.directional) current = Guidance.NONE
        if (raw != pending) { pending = raw; pendingSince = now }
        if (now - pendingSince >= CONFIRM_MS) current = pending // one-frame glitches never reach the speaker
        return current
    }

    private fun decide(inp: SpatialInput, now: Long): Guidance {
        val tx = inp.tipX; val ty = inp.tipY
        val hand = tx != null && ty != null
        aligned = false
        if (hand) {
            if (lastHandMs < 0 || now - lastHandMs > HAND_GRACE_MS) smoothed = false // hand is back: don't blend old positions
            lastHandMs = now
        }

        val box = inp.target
        if (box == null) {
            val last = lastTarget ?: return Guidance.NONE
            // Target gone while a hand is over where it was: the hand is in front of it.
            return if (inp.occluded || (hand && last.contains(tx!!, ty!!))) Guidance.HAND_COVERS_TARGET else Guidance.TARGET_LOST
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
        aligned = dir == null
        val d = inp.depth
        if (dir != null || d == null || !d.valid) {
            if (d == null || !d.valid) gapValid = false
            contactSince = -1L // contact needs alignment and valid depth without a break
            return dir ?: Guidance.LINED_UP_2D
        }

        val g = d.targetM - d.tipM // > 0: fingertip is still in front of (closer to the camera than) the target
        gap = if (gapValid) gap + ALPHA * (g - gap) else g
        if (!gapValid) zone = Zone.of(gap)
        gapValid = true
        if (!zone.holds(gap)) zone = Zone.of(gap)
        if (zone != Zone.CONTACT) contactSince = -1L
        return when (zone) {
            Zone.FAR -> Guidance.CLOSER
            Zone.NEAR -> Guidance.NEAR
            Zone.BEYOND -> Guidance.BACK
            Zone.CONTACT -> {
                if (!depthReachedValidated) return Guidance.AT_TARGET // estimate only: the user confirms
                if (!(d.dist3dM <= CONTACT_3D_M)) { contactSince = -1L; return Guidance.NEAR } // NaN fails too
                if (contactSince < 0) contactSince = now
                if (now - contactSince >= REACH_HOLD_MS) Guidance.REACHED else Guidance.STOP
            }
        }
    }

    /** Forward-gap zones; a zone is kept while the gap stays within ZONE_MARGIN of its range. */
    private enum class Zone(val lo: Float, val hi: Float) {
        BEYOND(Float.NEGATIVE_INFINITY, BEYOND_M), CONTACT(BEYOND_M, CONTACT_M), NEAR(CONTACT_M, NEAR_M),
        FAR(NEAR_M, Float.POSITIVE_INFINITY);
        fun holds(g: Float) = g >= lo - ZONE_MARGIN && g <= hi + ZONE_MARGIN
        companion object { fun of(g: Float) = values().first { g <= it.hi } }
    }
}

/**
 * Rate limiter for spoken guidance: a changed cue at most every minGapMs, the same directional cue repeated
 * at most every repeatMs, a status line ("Lined up", "I can't see your hand"...) every statusRepeatMs.
 * STOP and REACHED are urgent and skip the gap. quiet() lets an announcement finish.
 */
class GuidanceThrottle(
    private val minGapMs: Long = 1100,
    private val repeatMs: Long = 2600,
    private val statusRepeatMs: Long = 6000
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
        if ((changed && (urgent || gap >= minGapMs)) || (!changed && gap >= if (cue.directional) repeatMs else statusRepeatMs)) {
            lastCue = cue; lastSpokenAt = now
            return cue
        }
        return null
    }

    fun quiet(untilMs: Long) { quietUntil = untilMs }

    /** Records [cue] as spoken now (it was said directly), so it is not repeated at once. */
    fun mark(cue: Guidance, now: Long) { lastCue = cue; lastSpokenAt = now }

    fun reset() { lastCue = Guidance.NONE; lastSpokenAt = 0L; quietUntil = 0L }
}
