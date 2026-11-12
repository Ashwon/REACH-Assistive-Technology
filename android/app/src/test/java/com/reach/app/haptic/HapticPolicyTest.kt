package com.reach.app.haptic

import com.reach.app.haptic.HapticCommand.*
import org.junit.Assert.*
import org.junit.Test

class HapticPolicyTest {
    private val p = HapticPolicy()
    private var t = 0L
    /** Feeds [ms] of frames (75 ms apart) and returns every command emitted. */
    private fun feed(ms: Long, l: Float?, r: Float?): List<HapticCommand> {
        val out = ArrayList<HapticCommand>(); val end = t + ms
        while (t < end) { p.compare(l, r, t)?.let(out::add); t += 75 }
        return out
    }

    @Test fun aClearlyCloserHandIsSignalledAfterAShortHoldAndOnlyOnce() {
        assertEquals(listOf(EQUAL_OR_UNCERTAIN), feed(150, null, null)) // nothing measured yet
        assertEquals(emptyList<HapticCommand>(), feed(225, 0.20f, 0.50f)) // held 300 ms before it is believed
        assertEquals(listOf(LEFT_HAND_CLOSER), feed(1000, 0.20f, 0.50f))   // ...then sent once, not every frame
        assertEquals(LEFT_HAND_CLOSER, p.current)
    }

    @Test fun nearlyEqualDistancesAreUncertain() {
        assertEquals(listOf(EQUAL_OR_UNCERTAIN), feed(2000, 0.30f, 0.33f)) // 3 cm: inside the 5 cm margin
        assertEquals(emptyList<HapticCommand>(), feed(2000, 0.60f, 0.655f)) // 5.5 cm at 66 cm: inside 10%
    }

    @Test fun rightHandCloserDrivesTheRightMotor() {
        feed(1000, 0.50f, 0.25f)
        assertEquals(RIGHT_HAND_CLOSER, p.current)
    }

    @Test fun hysteresisPreventsFlickerAroundTheMargin() {
        feed(1000, 0.20f, 0.40f); assertEquals(LEFT_HAND_CLOSER, p.current)
        val sent = ArrayList<HapticCommand>()
        repeat(40) { i -> p.compare(0.30f, if (i % 2 == 0) 0.335f else 0.345f, t)?.let(sent::add); t += 75 } // lead 3.5-4.5 cm
        assertEquals(emptyList<HapticCommand>(), sent); assertEquals(LEFT_HAND_CLOSER, p.current)
        feed(2000, 0.30f, 0.31f)                                         // lead below half the margin: drop it
        assertEquals(EQUAL_OR_UNCERTAIN, p.current)
    }

    @Test fun anUnseenOrInvalidHandIsNeverFarther() {
        feed(1000, 0.20f, 0.50f); assertEquals(LEFT_HAND_CLOSER, p.current)
        assertEquals(listOf(EQUAL_OR_UNCERTAIN), feed(75, 0.20f, null))  // right hand not measured this frame
        feed(1000, 0.20f, 0.50f); assertEquals(LEFT_HAND_CLOSER, p.current)
        for (bad in listOf(Float.NaN, -0.1f, 0f, Float.POSITIVE_INFINITY)) {
            feed(75, 0.20f, bad); assertEquals("$bad", EQUAL_OR_UNCERTAIN, p.current); feed(1000, 0.20f, 0.50f)
        }
    }

    @Test fun safetyCommandsAreSentOnceEach() {
        feed(1000, 0.20f, 0.50f)
        assertEquals(STOP, p.force(STOP)); assertNull(p.force(STOP))
        assertEquals(REACHED, p.force(REACHED)); assertNull(p.force(REACHED))
        assertEquals(TRACKING_LOST, p.force(TRACKING_LOST)); assertNull(p.force(TRACKING_LOST))
        assertEquals(emptyList<HapticCommand>(), feed(150, 0.20f, 0.50f)) // the old comparison is not resumed blindly
    }
}
