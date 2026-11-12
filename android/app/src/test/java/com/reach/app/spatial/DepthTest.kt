package com.reach.app.spatial

import com.reach.app.grounding.Box
import com.reach.app.hand.HandResult
import com.reach.app.hand.Lm
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Synthetic checks of every depth step. CPU image 640x480 (sensor orientation, as ARCore delivers it), rotated
 * 90° clockwise into the 480x640 upright analysis frame; fx = fy = 500, principal point at the centre.
 */
class DepthTest {
    private val intr = Intrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val identity = floatArrayOf(1f, 0f, 1f, 0f)

    private fun frame(mm: Int = 600, w: Int = 64, h: Int = 48, map: FloatArray = identity, skewNs: Long = 0L,
                      fill: (Int, Int) -> Int = { _, _ -> mm }) =
        DepthFrame(w, h, ShortArray(w * h) { fill(it % w, it / w).toShort() }, intr, 90, map, 1_000_000_000L, 1_000_000_000L - skewNs)

    // ---- coordinate transforms ----

    @Test fun rotationTransformsRoundTripForEveryOrientation() {
        val pts = listOf(0f to 0f, 1f to 0f, 0f to 1f, 0.2f to 0.7f, 0.5f to 0.5f)
        for (r in listOf(0, 90, 180, 270)) for ((x, y) in pts) {
            val (sx, sy) = uprightToSensor(x, y, r)
            val (ux, uy) = sensorToUpright(sx, sy, r)
            assertEquals("$r $x,$y", x, ux, 1e-6f); assertEquals("$r $x,$y", y, uy, 1e-6f)
        }
    }

    @Test fun ninetyDegreesClockwiseMatchesHowTheBitmapIsRotated() {
        // Matrix.postRotate(90) moves the sensor image's top-left corner to the upright image's top-right corner.
        assertEquals(0f to 0f, uprightToSensor(1f, 0f, 90))
        assertEquals(1f to 0f, uprightToSensor(1f, 1f, 90)) // sensor top-right -> upright bottom-right
        assertEquals(0f to 1f, uprightToSensor(0f, 0f, 90)) // sensor bottom-left -> upright top-left
    }

