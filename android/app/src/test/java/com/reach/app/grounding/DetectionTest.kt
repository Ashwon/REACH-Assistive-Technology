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

    @Test fun theSupportedSetIsExactlyTheSixEverydayObjects() =
        assertEquals(setOf("bottle", "laptop", "book", "backpack", "handbag", "cup"), SupportedClasses.DETECTED)

    @Test fun resolvesSpokenObjects() {
        assertEquals(setOf("laptop"), SupportedClasses.resolve("Where is my laptop?")!!.labels)
        assertEquals(setOf("laptop"), SupportedClasses.resolve("find my computer")!!.labels)
        assertEquals(setOf("cup"), SupportedClasses.resolve("my mug")!!.labels)
        assertEquals(setOf("backpack"), SupportedClasses.resolve("find my rucksack")!!.labels)
        assertEquals(setOf("handbag"), SupportedClasses.resolve("where's my purse")!!.labels)
        assertEquals(setOf("book"), SupportedClasses.resolve("find the book")!!.labels)
        val coke = SupportedClasses.resolve("find my Coke")!!
        assertEquals("coke", coke.beverage); assertEquals(SupportedClasses.DRINK_CONTAINERS, coke.labels)
        assertEquals("coke", SupportedClasses.resolve("a cola please")!!.beverage)
    }

    @Test fun peopleAndScissorsAreNeverTargetsAndSaySo() {
        assertFalse("person" in SupportedClasses.DETECTED); assertFalse("scissors" in SupportedClasses.DETECTED)
        assertFalse(SupportedClasses.resolve("find the person")!!.supported)
        assertEquals("I only help find objects, not people.", ClaudeClient().interpretLocal("where is someone", emptyList()).question)
        assertEquals("I don't guide hands to sharp things like scissors.", ClaudeClient().interpretLocal("find the scissors", emptyList()).question)
    }

    @Test fun theDemoCategoriesAreSupportedAndTheBagWithoutAClassIsNot() {
        for (w in listOf("water bottle", "laptop", "book", "backpack", "cup")) assertTrue(w, SupportedClasses.resolve("find my $w")!!.supported)
        assertEquals(setOf("backpack", "handbag"), SupportedClasses.resolve("the other bag")!!.labels)
        assertFalse(SupportedClasses.resolve("find my laptop bag")!!.supported)
    }

    /** Before: the alphabetically/longest-first key won, so "the laptop next to my bottle" was a bottle. */
    @Test fun theFirstObjectNamedIsTheOneAskedFor() {
        assertEquals("laptop", SupportedClasses.resolve("find the laptop next to my bottle")!!.name)
        assertEquals("bottle", SupportedClasses.resolve("find the bottle next to my laptop")!!.name)
        assertFalse(SupportedClasses.resolve("find my laptop bag")!!.supported)       // the longer phrase at the same place
        assertEquals(setOf("bottle"), SupportedClasses.resolve("my water bottle by the cup")!!.labels)
        assertEquals("coke", SupportedClasses.resolve("the coke next to the laptop")!!.beverage)
    }

    @Test fun neighbourWordsAndTheNearestOption() {
        val (before, anchor) = nearAnchor("the one next to my bottle")!!
        assertEquals(" the one", before); assertEquals("bottle", anchor.name)
        assertEquals("cup", nearAnchor("the laptop closest to the cup")!!.second.name)
        assertNull(nearAnchor("the one on the left")); assertNull(nearAnchor("the one next to it"))
        val a = cand(1, "laptop", 0.2f); val b = cand(2, "laptop", 0.7f)
        assertEquals(2, pickNear(listOf(a, b), cand(3, "bottle", 0.9f))!!.id)
        assertNull(pickNear(listOf(a, b), cand(3, "bottle", 0.45f)))                  // about as close to both
    }

    @Test fun nonObjectSpeechResolvesToNothing() {
        assertNull(SupportedClasses.resolve("guide me"))
        assertNull(SupportedClasses.resolve("can you help me"))   // the verb "can" is not the object
        assertNull(SupportedClasses.resolve("find my giraffe"))   // never asked for: the interpreter answers
    }

    /** Before: phone, chair, glass... were targets, and "glass" silently meant "wine glass or cup". */
    @Test fun outOfScopeObjectsGetAClearAnswerAndNoLabels() {
        for (w in listOf("phone", "cell phone", "chair", "glass", "wine glass", "vase", "bowl", "remote", "keyboard",
                         "mouse", "clock", "apple", "teddy bear", "soda can", "laptop bag")) {
            val spec = SupportedClasses.resolve("find my $w")!!
            assertFalse(w, spec.supported); assertTrue(w, spec.labels.isEmpty())
            val r = ClaudeClient().interpretLocal("find my $w", listOf(cand(1, "cup", 0.5f), cand(2, "laptop", 0.2f)))
            assertEquals(w, -1, r.candidateId); assertTrue(r.needsClarification)
            assertTrue(r.question, r.question.endsWith(SupportedClasses.SUPPORTED_LINE))
        }
        assertEquals("I can't recognise a phone yet. ${SupportedClasses.SUPPORTED_LINE}", ClaudeClient().interpretLocal("where is my phone", emptyList()).question)
        assertEquals("I can't recognise an apple yet. ${SupportedClasses.SUPPORTED_LINE}", ClaudeClient().interpretLocal("an apple", emptyList()).question)
        assertTrue(ClaudeClient().interpretLocal("find my laptop bag", emptyList()).question.startsWith("I can't tell a laptop bag from other bags."))
    }
}

