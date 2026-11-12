package com.reach.app.haptic

import com.reach.app.haptic.HapticCue.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** Arbitration, pre-emption, repetition and cancellation, driven by a fake clock the way the band's Handler drives it. */
class HapticPlayerTest {
    private var now = 0L
    private val out = ArrayList<Pair<Long, String>>()
    private val p = HapticPlayer { out += now to it }

    /** Runs the player's own timer up to [until]; every wake-up it asks for must be in the future. */
    private fun runTo(until: Long) {
        while (true) {
            val next = p.tick(now) ?: break
            assertTrue("timer did not advance: $next at $now", next > now)
            if (next > until) break
            now = next
        }
        now = until
    }

    private fun at(t: Long, cue: HapticCue?) { runTo(t); p.set(cue, now); runTo(now) }
    private fun sent(from: Long = 0, to: Long = Long.MAX_VALUE) = out.filter { it.first in from..to }.map { "${it.first}:${it.second}" }

    @Test fun aPatternPlaysWithItsGapsAndRepeatsAtItsInterval() {
        at(0, MOVE_LEFT); runTo(2500)
        assertEquals(listOf("0:L,180,100", "200:L,180,100", "1200:L,180,100", "1400:L,180,100", "2400:L,180,100"), sent())
    }

    @Test fun requestingTheSameCueEveryFrameNeverReplaysIt() {
        for (t in 0L..3000L step 75) at(t, MOVE_RIGHT)              // ReachCore reports its state on every camera frame
        assertEquals(listOf("0:R,180,100", "200:R,180,100", "1200:R,180,100", "1400:R,180,100", "2400:R,180,100", "2600:R,180,100"), sent())
    }

    @Test fun aHigherPriorityCuePreemptsAtOnceAndStopsTheRunningPulse() {
        at(0, MOVE_LEFT)
        at(50, HOLD)                                    // the first left pulse (0-100) is still running
        runTo(3000)
        assertEquals(listOf("0:L,180,100", "50:B,0,0", "250:B,180,400"), sent()) // no second left pulse; HOLD after a clear gap
    }

    @Test fun aLowerPriorityCueWaitsForARunningOneShotToFinish() {
        at(0, REACHED)
        at(100, SEARCH)
        runTo(2000)
        val s = sent()
        assertEquals(listOf("0:B,200,250", "370:B,200,250", "740:B,200,250"), s.take(3)) // all three success pulses
        assertEquals("1140:L,140,120", s[3])                                       // then searching, after a gap
    }

    @Test fun aRepeatingPatternThatIsNoLongerWantedStopsMidPattern() {
        at(0, FORWARD)
        at(160, null)                                   // the second pulse (150-250) is running
        runTo(3000)
        assertEquals(listOf("0:L,150,100", "150:R,150,100", "160:B,0,0"), sent())
    }

    @Test fun anEqualPriorityChangeOfDirectionPreempts() {
        at(0, MOVE_LEFT); at(120, MOVE_RIGHT); runTo(1000)
        assertEquals(listOf("0:L,180,100", "250:R,180,100", "450:R,180,100"), sent())
    }

    @Test fun aOneShotPlaysOncePerEntryAndAFlickeringStateCannotBuzzRepeatedly() {
        for (t in 0L..3000L step 75) at(t, HOLD)
        assertEquals(listOf("0:B,180,400"), sent())
        for (t in 3000L..6000L step 75) at(t, if ((t / 75) % 2 == 0L) HOLD else null) // in and out of a pause every frame
        val holds = out.filter { it.second == "B,180,400" }.map { it.first }
        assertTrue(holds.toString(), holds.zipWithNext().all { (a, b) -> b - a >= HapticPlayer.ONE_SHOT_COOLDOWN_MS })
        assertTrue(holds.size <= 3)
    }

    @Test fun togglingARepeatingCueCannotSpeedItUp() {
        at(0, MOVE_LEFT); at(300, null); at(400, MOVE_LEFT); runTo(1300)
        assertEquals(listOf("0:L,180,100", "200:L,180,100", "1200:L,180,100"), sent())
    }

    @Test fun stopCancelsEverythingAndALastOneShotIsNotReplayedAfterIt() {
        at(0, REACHED)
        runTo(300); p.stop(now); runTo(5000)
        assertEquals(listOf("0:B,200,250", "300:B,0,0"), sent())   // the 2nd and 3rd success pulses never play
        at(5000, REACHED); runTo(9000)                             // the same state reported again after the stop
        assertEquals(2, out.size)
        at(9000, MOVE_LEFT); runTo(9300)                           // a new state plays normally
        assertEquals("9000:L,180,100", sent(9000).first())
    }

    @Test fun stopClearsARepeatingCueSoTheNextFrameReestablishesIt() {
        at(0, MOVE_LEFT); runTo(500); p.stop(now)
        at(600, MOVE_LEFT); runTo(1300)                            // e.g. after a reconnect, the frame's state resumes
        assertEquals(listOf("0:L,180,100", "200:L,180,100", "500:B,0,0", "1200:L,180,100"), sent())
    }

    @Test fun aLateTimerShiftsTheRestOfThePatternInsteadOfSqueezingIt() {
        p.set(FORWARD, 0); p.tick(0)
        now = 300; p.tick(now)                                     // the second pulse was due at 150
        now = 360; p.tick(now)                                     // the third (due 300) is now due at 450
        now = 450; p.tick(now)
        assertEquals(listOf(0L, 300L, 450L), out.map { it.first }) // the 100 ms pulse + 50 ms gap is kept after the late one
    }

    @Test fun practicePlaysOnceAndIsNotCutByAnUnchangedLiveState() {
        p.practice(MOVE_RIGHT.demo(), now); p.set(null, now); runTo(5000)
        assertEquals(4, out.size); assertTrue(out.all { it.second == "R,180,100" })
    }

    @Test fun patternsNeverOverlapWhateverIsRequested() {
        val rnd = Random(42)
        val cues = listOf(null) + HapticCue.values().toList()
        var t = 0L
        repeat(2000) {
            t += rnd.nextInt(120)
            if (rnd.nextInt(50) == 0) { runTo(t); p.stop(now) } else at(t, cues[rnd.nextInt(cues.size)])
        }
        runTo(t + 5000)
        val pulses = out.filter { it.second != Pulse.STOP }.map { (at, pl) -> at to pl.split(",")[2].toLong() }
        for ((a, b) in pulses.zipWithNext()) assertTrue("pulse at ${b.first} overlaps the one at ${a.first}", b.first >= a.first + a.second)
        assertTrue(pulses.size > 100)
    }
}
