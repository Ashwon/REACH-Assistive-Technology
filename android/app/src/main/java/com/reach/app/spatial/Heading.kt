package com.reach.app.spatial

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Which way the camera looks, horizontally, from Android's rotation matrix (device -> world, row-major 3x3, world
 * X east / Y north / Z up; from TYPE_GAME_ROTATION_VECTOR the "north" is arbitrary but fixed, which is all a relative
 * turn needs). The rear camera looks along device -Z. Degrees clockwise from world Y, or null when the camera points
 * nearly straight up or down (heading undefined). Works for an upright, chest-mounted phone, where the usual
 * azimuth from getOrientation() is degenerate.
 */
fun cameraHeadingDeg(r: FloatArray): Float? {
    val fx = -r[2]; val fy = -r[5]
    if (hypot(fx, fy) < 0.3f) return null
    return Math.toDegrees(atan2(fx, fy).toDouble()).toFloat().let { if (it < 0f) it + 360f else it }
}

/** Signed shortest turn from [from] to [to], in (-180, 180]. Positive = clockwise (to the right). */
fun angleDiff(to: Float, from: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

/**
 * Notices a substantial turn away from where the target was, and the return. Pure, JVM-tested.
 *
 * The anchor is the bearing of the target the last time it was seen (camera heading + the box's offset from the image
 * centre). Headings are low-pass filtered as a unit vector (no wrap-around jumps). AWAY needs the filtered heading more
 * than [turnDeg] from the anchor for [holdMs]; back to ALIGNED needs it within [returnDeg] for [stableMs] (hysteresis),
 * so swaying, reaching and sensor noise do not count as turning. Without samples or an anchor the state is UNKNOWN.
 *
 * Device rotation is body rotation only when the phone is chest-mounted; the caller words it accordingly.
 */
class TurnMonitor(
    private val turnDeg: Float = 60f,
    private val returnDeg: Float = 25f,
    private val holdMs: Long = 700,
    private val stableMs: Long = 500,
    private val alpha: Float = 0.2f,      // per sample (~50 Hz): ~0.25 s time constant
    private val staleMs: Long = 1000      // no sample this long: the sensor stopped, heading unknown
) {
    enum class State { UNKNOWN, ALIGNED, AWAY }

    var state = State.UNKNOWN; private set
    /** Filtered heading (deg), NaN until the first sample. */
    var heading = Float.NaN; private set
    /** Target bearing when it was last seen (deg), NaN when none. */
    var anchor = Float.NaN; private set
    private var seenHeading = Float.NaN
    private var cx = 0f; private var cy = 0f; private var hasSample = false
    private var sampleMs = 0L
    private var pendingSince = -1L

    /** Signed turn needed to face the anchor again (positive = turn right), or null when unknown. */
    fun toTarget(nowMs: Long): Float? = if (known(nowMs) && !anchor.isNaN()) angleDiff(anchor, heading) else null

    private fun known(nowMs: Long) = hasSample && nowMs - sampleMs <= staleMs

    fun onHeading(deg: Float, nowMs: Long) {
        val rad = Math.toRadians(deg.toDouble())
        if (!hasSample) { cx = cos(rad).toFloat(); cy = sin(rad).toFloat(); hasSample = true }
        else { cx += alpha * (cos(rad).toFloat() - cx); cy += alpha * (sin(rad).toFloat() - cy) }
        heading = Math.toDegrees(atan2(cy, cx).toDouble()).toFloat().let { if (it < 0f) it + 360f else it }
        sampleMs = nowMs
        step(nowMs)
    }

    /** The target is in view now at [offsetDeg] from the image centre (positive = right of centre). */
    fun seenTarget(offsetDeg: Float, nowMs: Long) {
        if (!known(nowMs)) return
        anchor = (heading + offsetDeg + 360f) % 360f; seenHeading = heading
        state = State.ALIGNED; pendingSince = -1L
    }

    fun clear() { anchor = Float.NaN; seenHeading = Float.NaN; state = State.UNKNOWN; pendingSince = -1L }

    /** How far the camera has turned since the target was last seen (deg), or null when unknown. A box seen before a
     *  rotation is in the wrong place in today's image even while it is still "fresh". */
    fun turnedSinceSeen(nowMs: Long): Float? = if (known(nowMs) && !seenHeading.isNaN()) angleDiff(heading, seenHeading) else null

    /** Re-evaluates the state at [nowMs] (also called per camera frame, so a stopped sensor turns UNKNOWN). */
    fun step(nowMs: Long): State {
        val d = toTarget(nowMs)
        if (d == null) { state = State.UNKNOWN; pendingSince = -1L; return state }
        val leaving = state != State.AWAY && abs(d) > turnDeg
        val returning = state == State.AWAY && abs(d) < returnDeg
        if (state == State.UNKNOWN) state = if (abs(d) > turnDeg) State.AWAY else State.ALIGNED
        else if (leaving || returning) {
            if (pendingSince < 0) pendingSince = nowMs
            if (nowMs - pendingSince >= if (leaving) holdMs else stableMs) { state = if (leaving) State.AWAY else State.ALIGNED; pendingSince = -1L }
        } else pendingSince = -1L
        return state
    }
}
