package com.reach.app.haptic

import org.junit.Assert.*
import org.junit.Test

/** The band link without Android: serialized writes, STOP, stale pulses, disconnect / reconnect and honest status. */
class BandSessionTest {
    private val writes = ArrayList<String>()
    private var accept = true
    private var lost = 0
    private val s = BandSession({ writes += it; accept }, onLost = { lost++ })

    private fun ready(now: Long = 0) { s.onReady(now); s.onWritten(true, now) } // connected; its first write (STOP) answered

    @Test fun nothingIsWrittenUntilTheLinkIsReady() {
        assertFalse(s.send("L,180,100", 0))
        s.status(BandState.CONNECTING); assertFalse(s.send("L,180,100", 0))
        assertEquals(emptyList<String>(), writes)
    }

    @Test fun theFirstWriteAfterConnectingIsStop() {
        s.onReady(0)
        assertEquals(listOf(Pulse.STOP), writes)
        assertEquals(BandState.READY, s.state)
    }

    @Test fun writesAreSerializedOneAtATime() {
        ready()
        s.send("L,180,100", 10); s.send("R,180,100", 20)
        assertEquals(listOf(Pulse.STOP, "L,180,100"), writes)       // R waits for L's write to complete
        s.onWritten(true, 30)
        assertEquals(listOf(Pulse.STOP, "L,180,100", "R,180,100"), writes)
        s.onWritten(true, 40)
        assertEquals(3, s.written)
    }

    @Test fun stopJumpsTheQueueAndDropsWhatWasQueued() {
        ready()
        s.send("L,180,100", 10); s.send("L,180,100", 11); s.send("R,180,100", 12)
        s.send(Pulse.STOP, 13)
        s.onWritten(true, 14)
        assertEquals(listOf(Pulse.STOP, "L,180,100", Pulse.STOP), writes)
        s.onWritten(true, 15)
        assertEquals("stop sent", s.motors)                        // written, not confirmed off
    }

    @Test fun aPulseThatWaitedTooLongIsDroppedNotPlayedLate() {
        ready()
        s.send("L,180,100", 0); s.send("R,180,100", 0)
        s.onWritten(true, BandSession.STALE_MS + 1)                // R queued at 0, its turn comes too late
        assertEquals(listOf(Pulse.STOP, "L,180,100"), writes)
        assertEquals(1, s.failed)
    }

    @Test fun aFailedWriteIsCountedAndTheQueueMovesOn() {
        ready()
        accept = false; s.send("L,180,100", 0)
        accept = true; s.send("R,180,100", 1)
        assertEquals(listOf(Pulse.STOP, "L,180,100", "R,180,100"), writes)
        assertEquals(1, s.failed)
    }

    @Test fun aWriteWithNoAnswerTimesOut() {
        ready()
        s.send("L,180,100", 0)
        assertFalse(s.writeTimedOut(BandSession.WRITE_TIMEOUT_MS))
        assertTrue(s.writeTimedOut(BandSession.WRITE_TIMEOUT_MS + 1))
    }

    @Test fun aDisconnectCancelsGuidanceDropsTheQueueAndReconnectsALimitedNumberOfTimes() {
        ready()
        s.send("L,180,100", 0); s.send("R,180,100", 0)
        assertEquals(BandSession.RECONNECT_DELAY_MS, s.onDisconnected(wanted = true, why = "link lost"))
        assertEquals(1, lost); assertEquals(BandState.CONNECTING, s.state); assertEquals("unknown", s.motors)
        assertFalse(s.send("L,180,100", 1))                        // nothing goes out while down
        s.onReady(2)                                                // reconnected: STOP first, nothing stale replayed
        assertEquals(listOf(Pulse.STOP, "L,180,100", Pulse.STOP), writes)
        s.onWritten(true, 3)
        assertEquals(3, writes.size)
        repeat(BandSession.MAX_RECONNECTS) { assertNotNull(s.onDisconnected(true, "again")) }
        assertNull(s.onDisconnected(true, "again"))                 // out of attempts: FAILED, with a retry for the user
        assertEquals(BandState.FAILED, s.state)
        s.resetAttempts(); assertNotNull(s.onDisconnected(true, "user retried"))
    }

    @Test fun aWantedDisconnectStaysDown() {
        ready()
        assertNull(s.onDisconnected(wanted = false, why = "disconnected"))
        assertEquals(BandState.DISCONNECTED, s.state); assertEquals(1, lost)
    }

    @Test fun cancellingOnLossNeverWritesToTheDeadLink() {
        lateinit var session: BandSession
        session = BandSession({ writes += it; true }, onLost = { session.send(Pulse.STOP, 9) }) // the player's stop on loss
        session.onReady(0); session.onWritten(true, 0)
        session.onDisconnected(true, "link lost")
        assertEquals(listOf(Pulse.STOP), writes)
    }

    @Test fun motorsAreOffOnlyWhenTheBandSaysSo() {
        ready()
        assertEquals("stop sent", s.motors)
        s.onNotify("OK B,0,0"); assertEquals("stop sent", s.motors)
        s.onNotify("OFF"); assertEquals("off (reported by band)", s.motors)
        s.send("L,180,100", 5); s.onWritten(true, 6)
        assertEquals("pulse sent", s.motors)
    }

    /** BandSession and HapticPlayer wired the way BleBand wires them. */
    @Test fun aLostLinkCancelsQueuedGuidanceAndAReconnectPlaysOnlyWhatIsTrueNow() {
        var now = 0L
        lateinit var player: HapticPlayer
        val link = BandSession({ writes += it; true }, onLost = { player.stop(now) })
        player = HapticPlayer { link.send(it, now) }
        fun tick(t: Long) { now = t; player.tick(t); if (writes.size > 0) link.onWritten(true, t) }
        link.onReady(0); link.onWritten(true, 0)
        player.set(HapticCue.MOVE_LEFT, 0); tick(0)
        now = 100; link.onDisconnected(true, "link lost")          // between the two left pulses
        tick(200); tick(1200)                                      // nothing is written while down, nothing queued for later
        assertEquals(listOf(Pulse.STOP, "L,180,100"), writes)
        now = 3000; link.onReady(now); link.onWritten(true, now)   // back: STOP first
        player.set(HapticCue.MOVE_RIGHT, now); tick(now)          // the current frame's state, not the old one
        assertEquals(listOf(Pulse.STOP, "L,180,100", Pulse.STOP, "R,180,100"), writes)
        player.set(HapticCue.REACHED, now); tick(3300); tick(3670); tick(4040)
        val before = writes.size
        now = 4100; link.onDisconnected(true, "link lost"); link.onReady(5000); link.onWritten(true, 5000)
        player.set(HapticCue.REACHED, 5000); tick(5000); tick(9000) // the same reached state after the reconnect
        assertEquals(listOf(Pulse.STOP), writes.drop(before))       // only the reconnect's STOP: success is not replayed
    }
}
