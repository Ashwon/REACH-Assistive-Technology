package com.reach.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The preview letterboxes (fitCenter); the overlay must put frame coordinates on the same pixels. */
class FitCenterTest {
    private fun map(w: Float, h: Float, nx: Float, ny: Float): Pair<Float, Float> {
        val (sx, sy, ox, oy) = fitCenter(w, h, 3f / 4f)
        return Pair(ox + nx * sx, oy + ny * sy)
    }

    @Test fun wideCardLetterboxesLeftAndRight() {
        // 1000x1000 card: 3:4 frame scaled to 750x1000, 125 px bars each side.
        assertEquals(Pair(125f, 0f), map(1000f, 1000f, 0f, 0f))
        assertEquals(Pair(875f, 1000f), map(1000f, 1000f, 1f, 1f))
        assertEquals(Pair(500f, 500f), map(1000f, 1000f, 0.5f, 0.5f))
    }

    @Test fun tallViewLetterboxesTopAndBottom() {
        // 300x600 view: frame scaled to 300x400, 100 px bars top and bottom.
        assertEquals(Pair(0f, 100f), map(300f, 600f, 0f, 0f))
        assertEquals(Pair(300f, 500f), map(300f, 600f, 1f, 1f))
    }

    @Test fun exactAspectIsIdentityScale() = assertEquals(Pair(300f, 400f), map(300f, 400f, 1f, 1f))
}