    @Test fun axisAlignedCropBetweenCpuImageAndDepthMapIsRecovered() {
        // A 16:9 texture inside a 4:3 CPU image: full width, central 75% of the height.
        val probe = floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0.5f, 0.5f)
        val out = FloatArray(10) { i -> if (i % 2 == 0) probe[i] else (probe[i] - 0.125f) / 0.75f }
        val m = affineOrNull(probe, out)!!
        assertEquals(1f, m[0], 1e-5f); assertEquals(0f, m[1], 1e-5f)
        assertEquals(1.3333f, m[2], 1e-3f); assertEquals(-0.16667f, m[3], 1e-4f)
    }

    @Test fun mappingImpliedByTwoImagesOfTheSameLensMatchesARCoresCrop() {
        assertArrayEquals(identity, intrinsicsMap(intr, intr), 1e-6f)
        // The A15's ARCore images: CPU 640x480, texture 1920x1080 with the same horizontal field of view (f x3).
        val tex = Intrinsics(487.6f * 3, 487.4f * 3, 318.7f * 3, 241.0f * 3 - 180f, 1920, 1080)
        val cpu = Intrinsics(487.6f, 487.4f, 318.7f, 241.0f, 640, 480)
        val m = intrinsicsMap(cpu, tex)
        assertEquals(1f, m[0], 1e-5f); assertEquals(0f, m[1], 1e-5f)       // full width
        assertEquals(1.3333f, m[2], 1e-3f); assertEquals(-0.1667f, m[3], 1e-3f) // central 75% of the height
        // A CPU-image point maps to the same ray in the texture.
        val (u, v) = 100f to 300f
        val tu = (m[0] * u / 640 + m[1]) * 1920; val tv = (m[2] * v / 480 + m[3]) * 1080
        assertEquals((u - cpu.cx) / cpu.fx, (tu - tex.cx) / tex.fx, 1e-4f)
        assertEquals((v - cpu.cy) / cpu.fy, (tv - tex.cy) / tex.fy, 1e-4f)
    }

    @Test fun aRotatedOrSwappedMappingIsRefusedNotAssumed() {
        val probe = floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0.5f, 0.5f)
        val swapped = FloatArray(10) { i -> probe[if (i % 2 == 0) i + 1 else i - 1] } // x and y exchanged
        assertNull(affineOrNull(probe, swapped))
        val nonLinear = probe.copyOf().also { it[8] = 0.6f } // centre off the line through the corners
        assertNull(affineOrNull(probe, nonLinear))
    }

    @Test fun depthLookupGoesThroughRotationAndCrop() {
        val f = frame(map = floatArrayOf(1f, 0f, 1.3333f, -0.16667f))
        // Upright (0.5, 0.5) is sensor (0.5, 0.5) -> depth (0.5, 0.5) of the 64x48 map.
        val (dx, dy) = f.depthPx(0.5f, 0.5f)
        assertEquals(32f, dx, 1e-2f); assertEquals(24f, dy, 1e-2f)
        // Upright x = 0.95 is sensor y = 0.05: inside the CPU image, cropped out of the depth map (above its top).
        assertTrue(f.depthPx(0.95f, 0.5f).second < 0f)
        assertNull(f.medianM(0.92f, 0.45f, 0.98f, 0.55f))
    }

    // ---- sampling and validity ----

    @Test fun medianRejectsZerosAndOutOfRangeSamples() {
        assertEquals(0.6f, frame(600).medianM(0.4f, 0.4f, 0.6f, 0.6f)!!, 1e-6f)
        // Half the pixels say 0 (no data) and a few say 9 m: the median of the valid ones is still 0.6.
        val noisy = frame { x, y -> if ((x + y) % 2 == 0) 0 else if (x % 7 == 0) 9000 else 600 }
        assertEquals(0.6f, noisy.medianM(0.3f, 0.3f, 0.7f, 0.7f)!!, 1e-6f)
    }

    @Test fun tooFewValidSamplesIsNoMeasurement() {
        assertNull(frame(0).medianM(0.3f, 0.3f, 0.7f, 0.7f))                       // no data at all
        assertNull(frame(5000).medianM(0.3f, 0.3f, 0.7f, 0.7f))                    // all beyond 3 m
        assertNull(frame { x, _ -> if (x % 5 == 0) 600 else 0 }.medianM(0.3f, 0.3f, 0.7f, 0.7f)) // 20% valid < 30%
        assertNull(frame(100).medianM(0.3f, 0.3f, 0.7f, 0.7f))                     // 10 cm: closer than the sensor can tell
    }

    @Test fun staleDepthIsRefused() {
        assertTrue(frame(skewNs = 150_000_000L).fresh)
        val stale = frame(skewNs = 250_000_000L)
        assertFalse(stale.fresh)
        val r = DepthMeter().measure(stale, Box(0.4f, 0.4f, 0.6f, 0.6f), HandResult.NONE, emptyList(), 0)
        assertNull(r.sample); assertEquals("depth stale", r.reason)
    }

    // ---- 3D ----

    @Test fun unprojectionAndDistanceInMetres() {
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), unproject(intr, 320f, 240f, 1f), 1e-6f)
        val p = unproject(intr, 820f, 240f, 2f) // 500 px right of centre at 2 m -> 2 m right
        assertArrayEquals(floatArrayOf(2f, 0f, 2f), p, 1e-6f)
        assertEquals(hypot(2f, 1f), dist3(p, floatArrayOf(0f, 0f, 1f)), 1e-6f)
    }

    /** A hand-shaped set of world points (m, camera-aligned) projected by a pinhole camera at palm depth z. */
    private fun syntheticHand(z: Float, cxN: Float = 0.5f, cyN: Float = 0.5f, scale: Float = 1f): HandResult {
        val world = (0 until 21).map { i ->
            val finger = (i - 1) / 4; val joint = (i - 1) % 4
            if (i == 0) Lm(0f, 0.045f * scale, 0.005f)
            else Lm((finger - 2) * 0.018f * scale, (0.01f - joint * 0.022f) * scale, -0.004f * joint)
        }
        // Project in the upright frame (480x640, f = 500, centre at cxN, cyN of the frame).
        val lm = world.map { w ->
            val zz = z + w.z
            Lm((cxN * 480 + 500 * w.x / zz) / 480f, (cyN * 640 + 500 * w.y / zz) / 640f, 0f)
        }
        return HandResult.fromLandmarks(lm, 0.9f, "Right", world)
    }

    @Test fun fingertipDepthIsRecoveredFromHandGeometry() {
        for (z in listOf(0.3f, 0.5f, 0.8f)) {
            val h = syntheticHand(z)
            val expected = z + h.world[8].z // palm depth + the tip's own offset
            val got = handDepthM(h, frame())!!
            assertTrue("z=$z got=$got expected=$expected", abs(got - expected) / expected < 0.03f)
        }
    }

    @Test fun aSmallerHandAtTheSameDistanceReadsFartherThatIsTheEstimatesKnownLimit() {
        // MediaPipe's world scale is a typical hand: a hand 15% smaller than that looks 15% farther away.
        val small = syntheticHand(0.5f, scale = 0.85f).let { it.copy(world = syntheticHand(0.5f).world) }
        val got = handDepthM(small, frame())!!
        assertEquals(0.5f / 0.85f, got, 0.03f)
        assertEquals(0.15f, HAND_DEPTH_REL_ERR, 0f) // what the screen and logs quote as the uncertainty
    }

    @Test fun fingertipDepthIsRefusedWhenItCannotBeMeasured() {
        val h = syntheticHand(0.5f)
        assertNull(handDepthM(h.copy(world = emptyList()), frame()))                         // no world landmarks
        val offFrame = syntheticHand(0.5f, cxN = 0.5f, cyN = 0.04f)                            // tip above the frame
        assertFalse(offFrame.index!!.inFrame); assertNull(handDepthM(offFrame, frame()))
        assertNull(handDepthM(syntheticHand(2.9f, scale = 0.5f), frame()))                   // ~3 px spread: too small
        assertNull(handDepthM(HandResult.NONE, frame()))
    }

    @Test fun targetDepthIsMeasuredOnlyWhileNoHandCoversItAndExpires() {
        val td = TargetDepth()
        val box = Box(0.4f, 0.4f, 0.6f, 0.6f)
        assertEquals(0.6f, td.update(box, false, frame(600), 0)!!, 1e-6f)
        // The hand now covers the target: the depth map there reads the hand (0.35 m), which must not replace it.
        assertEquals(0.6f, td.update(box, true, frame(350), 1000)!!, 1e-6f)
        assertNull(td.update(box, true, frame(350), TargetDepth.HOLD_MS + 1))
    }

    @Test fun handsOverDetectsLandmarksInsideTheGrownBox() {
        val box = Box(0.4f, 0.4f, 0.6f, 0.6f)
        assertTrue(handsOver(box, listOf(syntheticHand(0.5f))))
        assertFalse(handsOver(box, listOf(syntheticHand(0.5f, cxN = 0.15f, cyN = 0.8f))))
    }

    @Test fun meterGivesTargetAndFingertipInOneCameraFrameAndTheir3dDistance() {
        val box = Box(0.45f, 0.4f, 0.55f, 0.6f)
        val hand = syntheticHand(0.45f, cxN = 0.2f, cyN = 0.75f) // left of and below the target, 15 cm nearer
        val r = DepthMeter().measure(frame(600), box, hand, listOf(hand), 0)
        assertEquals("ok", r.reason)
        val s = r.sample!!
        assertEquals(0.6f, s.targetM, 1e-6f)
        assertEquals(0.45f + hand.world[8].z, s.tipM, 0.02f)
        // Independent check: both points through the same intrinsics, then Euclidean distance.
        val f = frame(600)
        val tip = hand.index!!
        val expected = dist3(f.point(0.5f, 0.5f, s.targetM), f.point(tip.x, tip.y, s.tipM))
        assertEquals(expected, s.dist3dM, 1e-5f)
        assertTrue(s.dist3dM > s.targetM - s.tipM) // lateral offset adds to the forward gap
    }

    @Test fun meterSaysWhyThereIsNoMeasurement() {
        val box = Box(0.45f, 0.4f, 0.55f, 0.6f)
        assertEquals("no target box", DepthMeter().measure(frame(), null, HandResult.NONE, emptyList(), 0).reason)
        assertEquals("no valid target depth", DepthMeter().measure(frame(0), box, HandResult.NONE, emptyList(), 0).reason)
        assertEquals("no fingertip", DepthMeter().measure(frame(), box, HandResult.NONE, emptyList(), 0).reason)
        val noWorld = syntheticHand(0.45f, cxN = 0.2f, cyN = 0.75f).copy(world = emptyList())
        assertEquals("fingertip depth not measurable", DepthMeter().measure(frame(), box, noWorld, listOf(noWorld), 0).reason)
    }
}

