package com.reach.app.spatial

import com.reach.app.grounding.Box
import com.reach.app.spatial.Guidance.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Frames every 75 ms (~13 fps, as measured on the A15). Bottle box: centre (0.50, 0.55), 0.10 x 0.30. */
class SpatialEngineTest {
    private val bottle = Box(0.45f, 0.40f, 0.55f, 0.70f)
    private var e = SpatialEngine()
    private var t = 0L

    /** Feeds frames for durMs and returns every reported cue. */
    private fun feed(durMs: Long, target: Box? = bottle, tip: Pair<Float, Float>? = 0.5f to 0.55f,
                     depth: DepthSample? = null): List<Guidance> {
        val out = ArrayList<Guidance>()
        val end = t + durMs
        while (t < end) { out += e.update(SpatialInput(target, tip?.first, tip?.second, depth, t)); t += 75 }
        return out
    }

    /** An engine allowed to report "reached" from depth (production waits for the on-phone validation). */
    private fun validated() { e = SpatialEngine(depthReachedValidated = true) }

    @Test fun thresholdsScaleWithTheBox() {
        assertEquals(0.035f, SpatialEngine.exitX(0.10f), 1e-6f); assertEquals(0.065f, SpatialEngine.enterX(0.10f), 1e-6f)
        assertEquals(0.03f, SpatialEngine.exitX(0.02f), 1e-6f)    // tiny/far box: floor, not an impossible target
        assertEquals(0.09f, SpatialEngine.exitY(0.30f), 1e-6f); assertEquals(0.135f, SpatialEngine.enterY(0.30f), 1e-6f)
    }

    @Test fun eachDirectionPointsFromTheFingertipToTheBottle() {
        assertEquals(RIGHT, feed(600, tip = 0.30f to 0.55f).last())
        e.reset(); assertEquals(LEFT, feed(600, tip = 0.70f to 0.55f).last())
        e.reset(); assertEquals(DOWN, feed(600, tip = 0.50f to 0.30f).last())   // image y grows downwards
        e.reset(); assertEquals(UP, feed(600, tip = 0.50f to 0.85f).last())
        e.reset(); assertEquals(RIGHT, feed(600, tip = 0.30f to 0.30f).last())  // horizontal is corrected first
    }

    @Test fun nothingIsSaidUntilADecisionHasHeldForTheConfirmTime() {
        val out = feed(600, tip = 0.30f to 0.55f)
        assertTrue(out.take(4).all { it == NONE })           // 0..225 ms
        assertEquals(RIGHT, out[4])                          // 300 ms
    }

    @Test fun hysteresisHoldsBetweenExitAndEnter() {
        feed(600, tip = 0.30f to 0.55f)
        assertEquals(RIGHT, feed(1000, tip = 0.45f to 0.55f).last())        // ex 0.05: inside the dead band, keep RIGHT
        assertEquals(LINED_UP_2D, feed(1000, tip = 0.48f to 0.55f).last())  // ex 0.02 < exit 0.035
        assertEquals(LINED_UP_2D, feed(1000, tip = 0.45f to 0.55f).last())  // back to 0.05: no chatter
        assertEquals(LEFT, feed(1000, tip = 0.60f to 0.55f).last())         // past enter on the other side
    }

    @Test fun noisyFingertipOverTheBottleNeverProducesADirection() {
        val rnd = Random(7)
        val out = ArrayList<Guidance>()
        repeat(200) { // 15 s of ±0.08 jitter in x and y, larger than the enter threshold
            out += feed(75, tip = (0.5f + (rnd.nextFloat() - 0.5f) * 0.16f) to (0.55f + (rnd.nextFloat() - 0.5f) * 0.16f))
        }
        assertTrue(out.toSet().toString(), out.all { it == NONE || it == LINED_UP_2D })
    }

    @Test fun aSingleFrameGlitchIsNotReported() {
        feed(600)
        val out = feed(75, tip = 0.05f to 0.55f) + feed(1000)
        assertTrue(out.toString(), out.all { it == LINED_UP_2D })
    }

