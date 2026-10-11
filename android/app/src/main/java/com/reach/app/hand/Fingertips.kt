package com.reach.app.hand

/** Pure Kotlin (no MediaPipe/android types) so extraction is unit-testable on the JVM. */
enum class Finger(val landmark: Int) { THUMB(4), INDEX(8), MIDDLE(12), RING(16), LITTLE(20) }

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
    val score: Float = 0f               // hand (handedness) confidence; the only per-hand score the model gives
) {
    fun tip(f: Finger) = tips.firstOrNull { it.finger == f }
    /** Guidance point. Index finger stays the default until other fingers are tested in use. */
    val index get() = tip(Finger.INDEX)

    companion object {
        val NONE = HandResult(present = false)

        fun fromLandmarks(lm: List<Lm>, score: Float): HandResult {
            if (lm.size < 21) return NONE // a full hand is 21 landmarks; anything else is not a hand
            val tips = Finger.values().map { f -> lm[f.landmark].let { Tip(f, it.x, it.y, it.z, it.visibility, it.presence) } }
            val size = kotlin.math.hypot(lm[0].x - lm[9].x, lm[0].y - lm[9].y) // wrist -> middle MCP
            return HandResult(true, tips, size, score)
        }
    }
}
