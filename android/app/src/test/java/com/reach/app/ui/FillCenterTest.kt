package com.reach.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview fills the screen (PreviewView fillCenter / ImageView centerCrop); the overlay must put analysis-frame
 * coordinates on the same pixels, for whatever frame size the current camera mode delivers.
 */
class FillCenterTest {
    private fun map(w: Float, h: Float, fw: Float, fh: Float, nx: Float, ny: Float): Pair<Float, Float> {
        val (sx, sy, ox, oy) = fillCenter(w, h, fw, fh)
        return Pair(ox + nx * sx, oy + ny * sy)
    }

    @Test fun tallPhoneCropsTheSidesOfAPortrait34Frame() {
        // 1080x2340 screen, 480x640 frame: scale 3.65625 -> 1755x2340, 337.5 px cut off each side.
        val (l, t) = map(1080f, 2340f, 480f, 640f, 0f, 0f)
        assertEquals(-337.5f, l, 1e-3f); assertEquals(0f, t, 1e-3f)
        assertEquals(Pair(540f, 1170f), map(1080f, 2340f, 480f, 640f, 0.5f, 0.5f)) // centre stays centre
        assertEquals(2340f, map(1080f, 2340f, 480f, 640f, 1f, 1f).second, 1e-3f)
    }

    @Test fun croppedOffScreenPointsFallOutsideTheViewVisibleOnesInside() {
        val visibleFrom = 337.5f / 1755f // normalized x where the screen starts
        assertTrue(map(1080f, 2340f, 480f, 640f, 0.05f, 0.5f).first < 0f)
        assertTrue(map(1080f, 2340f, 480f, 640f, 0.95f, 0.5f).first > 1080f)
        assertEquals(0f, map(1080f, 2340f, 480f, 640f, visibleFrom, 0.5f).first, 1e-3f)
    }

    @Test fun mappingFollowsTheFrameSizeWhenTheCameraChanges() {
        // Same normalized point, different frame aspect (ultrawide 3:4 vs. a 9:16 frame): each maps through its own scale.
        val a = map(1080f, 2340f, 480f, 640f, 0.25f, 0.25f)
        val b = map(1080f, 2340f, 720f, 1280f, 0.25f, 0.25f)
        assertTrue(a != b)
        // 720x1280 on 1080x2340: scale = max(1.5, 1.828) = 1.828 -> 1316.25 x 2340, offset -118.125.
        assertEquals(-118.125f + 0.25f * 1316.25f, b.first, 1e-2f)
    }

    @Test fun wideViewCropsTopAndBottom() {
        // 1000x500 view, 3:4 frame: scale = 1000/480 -> 1000 x 1333.3, offset y -416.7.
        val (sx, sy, ox, oy) = fillCenter(1000f, 500f, 480f, 640f)
        assertEquals(1000f, sx, 1e-3f); assertEquals(1333.333f, sy, 1e-2f); assertEquals(0f, ox, 1e-3f); assertEquals(-416.667f, oy, 1e-2f)
    }

    @Test fun exactAspectIsIdentityScale() = assertEquals(Pair(300f, 400f), map(300f, 400f, 480f, 640f, 1f, 1f))
}
