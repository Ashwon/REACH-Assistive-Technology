package com.reach.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FillCenterTest {
    private fun map(w: Float, h: Float, nx: Float, ny: Float): Pair<Float, Float> {
        val (sx, sy, ox, oy) = fillCenter(w, h, 3f / 4f)
        return Pair(ox + nx * sx, oy + ny * sy)
    }

    @Test fun tallPhoneScreenCropsTheFrameSides() {
        // 1080x2340 view, 3:4 frame scaled to 1755x2340: 337.5 px cut off each side.
        assertEquals(Pair(540f, 1170f), map(1080f, 2340f, 0.5f, 0.5f))          // center stays center
        assertEquals(Pair(-337.5f, 0f), map(1080f, 2340f, 0f, 0f))               // frame corner is off-screen
        assertEquals(Pair(1080f, 2340f), map(1080f, 2340f, 0.8076923f, 1f).let { Pair(Math.round(it.first).toFloat(), it.second) })
    }

    @Test fun wideViewCropsTopAndBottom() {
        // 900x900 view: frame scaled to 900x1200, 150 px cut top and bottom.
        assertEquals(Pair(0f, -150f), map(900f, 900f, 0f, 0f))
        assertEquals(Pair(450f, 450f), map(900f, 900f, 0.5f, 0.5f))
    }

    @Test fun exactAspectIsIdentityScale() = assertEquals(Pair(300f, 400f), map(300f, 400f, 1f, 1f))
}
