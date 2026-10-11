package com.reach.app.grounding

import com.reach.app.claude.ClaudeClient
import org.junit.Assert.*
import org.junit.Test

private fun box(cx: Float, cy: Float = 0.5f, half: Float = 0.05f) = Box(cx - half, cy - half, cx + half, cy + half)
private fun cand(id: Int, label: String, cx: Float, cy: Float = 0.5f, bev: String = "unknown", score: Float = 0.8f) =
    Candidate(id, label, bev, box(cx, cy), score, null)
private fun det(label: String, cx: Float, cy: Float = 0.5f) = Det(label, 0.8f, box(cx, cy))

class ClassMappingTest {
    // Copy of the labels.txt embedded in efficientdet_lite0.tflite; line N is model class index N-1.
    private val labels = javaClass.getResource("/coco_labels.txt")!!.readText().lines().filter { it.isNotEmpty() }

    @Test fun modelHas90ClassSlotsAnd80RealNames() {
        assertEquals(90, labels.size)
        assertEquals(80, labels.count { it != "???" })
    }

    @Test fun everySupportedLabelIsARealModelLabel() {
        val missing = SupportedClasses.DETECTED - labels.toSet()
        assertTrue("not in model labels: $missing", missing.isEmpty())
    }

    @Test fun modelHasNoCanClass() = assertFalse("can" in labels)

    @Test fun everySpeakableLabelIsActuallySurfacedByTheGrounder() {
        val unreachable = SupportedClasses.SPEAKABLE_LABELS - SupportedClasses.DETECTED
        assertTrue("speakable but filtered out: $unreachable", unreachable.isEmpty())
    }

    @Test fun classIndicesMatchLabelFileOrder() {
        val idx = mapOf("person" to 0, "bottle" to 43, "cup" to 46, "chair" to 61, "cell phone" to 76)
        idx.forEach { (name, i) -> assertEquals(name, labels[i]) }
    }

    @Test fun resolvesSpokenObjects() {
        assertEquals(setOf("cell phone"), SupportedClasses.resolve("Where is my phone?")!!.labels)
        assertEquals(setOf("wine glass"), SupportedClasses.resolve("find the wine glass")!!.labels)
        assertEquals(setOf("chair"), SupportedClasses.resolve("take me to a chair")!!.labels)
        val coke = SupportedClasses.resolve("find my Coke")!!
        assertEquals("coke", coke.beverage); assertEquals(SupportedClasses.DRINK_CONTAINERS, coke.labels)
        assertEquals("coke", SupportedClasses.resolve("a cola please")!!.beverage)
    }

    @Test fun unsupportedOrNonObjectSpeechResolvesToNothing() {
        assertNull(SupportedClasses.resolve("guide me"))
        assertNull(SupportedClasses.resolve("find the soda can")) // "can" is not a model class
        assertNull(SupportedClasses.resolve("find my giraffe"))   // real COCO class, deliberately unsupported
    }
}

class BoxTest {
    @Test fun validity() {
        assertTrue(Box(0.1f, 0.1f, 0.3f, 0.4f).isValid())
        assertFalse(Box(0.1f, 0.1f, 0.11f, 0.4f).isValid())            // sliver
        assertFalse(Box(0.3f, 0.1f, 0.1f, 0.4f).isValid())             // inverted
        assertFalse(Box(0f, 0f, Float.NaN, 0.5f).isValid())
        assertEquals(1f, Box.clamped(-0.2f, 0f, 1.4f, 1f).right, 0f)   // clamp keeps it in frame
    }
}

class TargetPickTest {
    private val cups = SupportedClasses.resolve("cup")!!

    @Test fun noneOneAmbiguous() {
        assertEquals(TargetPick.None, pickTarget(cups, listOf(cand(1, "bottle", 0.5f))))
        assertEquals(1, ((pickTarget(cups, listOf(cand(1, "cup", 0.5f), cand(2, "bottle", 0.2f)))) as TargetPick.One).c.id)
        val amb = pickTarget(cups, listOf(cand(1, "cup", 0.8f), cand(2, "cup", 0.2f))) as TargetPick.Ambiguous
        assertEquals(listOf(2, 1), amb.options.map { it.id }) // ordered left to right
    }