    @Test fun twoDimensionalOverlapNeverMeansReached() {
        val out = feed(10_000, tip = 0.50f to 0.55f) // fingertip dead centre on the box for 10 s, no depth
        assertEquals(LINED_UP_2D, out.last())
        assertFalse(out.any { it == REACHED || it == STOP || it == CLOSER })
    }

    @Test fun invalidDepthIsIgnoredAndFallsBackTo2D() {
        for (d in listOf(DepthSample(Float.NaN, 0.5f), DepthSample(0.5f, 0f), DepthSample(5f, 4.99f),
                         DepthSample(0.5f, Float.POSITIVE_INFINITY), DepthSample(0.1f, 0.1f))) {
            e.reset()
            val out = feed(3000, depth = d)
            assertEquals("$d", LINED_UP_2D, out.last()); assertFalse("$d", REACHED in out)
        }
    }

    @Test fun depthGivesCloserThenNearThenStopThenReached() {
        validated()
        assertEquals(CLOSER, feed(600, depth = DepthSample(0.60f, 0.40f, 0.20f)).last())  // 20 cm short
        assertEquals(NEAR, feed(900, depth = DepthSample(0.60f, 0.50f, 0.10f)).last())    // 10 cm short
        val out = feed(2000, depth = DepthSample(0.60f, 0.59f, 0.01f))                     // 1 cm apart
        val stop = out.indexOf(STOP); val reached = out.indexOf(REACHED)
        assertTrue(out.toString(), stop in 1 until reached) // "Stop." (hold still) first, then "reached"
        assertTrue(75 * (reached - stop) >= SpatialEngine.REACH_HOLD_MS)
        assertEquals(REACHED, out.last())
        e.reset(); assertEquals(BACK, feed(600, depth = DepthSample(0.60f, 0.70f, 0.10f)).last()) // fingertip past it
    }

    @Test fun unvalidatedFingertipDepthNeverClaimsReachedOnlyThatTheUserShouldBeThere() {
        val out = feed(5000, depth = DepthSample(0.60f, 0.60f, 0.0f))
        assertEquals(AT_TARGET, out.last()); assertFalse(REACHED in out); assertFalse(STOP in out)
        assertFalse(SpatialEngine.DEPTH_REACHED_VALIDATED) // flipped only after the tape-measure check passes on the phone
    }

    @Test fun reachedNeedsAKnown3dDistanceToo() {
        validated()
        val out = feed(3000, depth = DepthSample(0.60f, 0.60f)) // Z gap closed, but the 3D distance is unknown (NaN)
        assertFalse(REACHED in out); assertEquals(NEAR, out.last())
        e.reset()
        assertFalse(REACHED in feed(3000, depth = DepthSample(0.60f, 0.60f, 0.25f))) // laterally 25 cm off in 3D
    }

    @Test fun distanceZonesHaveHysteresisSoNoiseAtABoundaryDoesNotFlipTheCue() {
        feed(600, depth = DepthSample(0.60f, 0.40f, 0.2f)) // FAR: Move closer
        val rnd = Random(3)
        val out = (0 until 60).flatMap { // gap 0.14..0.16 around the 0.15 boundary
            feed(75, depth = DepthSample(0.60f, 0.60f - (0.14f + rnd.nextFloat() * 0.02f), 0.15f))
        }
        assertTrue(out.toString(), out.all { it == CLOSER })
    }

    @Test fun alignmentAndDistanceAreSeparateLateralErrorsAlwaysWin() {
        validated()
        val out = feed(3000, tip = 0.30f to 0.55f, depth = DepthSample(0.60f, 0.60f, 0.0f))
        assertEquals(RIGHT, out.last()); assertFalse(REACHED in out)
        assertFalse(out.any { it == CLOSER || it == NEAR || it == BACK || it == AT_TARGET })
    }

    @Test fun aHeldDirectionIsDroppedOnTheFirstFrameWithoutAFingertip() {
        assertEquals(RIGHT, feed(600, tip = 0.30f to 0.55f).last())
        assertEquals(NONE, feed(75, tip = null).single()) // never "Move right." from a stale fingertip position
    }

    @Test fun reachedNeedsAlignmentToo() {
        validated()
        val out = feed(3000, tip = 0.30f to 0.55f, depth = DepthSample(0.60f, 0.60f, 0.0f))
        assertEquals(RIGHT, out.last()); assertFalse(REACHED in out)
    }

