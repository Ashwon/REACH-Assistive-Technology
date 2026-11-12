package com.reach.app.spatial

import com.reach.app.spatial.TurnMonitor.State.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class HeadingTest {
    @Test fun theRearCamerasHeadingForAnUprightPhone() {
        // Rotation matrices (device -> world, row-major) of an upright portrait phone whose rear camera faces...
        val north = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val east = floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)
        assertEquals(0f, cameraHeadingDeg(north)!!, 1e-4f)
        assertEquals(90f, cameraHeadingDeg(east)!!, 1e-4f)
        val flatOnTable = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f) // camera points at the floor
        assertNull(cameraHeadingDeg(flatOnTable))
    }

    @Test fun shortestSignedTurn() {
        assertEquals(20f, angleDiff(10f, 350f), 1e-4f)
        assertEquals(-20f, angleDiff(350f, 10f), 1e-4f)
        assertEquals(180f, angleDiff(180f, 0f), 1e-4f)
        assertEquals(-90f, angleDiff(0f, 90f), 1e-4f)
    }

    private var t = 0L
    private fun feed(m: TurnMonitor, ms: Long, deg: (Long) -> Float) {
        val end = t + ms
        while (t < end) { m.onHeading(deg(t), t); t += 20 } // ~50 Hz
    }

    @Test fun aSustainedTurnIsAwayAndTheReturnNeedsToSettle() {
        val m = TurnMonitor()
        feed(m, 500) { 350f }
        assertEquals(UNKNOWN, m.state)                    // no target seen yet: nothing to have turned from
        m.seenTarget(5f, t)                               // the target was seen 5° right of centre
        assertEquals(355f, m.anchor, 1e-3f); assertEquals(ALIGNED, m.state)
        feed(m, 2000) { 80f }                             // turned 85° clockwise
        assertEquals(AWAY, m.state); assertEquals(-85f, m.toTarget(t)!!, 1f) // turn back left
        feed(m, 300) { 0f }
        assertEquals(AWAY, m.state)                       // passing through is not settled yet
        feed(m, 800) { 0f }
        assertEquals(ALIGNED, m.state)
    }

    @Test fun swayingAndNoiseAndBriefSpikesAreNotATurn() {
        val m = TurnMonitor(); val rnd = Random(3)
        feed(m, 200) { 0f }; m.seenTarget(0f, t)
        val seen = HashSet<TurnMonitor.State>()
        val end = t + 20_000
        while (t < end) {
            val sway = 35f * kotlin.math.sin(t / 500.0).toFloat()            // torso sway while reaching
            val spike = if (t % 3000 < 200) 120f else 0f                      // a 200 ms magnetic/jolt spike
            m.onHeading(sway + spike + (rnd.nextFloat() - 0.5f) * 20f, t); seen += m.state; t += 20
        }
        assertEquals(setOf(ALIGNED), seen)
    }

    @Test fun aStoppedSensorMakesTheHeadingUnknown() {
        val m = TurnMonitor()
        feed(m, 200) { 0f }; m.seenTarget(0f, t)
        assertNotNull(m.toTarget(t))
        assertEquals(UNKNOWN, m.step(t + 1500)); assertNull(m.toTarget(t + 1500))
    }
}