    @Test fun beverageSpecRequiresMatchingColorGuess() {
        val coke = SupportedClasses.resolve("coke")!!
        val cs = listOf(cand(1, "bottle", 0.3f, bev = "pepsi"), cand(2, "cup", 0.7f, bev = "coke"))
        assertEquals(2, (pickTarget(coke, cs) as TargetPick.One).c.id)
    }

    @Test fun positionAnswers() {
        val opts = listOf(cand(1, "cup", 0.2f), cand(2, "cup", 0.5f), cand(3, "cup", 0.8f))
        assertEquals(1, pickByPosition(opts, "the left one")!!.id)
        assertEquals(3, pickByPosition(opts, "Right.")!!.id)
        assertEquals(2, pickByPosition(opts, "middle")!!.id)
        assertNull(pickByPosition(opts, "the red one"))
    }

    @Test fun offlineFallbackAsksInsteadOfGuessing() {
        val client = ClaudeClient()
        val two = listOf(cand(1, "cup", 0.2f), cand(2, "cup", 0.8f))
        val r = client.interpretLocal("find my cup", two)
        assertTrue(r.needsClarification); assertEquals(-1, r.candidateId)
        assertNotNull(r.ambiguousSpec); assertTrue("left" in r.question)

        val one = client.interpretLocal("find my cup", listOf(cand(7, "cup", 0.4f, score = 0.62f)))
        assertEquals(7, one.candidateId); assertEquals(0.62, one.confidence, 1e-6); assertFalse(one.needsClarification)

        assertTrue(client.interpretLocal("find my phone", two).needsClarification) // not visible
        assertEquals(-1, client.interpretLocal("find my phone", two).candidateId)
    }
}

class CandidateTrackerTest {
    @Test fun idsSurviveSmallMotion() {
        val t = CandidateTracker()
        val a = t.assign(listOf(det("cup", 0.30f)), 0)
        val b = t.assign(listOf(det("cup", 0.33f)), 300)
        assertEquals(a[0], b[0])
    }

    @Test fun neighbouringCupsDoNotSwapIds() {
        val t = CandidateTracker()
        val f1 = t.assign(listOf(det("cup", 0.30f), det("cup", 0.50f)), 0)
        val f2 = t.assign(listOf(det("cup", 0.52f), det("cup", 0.31f)), 300) // reported in the other order
        assertEquals(f1[0], f2[1]); assertEquals(f1[1], f2[0])
    }

    @Test fun newObjectsAndOtherClassesGetNewIds() {
        val t = CandidateTracker()
        val a = t.assign(listOf(det("cup", 0.3f)), 0)
        val b = t.assign(listOf(det("cup", 0.3f), det("bottle", 0.3f), det("cup", 0.8f)), 300)
        assertEquals(a[0], b[0]); assertEquals(3, b.toSet().size)
    }

    @Test fun expiredTrackGetsAFreshIdAndIdsAreNeverReused() {
        val t = CandidateTracker(ttlMs = 1000)
        val a = t.assign(listOf(det("cup", 0.3f)), 0)
        val b = t.assign(listOf(det("cup", 0.3f)), 5000)
        assertNotEquals(a[0], b[0])
    }
}

class RecentCandidatesTest {
    @Test fun blinkedOutObjectStaysSelectableForTheGraceThenExpires() {
        val r = RecentCandidates(graceMs = 1000)
        assertEquals(listOf(1), r.update(listOf(cand(1, "cup", 0.3f)), 0).map { it.id })
        assertEquals(listOf(1), r.update(emptyList(), 600).map { it.id })       // blinked out
        assertEquals(emptyList<Int>(), r.update(emptyList(), 1500).map { it.id }) // really gone
    }

