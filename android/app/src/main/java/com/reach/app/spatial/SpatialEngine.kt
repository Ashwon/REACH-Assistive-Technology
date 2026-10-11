package com.reach.app.spatial

import android.graphics.RectF

/**
 * V4 — Deterministic spatial engine. No ML, no network, no Claude.
 *
 * Frame coords: x grows RIGHT, y grows DOWN, both normalized 0..1.
 * Camera is chest-mounted facing away from user, so frame-right == user-right.
 *
 * Design:
 *  - Hysteresis: a direction must exceed ENTER threshold to fire and drop
 *    below EXIT threshold to clear -> no LEFT/RIGHT chatter at the boundary.
 *  - Alignment must hold for ALIGN_HOLD_MS before we announce it.
 *  - Depth is optional: with no depth we use hand-inside-target-box +
 *    hand-size growth as the "reach forward" proxy (Level 1 fallback).
 */
enum class Guidance { LEFT, RIGHT, UP, DOWN, FORWARD, ALIGNED, REACH, REACHED, UNCERTAIN, NONE }

data class SpatialInput(
    val targetBox: RectF?,        // normalized, null if target lost
    val handX: Float?, val handY: Float?, // index fingertip, null if no hand
    val handSize: Float?,         // normalized hand scale
    val targetDepthM: Float? = null, // Level 2/3 only
    val handDepthM: Float? = null,
    val timestampMs: Long
)

class SpatialEngine {

    // Tunables — tune live on the A15 with real cans.
    private val ENTER_X = 0.10f   // horizontal error to trigger LEFT/RIGHT
    private val EXIT_X = 0.05f
    private val ENTER_Y = 0.12f
    private val EXIT_Y = 0.06f
    private val ALIGN_HOLD_MS = 600L
    private val LOST_GRACE_MS = 800L

    private var activeX = 0        // -1 LEFT needed, +1 RIGHT needed, 0 ok
    private var activeY = 0
    private var alignedSince = -1L
    private var lastSeenTarget = 0L
    private var lastSeenHand = 0L
    private var baselineHandSize = -1f
    private var reachAnnounced = false

    fun reset() {
        activeX = 0; activeY = 0; alignedSince = -1L
        baselineHandSize = -1f; reachAnnounced = false
    }

    fun update(inp: SpatialInput): Guidance {
        val now = inp.timestampMs
        if (inp.targetBox != null) lastSeenTarget = now
        if (inp.handX != null) lastSeenHand = now

        val target = inp.targetBox
        if (target == null) {
            return if (now - lastSeenTarget > LOST_GRACE_MS) Guidance.UNCERTAIN else Guidance.NONE
        }
        val hx = inp.handX; val hy = inp.handY
        if (hx == null || hy == null) {
            return if (now - lastSeenHand > LOST_GRACE_MS) Guidance.UNCERTAIN else Guidance.NONE
        }

        val tx = target.centerX()
        val ty = target.centerY()
        val ex = tx - hx            // +ve => hand must move RIGHT
        val ey = ty - hy            // +ve => hand must move DOWN

        // Hysteresis X
        activeX = when {
            activeX == 0 && ex > ENTER_X -> 1
            activeX == 0 && ex < -ENTER_X -> -1
            activeX != 0 && kotlin.math.abs(ex) < EXIT_X -> 0
            else -> activeX
        }
        // Hysteresis Y
        activeY = when {
            activeY == 0 && ey > ENTER_Y -> 1
            activeY == 0 && ey < -ENTER_Y -> -1
            activeY != 0 && kotlin.math.abs(ey) < EXIT_Y -> 0
            else -> activeY
        }

        // Horizontal first: it's the dominant correction for tabletop reach.
        if (activeX == 1) { alignedSince = -1; return Guidance.RIGHT }
        if (activeX == -1) { alignedSince = -1; return Guidance.LEFT }
        if (activeY == 1) { alignedSince = -1; return Guidance.DOWN }
        if (activeY == -1) { alignedSince = -1; return Guidance.UP }

        // 2D aligned. Debounce before announcing.
        if (alignedSince < 0) alignedSince = now
        if (now - alignedSince < ALIGN_HOLD_MS) return Guidance.NONE

        // Depth path (Level 2/3) if both depths are credible.
        val td = inp.targetDepthM; val hd = inp.handDepthM
        if (td != null && hd != null && td > 0.1f && hd > 0.1f) {
            val gap = td - hd
            return when {
                gap > 0.35f -> Guidance.FORWARD
                gap > 0.12f -> Guidance.REACH
                else -> markReached()
            }
        }

        // Level 1 proxy: fingertip inside target box + hand grew vs baseline.
        val hs = inp.handSize ?: return Guidance.ALIGNED
        if (baselineHandSize < 0) { baselineHandSize = hs; return Guidance.ALIGNED }
        val inside = target.contains(hx, hy)
        val grew = hs > baselineHandSize * 1.25f
        return when {
            inside && grew -> markReached()
            else -> Guidance.REACH   // "Reach forward slowly."
        }
    }

    private fun markReached(): Guidance {
        if (reachAnnounced) return Guidance.NONE
        reachAnnounced = true
        return Guidance.REACHED
    }
}

/**
 * Rate limiter for spoken guidance: never talk over yourself,
 * repeat the same cue at most every repeatMs, always announce changes
 * after minGapMs.
 */
class GuidanceThrottle(
    private val minGapMs: Long = 1100,
    private val repeatMs: Long = 2600
) {
    private var lastCue: Guidance = Guidance.NONE
    private var lastSpokenAt = 0L

    fun filter(cue: Guidance, now: Long): Guidance? {
        if (cue == Guidance.NONE) return null
        val gap = now - lastSpokenAt
        val changed = cue != lastCue
        if ((changed && gap >= minGapMs) || (!changed && gap >= repeatMs)) {
            lastCue = cue; lastSpokenAt = now
            return cue
        }
        return null
    }

    fun reset() { lastCue = Guidance.NONE; lastSpokenAt = 0L }
}
