package com.reach.app.spatial

import com.reach.app.grounding.Box
import com.reach.app.hand.HandResult
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Metric depth for guidance. Pure Kotlin (JVM-tested); ArCoreSource fills [DepthFrame] from ARCore.
 *
 * Coordinate chain, every step explicit:
 *  1. upright analysis frame, normalized (what detector boxes and hand landmarks use; x right, y down)
 *  2. -> sensor-oriented CPU image, normalized: undo the clockwise [DepthFrame.rotation] used to make it upright
 *  3. -> CPU image pixels: x [Intrinsics.w], y [Intrinsics.h]; unprojected with the CPU image intrinsics
 *  4. -> depth map pixels: [DepthFrame.toDepth], the CPU-image -> depth-map mapping ARCore reported for this frame
 * Camera space (metres): X right, Y down, Z forward along the optical axis, in the sensor's orientation. Distances
 * and Z do not depend on that in-plane orientation.
 */
data class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val w: Int, val h: Int)

/** Normalized point in the sensor-oriented image for a point in the upright frame rotated clockwise by [rotation]. */
fun uprightToSensor(ux: Float, uy: Float, rotation: Int): Pair<Float, Float> = when ((rotation % 360 + 360) % 360) {
    0 -> ux to uy
    90 -> uy to 1f - ux
    180 -> 1f - ux to 1f - uy
    270 -> 1f - uy to ux
    else -> throw IllegalArgumentException("rotation $rotation")
}

/** Inverse of [uprightToSensor]. */
fun sensorToUpright(sx: Float, sy: Float, rotation: Int): Pair<Float, Float> = when ((rotation % 360 + 360) % 360) {
    0 -> sx to sy
    90 -> 1f - sy to sx
    180 -> 1f - sx to 1f - sy
    270 -> sy to 1f - sx
    else -> throw IllegalArgumentException("rotation $rotation")
}

/**
 * (ax, bx, ay, by) with out = a * in + b per axis, from the (0,0) and (1,1) probes, if the (1,0), (0,1) and
 * centre probes agree within 1% (i.e. no rotation, flip-swap or non-linear crop between the two images).
 */
fun affineOrNull(probe: FloatArray, out: FloatArray): FloatArray? {
    val ax = (out[2] - out[0]) / (probe[2] - probe[0]); val bx = out[0] - ax * probe[0]
    val ay = (out[3] - out[1]) / (probe[3] - probe[1]); val by = out[1] - ay * probe[1]
    for (k in 0 until probe.size / 2) {
        if (abs(ax * probe[2 * k] + bx - out[2 * k]) > 0.01f || abs(ay * probe[2 * k + 1] + by - out[2 * k + 1]) > 0.01f) return null
    }
    return floatArrayOf(ax, bx, ay, by).takeIf { m -> m.all { it.isFinite() } }
}

/**
 * (ax, bx, ay, by) taking normalized coordinates of image [a] to normalized coordinates of image [b], when both are
 * views of the same lens: a pixel's ray ((u - cx) / fx, (v - cy) / fy) is the same in both. Used to cross-check
 * ARCore's CPU-image -> texture (depth map) transform.
 */
fun intrinsicsMap(a: Intrinsics, b: Intrinsics): FloatArray {
    val ax = a.w * b.fx / (a.fx * b.w); val bx = (b.cx - a.cx * b.fx / a.fx) / b.w
    val ay = a.h * b.fy / (a.fy * b.h); val by = (b.cy - a.cy * b.fy / a.fy) / b.h
    return floatArrayOf(ax, bx, ay, by)
}

/** Camera-space point (m) for CPU-image pixel (u, v) at depth z. */
fun unproject(i: Intrinsics, u: Float, v: Float, z: Float) = floatArrayOf((u - i.cx) / i.fx * z, (v - i.cy) / i.fy * z, z)

