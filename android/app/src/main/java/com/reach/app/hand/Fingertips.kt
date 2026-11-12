package com.reach.app.hand

/** Pure Kotlin (no MediaPipe/android types) so extraction is unit-testable on the JVM. */
enum class Finger(val landmark: Int) { THUMB(4), INDEX(8), MIDDLE(12), RING(16), LITTLE(20) }

/** OPEN: >= 3 of index..little extended. FIST: >= 3 curled. Used only for the deliberate hand-confirmation gesture. */
enum class Pose { OPEN, FIST, OTHER }

/** One MediaPipe landmark. visibility/presence are null when the model does not fill them in. */
data class Lm(val x: Float, val y: Float, val z: Float = 0f, val visibility: Float? = null, val presence: Float? = null)

/**
 * x, y are normalized to the upright analysis frame (the same frame detector boxes use).
 * MediaPipe extrapolates landmarks it cannot see, so they can fall outside 0..1.
 */
data class Tip(val finger: Finger, val x: Float, val y: Float, val z: Float, val visibility: Float?, val presence: Float?) {
    val inFrame get() = x in 0f..1f && y in 0f..1f
}

data class HandResult(
    val present: Boolean,
    val tips: List<Tip> = emptyList(),  // thumb..little, in Finger order
    val handSize: Float = 0f,           // normalized wrist->middle_mcp distance, crude proximity proxy
    val score: Float = 0f,              // hand (handedness) confidence; the only per-hand score the model gives
    val palmX: Float = 0f,              // centre of wrist + the four knuckles: where this hand is, frame to frame
    val palmY: Float = 0f,
    val pose: Pose = Pose.OTHER,
    val landmarks: List<Lm> = emptyList(), // all 21, for recording / offline evaluation
    val handedness: String? = null,        // MediaPipe's "Left"/"Right" (mirrored for a rear camera); a veto, never identity
    val world: List<Lm> = emptyList()      // MediaPipe world landmarks: metres, origin at the hand's centre, camera-aligned axes
) {
    fun tip(f: Finger) = tips.firstOrNull { it.finger == f }
    /** Guidance point. Index finger stays the default until other fingers are tested in use. */
    val index get() = tip(Finger.INDEX)

    // Size alone never decides whose hand this is (a bystander at a table is as big as the wearer's
    // outstretched hand): HandSelector does, by acquisition + continuity.
    companion object {
        val NONE = HandResult(present = false)
        private val PALM = intArrayOf(0, 5, 9, 13, 17) // wrist + index/middle/ring/little knuckles
        private val TIP_PIP = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18) // index..little: tip, middle joint

        fun fromLandmarks(lm: List<Lm>, score: Float, handedness: String? = null, world: List<Lm> = emptyList()): HandResult {
            if (lm.size < 21) return NONE // a full hand is 21 landmarks; anything else is not a hand
            val tips = Finger.values().map { f -> lm[f.landmark].let { Tip(f, it.x, it.y, it.z, it.visibility, it.presence) } }
            val size = kotlin.math.hypot(lm[0].x - lm[9].x, lm[0].y - lm[9].y) // wrist -> middle MCP
            val palm = PALM.map { lm[it] }
            return HandResult(true, tips, size, score, palm.map { it.x }.average().toFloat(), palm.map { it.y }.average().toFloat(),
                pose(lm), lm, handedness, world.takeIf { it.size >= 21 } ?: emptyList())
        }

        /** A curled finger's tip is nearer the wrist than its middle joint (ratio ~0.5 in a fist, ~1.4 straight). */
        private fun pose(lm: List<Lm>): Pose {
            val w = lm[0]
            fun d(p: Lm) = kotlin.math.sqrt((p.x - w.x) * (p.x - w.x) + (p.y - w.y) * (p.y - w.y) + (p.z - w.z) * (p.z - w.z))
            val r = TIP_PIP.map { (tip, pip) -> d(lm[tip]) / maxOf(d(lm[pip]), 1e-6f) }
            return when { r.count { it < 1.0f } >= 3 -> Pose.FIST; r.count { it > 1.2f } >= 3 -> Pose.OPEN; else -> Pose.OTHER }
        }
    }
}
