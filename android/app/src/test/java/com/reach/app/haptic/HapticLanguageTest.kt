package com.reach.app.haptic

import com.reach.app.haptic.HapticCue.*
import org.junit.Assert.*
import org.junit.Test

/** The Haptic Language v1.0 table, against the spec, as the payloads the firmware receives. */
class HapticLanguageTest {
    /** A pattern as "payload/gap" steps, the way the band receives it. */
    private fun steps(c: HapticCue) = c.pulses.map { "${it.payload}/${it.gapAfterMs}" }

    @Test fun payloadsUseTheExistingProtocol() {
        assertEquals("L,180,100", Pulse('L', 180, 100).payload)
        assertEquals("R,180,100", Pulse('R', 180, 100).payload)
        assertEquals("B,200,250", Pulse('B', 200, 250).payload)
        assertEquals("B,0,0", Pulse.STOP)
        assertEquals("L,150,300", Pulse.test('L').payload)
    }

    @Test fun malformedPulsesAreRejected() {
        for (bad in listOf({ Pulse('X', 100, 100) }, { Pulse('l', 100, 100) }, { Pulse('L', 0, 100) }, { Pulse('L', 256, 100) },
                           { Pulse('L', 100, 0) }, { Pulse('L', 100, Pulse.MAX_PULSE_MS + 1) }, { Pulse('L', 100, 100, -1) })) {
            try { bad(); fail("accepted a malformed pulse") } catch (e: IllegalArgumentException) {}
        }
    }

    @Test fun allNinePatternsMatchTheSpec() {
        assertEquals(listOf("L,180,100/100", "L,180,100/0"), steps(MOVE_LEFT))                       // H1
        assertEquals(listOf("R,180,100/100", "R,180,100/0"), steps(MOVE_RIGHT))                      // H2
        assertEquals(listOf("L,150,100/50", "R,150,100/50", "L,150,100/50", "R,150,100/0"), steps(FORWARD)) // H3
        for (n in listOf(NEAR_FAR, NEAR_CLOSE, NEAR_VERY_CLOSE)) assertEquals(listOf("B,150,80/0"), steps(n)) // H4
        assertEquals(listOf(800L, 400L, 200L), listOf(NEAR_FAR, NEAR_CLOSE, NEAR_VERY_CLOSE).map { it.repeatMs })
        assertEquals(listOf("B,200,250/120", "B,200,250/120", "B,200,250/0"), steps(REACHED))         // H5
        assertEquals(listOf("B,180,400/0"), steps(HOLD))                                              // H6
        assertEquals(3, TARGET_LOST.pulses.size); assertTrue(TARGET_LOST.pulses.all { it.motor == 'B' && it.ms == 70 }) // H7
        assertTrue(TARGET_LOST.pulses.dropLast(1).all { it.gapAfterMs >= 100 })                      // clear gaps
        assertEquals(listOf('L', 'R'), SEARCH.pulses.map { it.motor }); assertTrue(SEARCH.repeatMs >= 2000) // H8: slow, alternating
        assertEquals(2, REACQUIRED.pulses.size); assertTrue(REACQUIRED.pulses.all { it.motor == 'B' && it.ms == 100 }) // H9
        assertTrue(REACQUIRED.pulses[0].gapAfterMs >= 100)
    }

    @Test fun priorityAndRepetitionFollowTheSpec() {
        val order = listOf(HOLD, REACHED, TARGET_LOST, MOVE_LEFT, FORWARD, NEAR_FAR, SEARCH).map { it.priority }
        assertEquals(order.sorted(), order); assertEquals(order.distinct(), order)
        assertEquals(MOVE_LEFT.priority, MOVE_RIGHT.priority)
        assertEquals(setOf(HOLD, REACHED, TARGET_LOST, REACQUIRED), values().filter { it.oneShot }.toSet())
        // Every pattern is bounded, and a repeating one always leaves a pause before it repeats.
        for (c in values()) {
            assertTrue("$c", c.durationMs in 1..1500)
            if (!c.oneShot) assertTrue("$c", c.repeatMs > c.durationMs)
        }
    }

    @Test fun nearBandsAreCentralAndOrdered() {
        assertEquals(NEAR_FAR, HapticCue.near(0.14f))
        assertEquals(NEAR_CLOSE, HapticCue.near(0.10f))
        assertEquals(NEAR_CLOSE, HapticCue.near(0.06f))
        assertEquals(NEAR_VERY_CLOSE, HapticCue.near(0.05f))
        assertEquals(NEAR_VERY_CLOSE, HapticCue.near(-0.02f)) // fingertip slightly past the reference point
    }

    @Test fun practiceRunsAreBoundedAndTeachEveryCue() {
        assertEquals(9, HapticCue.taught.size)
        assertEquals(listOf(MOVE_LEFT, MOVE_RIGHT, FORWARD, NEAR_FAR, REACHED, HOLD, TARGET_LOST, SEARCH, REACQUIRED), HapticCue.taught)
        assertEquals(REACHED.pulses, REACHED.demo())                               // one-shots: once
        assertEquals(4, MOVE_LEFT.demo().size)                                     // repeating: twice, at the live interval
        assertEquals(MOVE_LEFT.repeatMs, MOVE_LEFT.demo().take(2).sumOf { it.ms + it.gapAfterMs }.toLong())
        assertEquals(listOf(720, 720, 720, 320, 320, 320, 120, 120, 120, 120), NEAR_FAR.demo().map { it.gapAfterMs }) // speeds up
        for (c in HapticCue.taught) assertTrue("$c", c.demo().sumOf { it.ms + it.gapAfterMs } <= 6000)
    }
}