fun dist3(a: FloatArray, b: FloatArray): Float {
    val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * One ARCore depth map (uint16 millimetres, 0 = no data) and what is needed to read it at analysis-frame points.
 * [toDepth] = (ax, bx, ay, by): depthNx = ax * imageNx + bx, depthNy = ay * imageNy + by (normalized, sensor
 * orientation). ArCoreSource builds it from transformCoordinates2d and refuses frames where that mapping is not
 * axis-aligned scale + offset.
 */
class DepthFrame(
    val w: Int, val h: Int, val mm: ShortArray,
    val intrinsics: Intrinsics,  // of the CPU image the analysis bitmap was made from
    val rotation: Int,           // clockwise degrees applied to the CPU image to make it upright
    val toDepth: FloatArray,
    val frameNs: Long, val depthNs: Long
) {
    /** Depth may lag the camera image; older than this is not used. */
    val fresh get() = abs(frameNs - depthNs) <= MAX_SKEW_NS

    /** CPU-image pixel for an upright normalized point. */
    fun imagePx(ux: Float, uy: Float): Pair<Float, Float> {
        val (sx, sy) = uprightToSensor(ux, uy, rotation)
        return sx * intrinsics.w to sy * intrinsics.h
    }

    /** Depth-map pixel for an upright normalized point (may be outside the map). */
    fun depthPx(ux: Float, uy: Float): Pair<Float, Float> {
        val (sx, sy) = uprightToSensor(ux, uy, rotation)
        return (toDepth[0] * sx + toDepth[1]) * w to (toDepth[2] * sy + toDepth[3]) * h
    }

    /**
     * Median depth (m) over the upright normalized rectangle, or null when fewer than [MIN_VALID_FRACTION] of the
     * depth pixels in it (and at least [MIN_SAMPLES]) hold a value in DepthSample.MIN_M..MAX_M.
     */
    fun medianM(left: Float, top: Float, right: Float, bottom: Float): Float? {
        val (ax, ay) = depthPx(left, top); val (bx, by) = depthPx(right, bottom)
        val x0 = minOf(ax, bx).toInt().coerceAtLeast(0); val x1 = maxOf(ax, bx).toInt().coerceAtMost(w - 1)
        val y0 = minOf(ay, by).toInt().coerceAtLeast(0); val y1 = maxOf(ay, by).toInt().coerceAtMost(h - 1)
        if (x1 < x0 || y1 < y0) return null // entirely outside the depth map (e.g. cropped by the texture)
        val vals = ArrayList<Int>()
        for (y in y0..y1) for (x in x0..x1) {
            val v = mm[y * w + x].toInt() and 0xFFFF
            if (v >= MIN_MM && v <= MAX_MM) vals.add(v)
        }
        val total = (x1 - x0 + 1) * (y1 - y0 + 1)
        if (vals.size < MIN_SAMPLES || vals.size < MIN_VALID_FRACTION * total) return null
        vals.sort()
        return vals[vals.size / 2] / 1000f
    }

    /** Camera-space point (m) for an upright normalized point at depth z. */
    fun point(ux: Float, uy: Float, z: Float): FloatArray { val (u, v) = imagePx(ux, uy); return unproject(intrinsics, u, v, z) }

    companion object {
        const val MAX_SKEW_NS = 200_000_000L
        const val MIN_SAMPLES = 3
        const val MIN_VALID_FRACTION = 0.3f
        val MIN_MM = (DepthSample.MIN_M * 1000).toInt()
        val MAX_MM = (DepthSample.MAX_M * 1000).toInt()
    }
}

/**
 * ESTIMATE of the index fingertip's Z (m) from the hand's own geometry, not from the depth map: ARCore's
 * motion-stereo depth assumes a static scene, so it is unreliable on a moving, thin fingertip (it is logged at the
 * tip for comparison only). MediaPipe world landmarks give the hand's shape in metres (scale learned from typical
 * hands); the ratio of their spread to the image landmarks' spread (pixels) gives the palm's Z by weak perspective,
 * Z = f * spread_m / spread_px, then the tip's world z offset is added.
 * Limits: hand size varies between people (~±15% on Z); a tip that is occluded or out of frame is extrapolated by
 * MediaPipe, so the estimate is refused then. Returns null when it cannot be computed.
 */
fun handDepthM(hand: HandResult, frame: DepthFrame): Float? {
    val lm = hand.landmarks; val world = hand.world
    if (!hand.present || lm.size < 21 || world.size < 21) return null
    val tip = hand.index ?: return null
    if (!tip.inFrame || (tip.presence ?: 1f) < 0.5f || (tip.visibility ?: 1f) < 0.5f) return null
    val px = lm.map { frame.imagePx(it.x, it.y) }
    val mx = px.map { it.first }.average().toFloat(); val my = px.map { it.second }.average().toFloat()
    val wx = world.map { it.x }.average().toFloat(); val wy = world.map { it.y }.average().toFloat()
    val wz = world.map { it.z }.average().toFloat()
    var sp = 0f; var sw = 0f
    for (i in 0 until 21) {
        sp += (px[i].first - mx).let { it * it } + (px[i].second - my).let { it * it }
        sw += (world[i].x - wx).let { it * it } + (world[i].y - wy).let { it * it }
    }
    if (sp < MIN_HAND_SPREAD_PX * MIN_HAND_SPREAD_PX * 21 || sw <= 0f) return null // hand too small to measure
    val f = (frame.intrinsics.fx + frame.intrinsics.fy) / 2f
    val palmZ = f * sqrt(sw / sp)
    val z = palmZ + (world[8].z - wz)
    return z.takeIf { it in DepthSample.MIN_M..DepthSample.MAX_M }
}

/** RMS landmark spread below this many CPU-image pixels: too few pixels for a scale estimate. */
const val MIN_HAND_SPREAD_PX = 6f
/** Relative uncertainty of [handDepthM] from hand-size variation, for display and logs only. */
const val HAND_DEPTH_REL_ERR = 0.15f

/**
 * Target Z (m) at its reference point, the centre of the locked box: median depth over the central 30% of the box.
 * Measured only while no hand overlaps the box (the depth there would be the hand's), then held for at most
 * [HOLD_MS], because the target is still but the chest-mounted camera is not.
 */
class TargetDepth {
    private var z = Float.NaN
    private var atMs = -1L

    fun reset() { z = Float.NaN; atMs = -1L }

    fun update(box: Box, handsOverBox: Boolean, frame: DepthFrame, nowMs: Long): Float? {
        if (!handsOverBox) {
            val cx = box.centerX(); val cy = box.centerY()
            val hw = (box.right - box.left) * 0.15f; val hh = (box.bottom - box.top) * 0.15f
            frame.medianM(cx - hw, cy - hh, cx + hw, cy + hh)?.let { z = it; atMs = nowMs }
        }
        return current(nowMs)
    }

    fun current(nowMs: Long) = z.takeIf { atMs >= 0 && nowMs - atMs <= HOLD_MS }

    companion object { const val HOLD_MS = 5000L }
}

/** Whether any of [hands]' landmarks fall inside [box] (grown by 10%): the depth there may be the hand's. */
fun handsOver(box: Box, hands: List<HandResult>): Boolean {
    val gx = (box.right - box.left) * 0.1f; val gy = (box.bottom - box.top) * 0.1f
    return hands.any { h -> h.landmarks.any { it.x in box.left - gx..box.right + gx && it.y in box.top - gy..box.bottom + gy } }
}

/** 3D distance (m) from [hand]'s index fingertip (Z estimated, see [handDepthM]) to the target point (the box centre at
 *  [targetM]), both unprojected with the same intrinsics; null when the fingertip cannot be measured. */
fun tipToTargetM(frame: DepthFrame, box: Box, targetM: Float, hand: HandResult): Float? {
    val tip = hand.index ?: return null
    val z = handDepthM(hand, frame) ?: return null
    return dist3(frame.point(box.centerX(), box.centerY(), targetM), frame.point(tip.x, tip.y, z))
}

/** One frame's distance measurement with the reason it is (in)valid, for guidance, the screen and the log. */
data class DepthReading(val sample: DepthSample?, val targetM: Float?, val tipM: Float?, val arTipM: Float?, val reason: String)

/** Builds [DepthSample]s from depth frames for one guidance session. Not thread-safe: ReachCore's lock covers it. */
class DepthMeter {
    private val target = TargetDepth()
    fun reset() = target.reset()

    fun measure(frame: DepthFrame, box: Box?, hand: HandResult, allHands: List<HandResult>, nowMs: Long): DepthReading {
        if (!frame.fresh) return DepthReading(null, null, null, null, "depth stale")
        if (box == null) return DepthReading(null, target.current(nowMs), null, null, "no target box")
        val targetM = target.update(box, handsOver(box, allHands), frame, nowMs)
        val tip = hand.index
        val tipM = if (tip != null) handDepthM(hand, frame) else null
        val arTipM = tip?.let { frame.medianM(it.x - 0.01f, it.y - 0.01f, it.x + 0.01f, it.y + 0.01f) } // diagnostics only
        val reason = when {
            targetM == null -> "no valid target depth"
            tip == null -> "no fingertip"
            tipM == null -> "fingertip depth not measurable"
            else -> "ok"
        }
        if (targetM == null || tip == null || tipM == null) return DepthReading(null, targetM, tipM, arTipM, reason)
        val d = dist3(frame.point(box.centerX(), box.centerY(), targetM), frame.point(tip.x, tip.y, tipM))
        return DepthReading(DepthSample(targetM, tipM, d), targetM, tipM, arTipM, reason)
    }
}