class TipDistanceTest {
    @Test fun threeDDistanceFromAnEstimatedFingertipToTheTarget() {
        val frame = DepthFrame(64, 48, ShortArray(64 * 48) { 600 }, Intrinsics(500f, 500f, 320f, 240f, 640, 480), 0,
            floatArrayOf(1f, 0f, 1f, 0f), 0L, 0L)
        // A world hand of 4 cm radius at 0.5 m, projected: its index tip (landmark 8) is then at depth 0.5 m.
        val world = List(21) { i -> val a = 2 * Math.PI * i / 21; com.reach.app.hand.Lm((0.04 * kotlin.math.cos(a)).toFloat(), (0.04 * kotlin.math.sin(a)).toFloat(), 0f) }
        val img = world.map { com.reach.app.hand.Lm(0.5f + 500f * it.x / 0.5f / 640f, 0.5f + 500f * it.y / 0.5f / 480f) }
        val h = com.reach.app.hand.HandResult.fromLandmarks(img, 0.9f, null, world)
        assertEquals(0.5f, handDepthM(h, frame)!!, 1e-3f)
        val box = com.reach.app.grounding.Box(0.45f, 0.45f, 0.55f, 0.55f) // centre on the optical axis
        // Tip: world (0.04 cos a8, 0.04 sin a8) at z 0.5, target (0, 0, 0.6) up to the 0.5 px principal-point offset.
        val a8 = 2 * Math.PI * 8 / 21
        val expected = kotlin.math.sqrt((0.04 * kotlin.math.cos(a8)).let { it * it } + (0.04 * kotlin.math.sin(a8)).let { it * it } + 0.01).toFloat()
        assertEquals(expected, tipToTargetM(frame, box, 0.6f, h)!!, 2e-3f)
        assertNull(tipToTargetM(frame, box, 0.6f, h.copy(world = emptyList()))) // no world landmarks: not measurable
    }
}
