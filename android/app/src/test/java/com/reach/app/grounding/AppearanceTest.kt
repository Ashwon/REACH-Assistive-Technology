package com.reach.app.grounding

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    private fun px(rgb: Int, n: Int = 100) = IntArray(n) { 0xFF000000.toInt() or rgb }
    private fun bottle(id: Int, cx: Float, look: Appearance?) =
        Candidate(id, "bottle", "unknown", Box(cx - 0.05f, 0.4f, cx + 0.05f, 0.7f), 0.8f, null, look = look)

    @Test fun hsvMatchesTheAndroidConversion() {
        val out = FloatArray(3)
        Appearance.rgbToHsv(255, 0, 0, out); assertArrayEquals(floatArrayOf(0f, 1f, 1f), out, 1e-4f)
        Appearance.rgbToHsv(0, 0, 255, out); assertEquals(240f, out[0], 1e-3f)
        Appearance.rgbToHsv(255, 0, 128, out); assertEquals(329.9f, out[0], 0.1f)
    }

    @Test fun histogramSharesAndSimilarity() {
        val blue = Appearance.of(px(0x1E40D0))!!; val white = Appearance.of(px(0xF0F0F0))!!
        assertEquals(1f, blue.share("blue"), 1e-6f); assertEquals(1f, white.share("white"), 1e-6f)
        assertEquals(1f, blue.similarity(blue), 1e-6f); assertEquals(0f, blue.similarity(white), 1e-6f)
        assertNull(Appearance.of(IntArray(0)))
    }

    @Test fun theBlueOneIsPickedOnlyWhenThePixelsClearlySupportIt() {
        val blue = Appearance.of(px(0x1E40D0, 60) + px(0xF0F0F0, 40))!!   // 60% blue label
        val white = Appearance.of(px(0xF0F0F0, 90) + px(0x1E40D0, 10))!!  // 10% blue cap
        val a = bottle(1, 0.3f, blue); val b = bottle(2, 0.7f, white)
        assertEquals(1, pickByColor(listOf(a, b), "blue")!!.id)
        assertEquals(2, pickByColor(listOf(a, b), "white")!!.id)
        assertNull(pickByColor(listOf(a, b), "red"))                         // nobody is red: no guess
        val alsoBlue = bottle(3, 0.5f, Appearance.of(px(0x1E40D0, 55) + px(0xF0F0F0, 45))!!)
        assertNull(pickByColor(listOf(a, alsoBlue), "blue"))                 // too close to call
        assertNull(pickByColor(listOf(bottle(4, 0.3f, null), bottle(5, 0.7f, null)), "blue")) // no pixels, no claim
    }
}
