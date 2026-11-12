package com.reach.app.grounding

import kotlin.math.sqrt

/**
 * What a detection looks like, from the pixels of its own box (the crop the detector path already makes).
 * Pure Kotlin on ARGB ints so it is JVM-tested. Used for three things only:
 *  - re-finding the locked object after an occlusion (same class AND similar colours),
 *  - answering "the blue one" when the colours clearly differ between the options,
 *  - refusing dark, featureless boxes the detector names with a weak score.
 * It is a colour histogram, not recognition: lighting changes it, and it never names an object by itself.
 */
class Appearance(val hist: FloatArray, val luma: Float, val contrast: Float) {

    /** Histogram intersection, 0 (nothing in common) .. 1 (same colour mix). */
    fun similarity(o: Appearance): Float = hist.indices.sumOf { minOf(hist[it], o.hist[it]).toDouble() }.toFloat()

    fun share(color: String): Float = COLORS.indexOf(color).let { if (it < 0) 0f else hist[it] }

    /** Dark and flat: nothing in the box but shadow. Measured A15 junk boxes in dim frames looked like this. */
    val darkFlat get() = luma < DARK_LUMA && contrast < FLAT_CONTRAST

    companion object {
        val COLORS = listOf("red", "orange", "yellow", "green", "blue", "purple", "pink", "brown", "white", "gray", "black")
        const val DARK_LUMA = 40f
        const val FLAT_CONTRAST = 10f

        /** Histogram of [argb] pixels over [COLORS], plus mean luma (0..255) and its standard deviation. */
        fun of(argb: IntArray): Appearance? {
            if (argb.isEmpty()) return null
            val h = FloatArray(COLORS.size); var sum = 0f; var sum2 = 0f
            val hsv = FloatArray(3)
            for (p in argb) {
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                sum += y; sum2 += y * y
                rgbToHsv(r, g, b, hsv)
                h[bin(hsv[0], hsv[1], hsv[2])]++
            }
            val n = argb.size.toFloat()
            for (i in h.indices) h[i] /= n
            val mean = sum / n
            return Appearance(h, mean, sqrt(maxOf(0f, sum2 / n - mean * mean)))
        }

        private fun bin(hue: Float, s: Float, v: Float): Int = COLORS.indexOf(when {
            v < 0.2f -> "black"
            s < 0.2f -> if (v > 0.75f) "white" else "gray"
            hue < 15f || hue >= 345f -> "red"
            hue < 40f -> if (v < 0.6f) "brown" else "orange"
            hue < 70f -> "yellow"
            hue < 170f -> "green"
            hue < 260f -> "blue"
            hue < 300f -> "purple"
            else -> "pink"
        })

        /** Same conversion as android.graphics.Color.colorToHSV: hue 0..360, s and v 0..1. */
        fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
            val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
            val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf); val d = max - min
            out[2] = max; out[1] = if (max == 0f) 0f else d / max
            out[0] = when {
                d == 0f -> 0f
                max == rf -> 60f * (((gf - bf) / d) % 6f)
                max == gf -> 60f * ((bf - rf) / d + 2f)
                else -> 60f * ((rf - gf) / d + 4f)
            }.let { if (it < 0f) it + 360f else it }
        }
    }
}

/** Spoken colour words the user may use for an object ("the blue one"); synonyms map onto [Appearance.COLORS]. */
val COLOR_WORDS = Appearance.COLORS.associateWith { it } + mapOf("grey" to "gray", "silver" to "gray", "violet" to "purple", "dark" to "black")

/**
 * The one option that clearly has more of [color] than every other option (share >= [MIN_SHARE] and ahead of the
 * runner-up by [MIN_LEAD]), or null when the pixels do not support picking one. Options without pixels never win.
 */
fun pickByColor(options: List<Candidate>, color: String): Candidate? {
    val ranked = options.map { it to (it.look?.share(color) ?: 0f) }.sortedByDescending { it.second }
    val best = ranked.firstOrNull() ?: return null
    val next = ranked.getOrNull(1)?.second ?: 0f
    return best.first.takeIf { best.second >= MIN_SHARE && best.second - next >= MIN_LEAD && it.look != null }
}

const val MIN_SHARE = 0.2f
const val MIN_LEAD = 0.15f
