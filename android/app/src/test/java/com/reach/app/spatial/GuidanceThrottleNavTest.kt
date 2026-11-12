package com.reach.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceThrottleNavTest {

    @Test
    fun linedUpIsNotSuppressedByPerStatusCooldownWhenReenteringAlignment() {
        val throttle = GuidanceThrottle(
            minGapMs = 850L,
            repeatMs = 2400L,
            statusRepeatMs = 4500L,
            perStatusCooldownMs = 8500L
        )
        assertEquals(Guidance.LEFT, throttle.filter(Guidance.LEFT, 1000L))
        assertEquals(Guidance.LINED_UP_2D, throttle.filter(Guidance.LINED_UP_2D, 2000L))
        // Drift slightly left and re-align 2.5s later (< 8.5s perStatusCooldownMs) -> MUST speak LINED_UP_2D again!
        assertEquals(Guidance.LEFT, throttle.filter(Guidance.LEFT, 3200L))
        assertEquals(Guidance.LINED_UP_2D, throttle.filter(Guidance.LINED_UP_2D, 4500L))

        // Routine non-navigation status cues like HAND_LOST still obey perStatusCooldownMs.
        assertEquals(Guidance.HAND_LOST, throttle.filter(Guidance.HAND_LOST, 6000L))
        assertEquals(Guidance.LEFT, throttle.filter(Guidance.LEFT, 7000L))
        assertNull(throttle.filter(Guidance.HAND_LOST, 9000L))

        assertTrue(Guidance.LINED_UP_2D.navigationCue)
        assertFalse(Guidance.LINED_UP_2D.directional)
        assertFalse(Guidance.HAND_LOST.navigationCue)
    }
}