class DarkBoxTest {
    @Test fun aWeakDetectionOnADarkFeaturelessBoxIsRejectedAndAStrongOneIsKept() {
        val box = Box(0.2f, 0.2f, 0.6f, 0.6f)
        val shadow = Appearance.of(IntArray(400) { 0xFF0A0A0C.toInt() + (it % 3) })!!
        assertTrue(shadow.darkFlat)
        assertEquals("darkflat", rejectReason(0.45f, box, look = shadow))   // a weak "laptop" on shadow
        assertNull(rejectReason(0.7f, box, look = shadow))                  // strong: kept (and marked dark, never "sure")
        val lid = Appearance.of(IntArray(400) { if (it % 2 == 0) 0xFF101010.toInt() else 0xFFB0B0B0.toInt() })!!
        assertFalse(lid.darkFlat); assertNull(rejectReason(0.45f, box, look = lid)) // dark but with edges/texture
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

    @Test fun nearWholeFrameBoxesAreImplausible() {
        // Boxes the A15 replay of underexposed frames produced (laptop 0.21, surfboard 0.28, clock 0.28 ...).
        for (b in listOf(Box(0f, 0f, 0.74f, 0.99f), Box(0.01f, 0f, 0.84f, 0.98f), Box(0f, 0f, 0.96f, 0.99f)))
            assertFalse("$b", b.isPlausible())
        assertTrue(Box(0.45f, 0.85f, 0.95f, 0.98f).isPlausible())   // the real laptop at the frame edge
        assertTrue(Box(0.3f, 0.2f, 0.8f, 0.95f).isPlausible())      // a person/chair close up
    }

    @Test fun iou() {
        val a = Box(0f, 0f, 0.2f, 0.2f)
        assertEquals(1f, a.iou(a), 1e-6f)
        assertEquals(0f, a.iou(Box(0.3f, 0.3f, 0.5f, 0.5f)), 0f)
        assertEquals(1f / 7f, a.iou(Box(0.1f, 0.1f, 0.3f, 0.3f)), 1e-6f)
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
        assertEquals("I can help. I see two cups. Do you want the one on the left or the right?", r.question)

        val one = client.interpretLocal("find my cup", listOf(cand(7, "cup", 0.4f, score = 0.62f)))
        assertEquals(7, one.candidateId); assertEquals(0.62, one.confidence, 1e-6); assertFalse(one.needsClarification)

        assertTrue(client.interpretLocal("find my bottle", two).needsClarification) // not visible
        assertEquals(-1, client.interpretLocal("find my bottle", two).candidateId)
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

    @Test fun anObjectIsConfirmedOnlyAfterSeveralDetectorRunsAndItsScoreIsSmoothed() {
        val t = CandidateTracker()
        val id = t.assign(listOf(Det("laptop", 0.70f, box(0.5f))), 0)[0]
        assertFalse(t.evidence(id)!!.confirmed)                        // a single frame is never enough
        t.assign(listOf(Det("laptop", 0.40f, box(0.5f))), 300)
        assertFalse(t.evidence(id)!!.confirmed)
        t.assign(listOf(Det("laptop", 0.40f, box(0.5f))), 600)
        val ev = t.evidence(id)!!
        assertTrue(ev.confirmed); assertEquals(0.475f, ev.score, 1e-6f) // 0.70 -> 0.55 -> 0.475
    }

    @Test fun repeatingTheSameDetectionConfirmsStabilityButNotTheClass() {
        // A dark patch reported as "laptop" in every frame is stable; stability is all `confirmed` claims.
        val t = CandidateTracker()
        var id = -1
        repeat(10) { id = t.assign(listOf(Det("laptop", 0.42f, box(0.5f))), it * 300L)[0] }
        val ev = t.evidence(id)!!
        assertTrue(ev.confirmed)
        val c = Candidate(id, "laptop", "unknown", box(0.5f), ev.score, null, ev.confirmed, ev.conflict)
        assertFalse(c.sure) // a 0.42 detector score is still "maybe a laptop", however often it repeats
    }

    @Test fun aSpotTheModelCallsTwoDifferentThingsIsAConflict() {
        val t = CandidateTracker()
        val bottle = t.assign(listOf(det("bottle", 0.5f)), 0)[0]
        t.assign(listOf(det("handbag", 0.5f)), 300)                    // same object, other class next frame
        t.assign(listOf(det("bottle", 0.5f)), 600)
        assertEquals("handbag", t.evidence(bottle)!!.conflict)
        t.assign(listOf(det("bottle", 0.5f)), 2000)                     // handbag track expired, dispute remembered
        assertEquals("handbag", t.evidence(bottle)!!.conflict)
        t.assign(listOf(det("bottle", 0.5f)), 3200)                     // > ttl since the two last coexisted
        assertNull(t.evidence(bottle)!!.conflict)
    }

    @Test fun aConflictDoesNotFlickerWithBoxJitter() {
        val t = CandidateTracker()
        val bed = Box(0.01f, 0.55f, 0.75f, 0.99f)
        t.assign(listOf(Det("bed", 0.5f, bed)), 0)
        val p = t.assign(listOf(Det("person", 0.5f, Box(0.26f, 0.58f, 0.73f, 0.95f))), 100)[0]  // IoU 0.53 with the bed
        t.assign(listOf(Det("bed", 0.5f, bed)), 200)
        t.assign(listOf(Det("person", 0.5f, Box(0.26f, 0.58f, 0.70f, 0.93f))), 300)             // IoU 0.48 this frame
        assertEquals("bed", t.evidence(p)!!.conflict)
    }

    @Test fun aBottleBesideABagIsNoConflict() {
        val t = CandidateTracker()
        val ids = t.assign(listOf(det("bottle", 0.40f), det("handbag", 0.55f)), 0)
        assertNull(t.evidence(ids[0])!!.conflict); assertNull(t.evidence(ids[1])!!.conflict)
    }
}

class BottleAndBagTest {
    private val bottle = cand(1, "bottle", 0.40f)
    private val bag = cand(2, "handbag", 0.60f)

    @Test fun askingForOneNeverResolvesToTheOther() {
        assertEquals(1, (pickTarget(SupportedClasses.resolve("find my bottle")!!, listOf(bag, bottle)) as TargetPick.One).c.id)
        assertEquals(2, (pickTarget(SupportedClasses.resolve("where is my bag")!!, listOf(bottle, bag)) as TargetPick.One).c.id)
        assertEquals(TargetPick.None, pickTarget(SupportedClasses.resolve("find my bottle")!!, listOf(bag)))
    }

    @Test fun lockedBottleNeverSwitchesToTheBagWhenTheBottleDisappears() {
        val l = TargetLock(); l.lock(bottle, 0)
        assertNull(l.update(listOf(bag), 2000)); assertNull(l.update(listOf(cand(3, "handbag", 0.42f)), 3000))
        assertEquals(TrackStatus.LOST, l.status); assertEquals(1, l.id); assertEquals("bottle", l.label)
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

    @Test fun aDetectorMissIsTemporaryThenLostButTheIdentityIsKept() {
        val l = locked()
        assertNull(l.update(emptyList(), 800)); assertEquals(TrackStatus.TRACKING, l.status)  // one missed run: still fresh
        assertNull(l.update(emptyList(), 1200)); assertEquals(TrackStatus.TEMPORARILY_MISSING, l.status)
        assertNull(l.boxAt(1200))                                      // a remembered box is not a measurement
        assertNull(l.update(emptyList(), 2600)); assertEquals(TrackStatus.LOST, l.status)
        assertEquals(1, l.id); assertEquals("cup", l.label); assertEquals(0.3f, l.box!!.centerX(), 1e-6f) // still who it is
        assertFalse(l.wasOccluded)
    }

    @Test fun aHandOverTheTargetIsOcclusionWithItsOwnLongerGrace() {
        val l = locked()
        for (ms in 0L..7900L step 100) l.noteHands(true, ms)         // a hand stays over it
        assertEquals(TrackStatus.OCCLUDED, l.statusAt(7900)); assertNull(l.boxAt(7900))
        l.noteHands(true, 8100)
        assertEquals(TrackStatus.LOST, l.statusAt(8100)); assertTrue(l.wasOccluded)
        val m = locked()
        for (ms in 0L..3000L step 100) m.noteHands(true, ms)         // the hand covers it for 3 s, then moves away...
        assertEquals(TrackStatus.TEMPORARILY_MISSING, m.statusAt(4000)) // ...and the miss is timed from then
        assertEquals(TrackStatus.LOST, m.statusAt(5600))
    }

    @Test fun neverSwitchesToAnotherCupWhenSeveralAreVisible() {
        val l = locked()
        l.update(emptyList(), 3000)
        assertNull(l.update(listOf(cand(5, "cup", 0.32f), cand(6, "cup", 0.6f)), 3300))
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

    @Test fun neverReacquiresAnUnsettledOrDisputedDetection() {
        val l = locked()
        l.update(emptyList(), 3000)
        val near = Candidate(5, "cup", "unknown", box(0.33f), 0.8f, null, confirmed = false)
        assertNull(l.update(listOf(near), 3100))                                  // seen in one frame only
        assertNull(l.update(listOf(near.copy(confirmed = true, conflict = "bowl")), 3200)) // cup or bowl?
        assertEquals(TrackStatus.LOST, l.status); assertEquals(0, l.reacquisitions)
        assertEquals(5, l.update(listOf(near.copy(confirmed = true)), 3300)!!.id)
    }

    @Test fun inactiveLockDoesNothing() {
        val l = TargetLock()
        assertNull(l.update(listOf(cand(1, "cup", 0.5f)), 0)); assertEquals(TrackStatus.NONE, l.status)
    }
    @Test fun staleBoxStopsDrivingGuidanceEvenWithoutNewDetections() {
        val l = locked()
        assertNotNull(l.boxAt(1000))
        assertNull(l.boxAt(1001))          // detector stalled: no update() since t=0, box must not be used
        assertNotNull(l.box)               // ...although nothing has formally declared it lost yet
        l.update(listOf(cand(1, "cup", 0.3f)), 1400) // a late but fresh-enough result revives it
        assertNotNull(l.boxAt(2000))
    }

    private fun look(vararg shares: Pair<String, Float>) =
        Appearance(FloatArray(Appearance.COLORS.size) { i -> shares.firstOrNull { it.first == Appearance.COLORS[i] }?.second ?: 0f }, 120f, 40f)

    @Test fun reFindingUnderANewIdNeedsTheSameColoursWhenTheyAreKnown() {
        val l = TargetLock(); l.lock(cand(1, "bottle", 0.3f).copy(look = look("blue" to 0.7f, "white" to 0.3f)), 0)
        l.update(emptyList(), 3000)
        assertNull(l.update(listOf(cand(5, "bottle", 0.32f).copy(look = look("red" to 0.8f, "white" to 0.2f))), 3300)) // another bottle
        assertEquals(1, l.id)
        assertEquals(6, l.update(listOf(cand(6, "bottle", 0.32f).copy(look = look("blue" to 0.6f, "white" to 0.4f))), 3600)!!.id)
    }

    @Test fun anObjectSeenTogetherWithTheTargetIsNeverTakenForIt() {
        val l = locked()
        l.update(listOf(cand(1, "cup", 0.3f), cand(2, "cup", 0.36f)), 300) // two cups side by side
        assertNull(l.update(listOf(cand(2, "cup", 0.36f)), 3500))           // the target vanished; its neighbour did not
        assertEquals(1, l.id); assertEquals(TrackStatus.LOST, l.status)
    }

    @Test fun afterATurnTheTargetCanBeReFoundAnywhereButOnlyIfItIsTheOnlyCandidate() {
        val l = locked()
        l.update(emptyList(), 3000)
        assertNull(l.update(listOf(cand(5, "cup", 0.9f)), 3300))            // far from where it was
        l.relaxPosition()
        assertNull(l.update(listOf(cand(5, "cup", 0.9f), cand(6, "cup", 0.6f)), 3600)) // two: no guess
        assertEquals(5, l.update(listOf(cand(5, "cup", 0.9f)), 3900)!!.id)
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