    @Test fun oneInvalidDepthFrameRestartsTheContactHold() {
        validated()
        val first = feed(450, depth = DepthSample(0.60f, 0.60f, 0.0f))      // 0..375: contact begins
        val glitch = feed(75, depth = DepthSample(Float.NaN, 0.60f))         // 450
        val after = feed(1500, depth = DepthSample(0.60f, 0.60f, 0.0f))      // 525..
        assertFalse(REACHED in first + glitch)
        val firstReached = 525 + 75L * after.indexOf(REACHED)
        assertTrue("reached at $firstReached", firstReached >= 525 + 500 + 300) // full hold + confirm after the break
    }

    @Test fun lostTargetIsReportedAndHandInFrontOfItIsDistinguished() {
        feed(600)
        assertEquals(HAND_COVERS_TARGET, feed(600, target = null, tip = 0.50f to 0.55f).last())
        assertEquals(TARGET_LOST, feed(600, target = null, tip = 0.20f to 0.20f).last())
        assertEquals(TARGET_LOST, feed(600, target = null, tip = null).last())
    }

    @Test fun targetNeverSeenSaysNothing() = assertTrue(feed(5000, target = null).all { it == NONE })

    @Test fun briefHandDropoutIsToleratedLongerIsReported() {
        feed(600)
        assertFalse(HAND_LOST in feed(450, tip = null))
        assertEquals(HAND_LOST, feed(600, tip = null).last())
    }

    @Test fun handNeverShownGetsAPromptAfterTheFirstWait() {
        val out = feed(5000, tip = null)
        assertFalse(HAND_LOST in out.take(4000 / 75))
        assertEquals(HAND_LOST, out.last())
    }

    @Test fun returningHandStartsFreshInsteadOfBlendingOldPositions() {
        feed(600, tip = 0.20f to 0.55f)
        feed(1000, tip = null)
        feed(75, tip = 0.50f to 0.55f)
        assertEquals(0f, e.ex, 1e-6f)
    }
}

class GuidancePhraseTest {
    @Test fun everyCueButNoneHasWordsAndNamesTheTarget() {
        for (g in Guidance.values()) assertEquals("$g", g == NONE, g.phrase("bottle") == null)
        assertEquals("Bottle reached.", REACHED.phrase("bottle"))
        assertEquals("I can't see the bottle. Hold still.", TARGET_LOST.phrase("bottle"))
    }
}

class GuidanceThrottleTest {
    @Test fun changedCuesWaitTheGapRepeatsWaitLonger() {
        val th = GuidanceThrottle(minGapMs = 1100, repeatMs = 2600)
        assertEquals(LEFT, th.filter(LEFT, 10_000))
        assertNull(th.filter(RIGHT, 10_500)); assertEquals(RIGHT, th.filter(RIGHT, 11_100))
        assertNull(th.filter(RIGHT, 12_000)); assertEquals(RIGHT, th.filter(RIGHT, 13_700))
        assertNull(th.filter(NONE, 20_000))
    }

    @Test fun statusLinesRepeatLessOftenThanDirections() {
        val th = GuidanceThrottle(minGapMs = 1100, repeatMs = 2600, statusRepeatMs = 6000)
        assertEquals(HAND_COVERS_TARGET, th.filter(HAND_COVERS_TARGET, 10_000))
        assertNull(th.filter(HAND_COVERS_TARGET, 13_000)); assertNull(th.filter(HAND_COVERS_TARGET, 15_900))
        assertEquals(HAND_COVERS_TARGET, th.filter(HAND_COVERS_TARGET, 16_000))
        assertTrue(LEFT.directional && CLOSER.directional && !LINED_UP_2D.directional && !HAND_LOST.directional)
    }

    @Test fun stopAndReachedAreNeverDelayed() {
        val th = GuidanceThrottle()
        th.filter(LEFT, 10_000); th.quiet(15_000)
        assertNull(th.filter(RIGHT, 12_000))                 // quiet window holds ordinary cues
        assertEquals(STOP, th.filter(STOP, 10_100))           // but not urgent ones
        assertEquals(REACHED, th.filter(REACHED, 10_200))
    }
}