    @Test fun reappearingObjectIsNotDuplicatedAndKeepsItsLatestBox() {
        val r = RecentCandidates()
        r.update(listOf(cand(1, "cup", 0.3f)), 0)
        val now = r.update(listOf(cand(1, "cup", 0.4f), cand(2, "bottle", 0.7f)), 300)
        assertEquals(listOf(1, 2), now.map { it.id }); assertEquals(0.4f, now[0].box.centerX(), 1e-6f)
    }
}

class TargetLockTest {
    private fun locked(): TargetLock {
        val l = TargetLock(); l.lock(cand(1, "cup", 0.3f), 0); return l
    }

    @Test fun followsTheLockedInstanceNotJustTheClass() {
        val l = locked()
        val seen = l.update(listOf(cand(2, "cup", 0.31f), cand(1, "cup", 0.8f)), 300)
        assertEquals(1, seen!!.id); assertEquals(TrackStatus.TRACKING, l.status)
        assertEquals(0.8f, l.box!!.centerX(), 1e-6f)
    }

    @Test fun holdsBrieflyThenLoses() {
        val l = locked()
        assertNull(l.update(emptyList(), 800)); assertEquals(TrackStatus.HOLDING, l.status); assertNotNull(l.box)
        assertNull(l.update(emptyList(), 2000)); assertEquals(TrackStatus.LOST, l.status); assertNull(l.box)
    }

    @Test fun neverSwitchesToAnotherCupWhenSeveralAreVisible() {
        val l = locked()
        l.update(emptyList(), 2000)
        assertNull(l.update(listOf(cand(5, "cup", 0.32f), cand(6, "cup", 0.6f)), 2300))
        assertEquals(TrackStatus.LOST, l.status); assertEquals(1, l.id)
    }

    @Test fun reacquiresOnlyAnUnambiguousNearbyObjectOfTheSameClass() {
        val l = locked()
        l.update(emptyList(), 2000)
        assertNull(l.update(listOf(cand(5, "bottle", 0.3f)), 2100))        // wrong class
        assertNull(l.update(listOf(cand(5, "cup", 0.95f)), 2200))          // far from last position
        assertEquals(5, l.update(listOf(cand(5, "cup", 0.33f)), 2300)!!.id)
        assertEquals(TrackStatus.TRACKING, l.status); assertEquals(5, l.id)
        assertEquals(1, l.reacquisitions) // counted, so the app announces it
    }

    @Test fun inactiveLockDoesNothing() {
        val l = TargetLock()
        assertNull(l.update(listOf(cand(1, "cup", 0.5f)), 0)); assertEquals(TrackStatus.NONE, l.status)
    }
    @Test fun staleBoxStopsDrivingGuidanceEvenWithoutNewDetections() {
        val l = locked()
        assertNotNull(l.boxAt(1500))
        assertNull(l.boxAt(1501))          // detector stalled: no update() since t=0, box must not be used
        assertNotNull(l.box)               // ...although nothing has formally declared it lost yet
        l.update(listOf(cand(1, "cup", 0.3f)), 1400) // a late but fresh-enough result revives it
        assertNotNull(l.boxAt(2000))
    }
}

class DetectorGateTest {
    @Test fun oneInFlightAndRateLimited() {
        val g = DetectorGate(intervalMs = 300, staleMs = 600)
        assertTrue(g.tryStart(0))
        assertFalse(g.tryStart(400))       // previous run still in flight: frame skipped, not queued
        assertTrue(g.finish(0, 150))
        assertFalse(g.tryStart(200))       // finished, but within the interval
        assertTrue(g.tryStart(400))
    }

    @Test fun staleResultsAreRejected() {
        val g = DetectorGate(intervalMs = 0, staleMs = 600)
        assertTrue(g.tryStart(0)); assertFalse(g.finish(0, 601)); assertEquals(1, g.staleDropped)
        assertTrue(g.tryStart(1000)); assertTrue(g.finish(1000, 1600)); assertEquals(1, g.staleDropped)
    }

    @Test fun failedRunStillFreesTheSlot() {
        val g = DetectorGate(intervalMs = 0, staleMs = 600)
        assertTrue(g.tryStart(0)); g.finish(0, 5000)   // e.g. detector threw; finish() is called in finally
        assertTrue(g.tryStart(5000))
    }
}
