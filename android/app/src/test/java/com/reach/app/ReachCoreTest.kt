package com.reach.app

import com.reach.app.grounding.Box
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.IdMatch
import com.reach.app.grounding.TrackStatus
import com.reach.app.haptic.HapticCommand
import com.reach.app.haptic.HapticCue
import com.reach.app.hand.HandResult
import com.reach.app.hand.HandSelector
import com.reach.app.hand.HandSelector.SlotState
import com.reach.app.hand.Lm
import com.reach.app.hand.Pose
import com.reach.app.hand.Side
import com.reach.app.hand.hand
import com.reach.app.spatial.DepthFrame
import com.reach.app.spatial.DepthSample
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.Intrinsics
import com.reach.app.spatial.TurnMonitor
import com.reach.app.spatial.phrase
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import kotlin.math.cos
import kotlin.math.sin

/** Frames every 75 ms; the detector reports every 300 ms. Bottle box centre (0.50, 0.55). */
class ReachCoreTest {
    private val spoken: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** The band cue ReachCore requested, one entry per frame. */
    private val sent: MutableList<HapticCue?> = Collections.synchronizedList(ArrayList())
    private var core = ReachCore({ spoken += it }, haptic = { sent += it })
    /** The band cues as they changed from entry [from] on. */
    private fun changes(from: Int = 0) = synchronized(sent) { sent.drop(from) }
        .fold(ArrayList<HapticCue?>()) { acc, c -> if (acc.isEmpty() || acc.last() != c) acc += c; acc }
    private fun assertInOrder(want: List<HapticCue?>, got: List<HapticCue?>) {
        var i = 0
        for (c in got) if (i < want.size && c == want[i]) i++
        assertEquals("$want in order within $got", want.size, i)
    }
    private var t = 0L

    private val bottle = cand(1, "bottle")
    private val leftOfBottle = hand(0.30f, 0.75f, 0.20f, tipX = 0.30f, tipY = 0.55f)   // -> "Move right."
    private val onBottle = hand(0.50f, 0.75f, 0.20f, tipX = 0.50f, tipY = 0.55f)
    private val bystander = hand(0.80f, 0.30f, 0.18f, tipX = 0.80f, tipY = 0.20f)      // would be "Move left."
    private val directions = listOf(Guidance.LEFT, Guidance.RIGHT, Guidance.UP, Guidance.DOWN, Guidance.CLOSER)
        .map { it.phrase("bottle")!! }

    private fun cand(id: Int, label: String, score: Float = 0.8f, confirmed: Boolean = true, conflict: String? = null,
                     lowLight: Boolean = false, box: Box = Box(0.45f, 0.40f, 0.55f, 0.70f)) =
        Candidate(id, label, "unknown", box, score, null, confirmed, conflict, lowLight)

    private fun run(ms: Long, target: Boolean = true, depth: DepthSample? = null, c: ReachCore = core,
                    dets: () -> List<Candidate> = { listOf(bottle) }, hands: () -> List<HandResult>) {
        val end = t + ms
        while (t < end) {
            if (target && t % 300 == 0L) c.onDetections(dets(), t, c.session)
            c.onFrame(hands(), emptyList(), t, depth)
            t += 75
        }
    }

    /** The user raises the hand at [at]'s palm, opens it, then closes it into a fist. */
    private fun gesture(at: HandResult, vararg extra: HandResult, c: ReachCore = core) {
        run(450, c = c) { listOf(hand(at.palmX, at.palmY, at.handSize, pose = Pose.OPEN)) + extra }
        run(450, c = c) { listOf(hand(at.palmX, at.palmY, at.handSize, pose = Pose.FIST)) + extra }
    }

    /** Enrols one hand (the right) at [at]: enough for single-hand guidance scenarios. */
    private fun setHand(at: HandResult = leftOfBottle, c: ReachCore = core) { c.beginHandSetup(t, side = Side.RIGHT); gesture(at, c = c) }

    private fun lock(c: ReachCore = core) =
        assertTrue(c.onSearchResult(c.beginSearch(), bottle, "bottle", 0.8, offline = true, question = "", nowMs = t))

    private fun lockAndGuide(at: HandResult = leftOfBottle) { setHand(at); lock(); assertTrue(core.startGuidance(t)) }

    private fun spokenSince(i: Int) = synchronized(spoken) { spoken.drop(i) }

    // ---- hands ----

    @Test fun bothHandsAreEnrolledFirstEachByItsOwnGesture() {
        core.beginHandSetup(t, "REACH ready. ")
        assertEquals("REACH ready. ${ReachCore.handSetup(Side.LEFT)}", spoken.last())
        run(5000) { listOf(leftOfBottle) }
        assertEquals(Guidance.HAND_FIST.phrase("")!!, spoken.last()) // a raised hand is asked to confirm, not assumed
        val other = hand(0.12f, 0.85f, 0.18f)
        gesture(other)
        assertEquals("Got your left hand. ${ReachCore.handSetup(Side.RIGHT)}", spoken.last())
        gesture(leftOfBottle, other)
        assertEquals("Got your right hand. Both hands are set.", spoken.last())
        val s = core.onFrame(listOf(other, leftOfBottle), emptyList(), t)
        assertEquals(SlotState.TRACKED, s.left.state); assertEquals(SlotState.TRACKED, s.right.state)
        assertEquals(0.12f, s.left.hand.palmX, 1e-6f); assertEquals(0.30f, s.right.hand.palmX, 1e-6f)
    }

    @Test fun anUnansweredSetupPromptExpiresButGuidanceKeepsAsking() {
        core.beginHandSetup(t, side = Side.RIGHT)
        run(HandSelector.SELECT_WINDOW_MS + 4000) { emptyList() } // the window runs from when the prompt has been said
        assertNull(core.onFrame(emptyList(), emptyList(), t).enrolling) // idle: stops asking
        lock(); core.startGuidance(t)
        run(HandSelector.SELECT_WINDOW_MS + 5000) { listOf(leftOfBottle) } // guiding: the prompt is renewed
        gesture(leftOfBottle)                                             // answers the left-hand prompt
        assertEquals(Side.LEFT, core.onFrame(listOf(leftOfBottle), emptyList(), t).guided)
        val mark = spoken.size
        run(HandSelector.SELECT_WINDOW_MS + 6000) { listOf(leftOfBottle) } // the right-hand prompt is never answered
        assertFalse(spokenSince(mark).toString(), spokenSince(mark).any { "left hand closer" in it || "too far" in it })
        assertEquals("Move right.", spoken.last())                         // guidance goes on with the left hand
    }

    @Test fun findingAnObjectNeverEnrolsAHand() {
        lock()
        run(3000) { listOf(leftOfBottle) }
        val s = core.onFrame(listOf(leftOfBottle), emptyList(), t)
        assertEquals(SlotState.NOT_ENROLLED, s.left.state); assertEquals(SlotState.NOT_ENROLLED, s.right.state)
        assertTrue(core.startGuidance(t))                  // guidance asks for the hands first...
        assertTrue(spoken.last(), spoken.last().endsWith(ReachCore.handSetup(Side.LEFT)))
        run(5000) { listOf(leftOfBottle, bystander) }      // ...and gives no direction until it has one
        assertTrue(spoken.none { it in directions })
        gesture(leftOfBottle, bystander)
        run(25_000) { listOf(leftOfBottle, bystander) }
        assertEquals("Move right.", spoken.last())         // the user's fingertip, never the bystander's ("Move left.")
        assertFalse("Move left." in spoken)
    }

    @Test fun anAmbiguousReturnPausesGuidanceAndAsksOnlyForThatHand() {
        lockAndGuide()
        run(3000) { listOf(leftOfBottle) }
        run(600) { emptyList() }
        val mark = spoken.size
        val a = hand(0.27f, 0.75f, 0.20f, tipX = 0.27f, tipY = 0.55f); val b = hand(0.34f, 0.77f, 0.20f, tipX = 0.34f, tipY = 0.55f)
        run(5000) { listOf(a, b) }
        val said = spokenSince(mark)
        assertTrue(said.toString(), "I can't be sure which is your right hand. Guidance paused. ${ReachCore.handSetup(Side.RIGHT)}" in said)
        assertTrue(said.toString(), said.none { it in directions })
    }

    @Test fun aHandBrieflyOutOfViewIsWaitedForNotReEnrolled() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        val mark = spoken.size
        run(3000) { listOf(bystander) }                        // the user's hand is gone for 3 s; a stranger is in view
        assertTrue(spokenSince(mark).toString(), "I can't see your right hand." in spokenSince(mark))
        assertTrue(spokenSince(mark).none { "fist" in it })    // no re-enrolment for a short absence
        assertEquals(SlotState.MISSING, core.onFrame(listOf(bystander), emptyList(), t).right.state)
        run(4000) { listOf(bystander) }
        assertTrue(spokenSince(mark).none { it in directions }) // the bystander's hand is never guided
    }

    @Test fun aHandGoneLongerThanTheGracePeriodIsConfirmedAgainByTheGesture() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        val mark = spoken.size
        run(HandSelector.GRACE_MS + 500) { listOf(bystander) }
        assertTrue(spokenSince(mark).toString(), spokenSince(mark).any { it.startsWith("I can't be sure which is your right hand.") })
        assertTrue(spokenSince(mark).none { it in directions })
        gesture(leftOfBottle, bystander)                          // the deliberate recovery
        run(4000) { listOf(leftOfBottle, bystander) }
        assertEquals("Move right.", spoken.last())
    }

    @Test fun anotherHandComingTooClosePausesGuidance() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        val mark = spoken.size
        val intruder = hand(0.38f, 0.75f, 0.20f, tipX = 0.38f, tipY = 0.55f) // palm 0.08 from the user's
        run(6000) { listOf(leftOfBottle, intruder) }
        val said = spokenSince(mark)
        assertTrue(said.toString(), "Another hand is close to your right hand. Paused." in said)
        assertTrue(said.toString(), said.none { it in directions })
    }

    // ---- reached and stale results ----

    @Test fun userConfirmationEndsGuidanceAndNoDirectionIsSpokenAfterIt() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        assertTrue(spoken.toString(), "Move right." in spoken)
        assertTrue(core.confirmReached())
        assertEquals(Mode.REACHED, core.mode); assertEquals("Bottle reached.", spoken.last())
        val mark = spoken.size
        run(8000) { listOf(leftOfBottle) } // hand still off to the side, target still detected: stays terminal
        assertEquals(Mode.REACHED, core.mode)
        assertEquals(emptyList<String>(), spokenSince(mark))
    }

    @Test fun lateDetectorResultsCannotRestartOrRedirectGuidanceAfterReached() {
        lockAndGuide()
        val guidingToken = core.session
        run(1000) { listOf(leftOfBottle) }
        core.confirmReached()
        val mark = spoken.size
        core.onDetections(listOf(bottle), t, guidingToken)       // started before "reached", finished after
        core.onDetections(listOf(cand(9, "bottle")), t, core.session)
        run(3000, target = false) { listOf(leftOfBottle) }
        assertEquals(Mode.REACHED, core.mode); assertEquals(emptyList<String>(), spokenSince(mark))
    }

    @Test fun aLateInterpretationAfterCompletionOrCancelIsDropped() {
        setHand(); lock()
        val pending = core.beginSearch()           // the user asks again; Claude is still thinking...
        core.onSearchResult(pending, bottle, "bottle", 0.9, false, "", t) // (this one lands)
        core.startGuidance(t); core.confirmReached()
        assertFalse(core.onSearchResult(pending, cand(2, "cup"), "cup", 0.9, false, "", t)) // ...a duplicate after reached
        assertEquals(Mode.REACHED, core.mode); assertEquals(1, core.lock.id)
        val again = core.beginSearch(); core.cancel()
        assertFalse(core.onSearchResult(again, bottle, "bottle", 0.9, false, "", t))
        assertEquals(Mode.IDLE, core.mode); assertFalse(core.lock.active)
    }

    @Test fun reachedArrivingWhileACueIsOnItsWayToTheSpeakerIsNeverFollowedByADirection() {
        // Forces the old race: the main thread confirms "reached" exactly while the camera thread is between
        // deciding on a cue and speaking it. Unlocked check-then-speak lets "Move right." out after "reached".
        var confirmer: Thread? = null
        lateinit var c: ReachCore
        c = ReachCore({ line ->
            if (line in directions && confirmer == null) {
                confirmer = Thread { c.confirmReached() }.also { it.start() }
                Thread.sleep(100)
            }
            spoken += line
        })
        setHand(c = c); lock(c); c.startGuidance(t)
        while (confirmer == null && t < 30_000) run(75, c = c) { listOf(leftOfBottle) } // this thread plays the camera
        confirmer!!.join()
        run(15_000, c = c) { listOf(leftOfBottle) }
        assertEquals(Mode.REACHED, c.mode); assertEquals(1, spoken.count { it == "Bottle reached." })
        val after = spokenSince(spoken.indexOf("Bottle reached.") + 1)
        assertTrue("spoken after reached: $after", after.none { it in directions })
    }

    @Test fun depthVerifiedContactReachesOnceAndStaysReachedDespiteFlicker() {
        core = ReachCore({ spoken += it }, depthReachedValidated = true) // as it will run once validated on the phone
        lockAndGuide(onBottle)
        run(3000, depth = DepthSample(0.60f, 0.60f, 0.02f)) { listOf(onBottle) }
        assertEquals(Mode.REACHED, core.mode)
        val mark = spoken.size
        run(1000, depth = DepthSample(Float.NaN, 0.6f)) { listOf(leftOfBottle) }  // invalid depth, misaligned
        run(1000, target = false) { emptyList() }                                // target and hand gone
        assertEquals(Mode.REACHED, core.mode)
        assertEquals(1, spoken.count { it == "Bottle reached." }); assertEquals(emptyList<String>(), spokenSince(mark))
    }

    @Test fun unvalidatedFingertipDepthNeverEndsGuidanceTheUserConfirms() {
        lockAndGuide(onBottle)
        run(6000, depth = DepthSample(0.60f, 0.60f, 0.0f)) { listOf(onBottle) }
        assertEquals(Mode.GUIDING, core.mode); assertFalse("Bottle reached." in spoken)
        assertTrue(Guidance.AT_TARGET.phrase("bottle") in spoken)
        assertTrue(core.confirmReached()); assertEquals(Mode.REACHED, core.mode)
    }

    @Test fun cameraChangeDropsTheTargetAndAnyLateResultsAndAsksForTheHandsAgain() {
        setHand(); lock()
        val search = core.beginSearch() // a request in flight when the camera changes
        val old = core.session
        core.onCameraChanged(t, "Camera changed. ")
        assertEquals(Mode.IDLE, core.mode); assertFalse(core.lock.active)
        assertEquals("Camera changed. ${ReachCore.handSetup(Side.LEFT)}", spoken.last())
        assertFalse(core.onSearchResult(search, bottle, "bottle", 0.9, false, "", t)) // late interpretation dropped
        core.onDetections(listOf(bottle), t, old)
        assertFalse(core.lock.active)                                                  // late detection dropped
        assertFalse(core.startGuidance(t))
        run(1000) { listOf(leftOfBottle) }
        assertEquals(SlotState.NOT_ENROLLED, core.onFrame(listOf(leftOfBottle), emptyList(), t).right.state)
    }

    @Test fun depthModeWithoutAMeasurableFingertipSaysSoAndKeepsDirectionGuidance() {
        val map = DepthFrame(8, 6, ShortArray(48) { 600 }, Intrinsics(500f, 500f, 320f, 240f, 640, 480), 90, floatArrayOf(1f, 0f, 1f, 0f), 0L, 0L)
        lockAndGuide()
        val end = t + 5000
        while (t < end) { // test hands have no world landmarks: the fingertip depth cannot be estimated
            if (t % 300 == 0L) core.onDetections(listOf(bottle), t, core.session)
            val s = core.onFrame(listOf(leftOfBottle), emptyList(), t, depthFrame = map)
            assertTrue(s.depthSource); t += 75
        }
        assertTrue(spoken.toString(), "Distance unavailable. Direction only." in spoken)
        assertEquals("Move right.", spoken.last { it in directions })
        assertEquals(1, spoken.count { it == "Distance unavailable. Direction only." })
    }

    @Test fun depthModeFramesWithoutADepthMapYetAreStillDepthModeAndSaySo() {
        lockAndGuide()
        val end = t + 4000
        while (t < end) {
            if (t % 300 == 0L) core.onDetections(listOf(bottle), t, core.session)
            val s = core.onFrame(listOf(leftOfBottle), emptyList(), t, depthSource = true) // ARCore: no map ready yet
            assertTrue(s.depthSource); assertEquals("no depth map yet", s.depth!!.reason); t += 75
        }
        assertTrue("Distance unavailable. Direction only." in spoken)
    }

    @Test fun invalidOrMissingDepthNeverReaches() {
        lockAndGuide(onBottle)
        run(10_000, depth = DepthSample(Float.NaN, 0.60f)) { listOf(onBottle) }
        run(10_000) { listOf(onBottle) } // 2D overlap only, fingertip dead centre for 10 s
        assertEquals(Mode.GUIDING, core.mode); assertFalse("Bottle reached." in spoken)
        assertTrue("Lined up. Reach forward slowly." in spoken)
    }

    // ---- the target through occlusion and detector misses ----

    @Test fun aHandInFrontOfTheBottleKeepsItLockedWithoutLossOrFoundAgainChatter() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        val mark = spoken.size
        run(5000, target = false) { listOf(onBottle) }      // the hand moves in front of the bottle: not detected for 5 s
        val s = core.onFrame(listOf(onBottle), emptyList(), t)
        assertEquals(Mode.GUIDING, core.mode); assertEquals(TrackStatus.OCCLUDED, s.track); assertEquals(1, s.targetId)
        run(3000) { listOf(leftOfBottle) }                   // the hand moves away; the same bottle is detected again
        val said = spokenSince(mark)
        assertTrue(said.toString(), Guidance.HAND_COVERS_TARGET.phrase("bottle") in said)
        assertTrue(said.toString(), said.none { "lost" in it || "again" in it || "anymore" in it })
        assertEquals("Move right.", said.last())
    }

    @Test fun noDirectionIsGivenFromARememberedPositionWhileTheTargetIsHidden() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        val mark = spoken.size
        run(2000, target = false) { listOf(leftOfBottle) }   // a detector miss, nothing over the bottle
        val said = spokenSince(mark)
        assertEquals(TrackStatus.TEMPORARILY_MISSING, core.onFrame(listOf(leftOfBottle), emptyList(), t).track)
        assertTrue(said.toString(), said.none { it in directions })
        assertTrue(said.toString(), said.count { it == "I can't see the bottle. Hold still." } <= 1)
    }

    @Test fun theBottleReturningUnderANewDetectorIdIsTheSameTargetAndGuidanceResumes() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        run(2500, target = false) { listOf(onBottle) }                    // long enough for the detector to forget its id
        run(3000, dets = { listOf(cand(9, "bottle")) }) { listOf(leftOfBottle) }
        assertEquals(9, core.lock.id); assertEquals(Mode.GUIDING, core.mode)
        assertEquals("Move right.", spoken.last())
    }

    @Test fun anotherBottleBesideTheHiddenOneIsNeverAdopted() {
        val other = cand(2, "bottle", box = Box(0.60f, 0.40f, 0.70f, 0.70f)) // right next to it, in view all along
        lockAndGuide()
        run(4000, dets = { listOf(bottle, other) }) { listOf(leftOfBottle) }
        run(12_000, dets = { listOf(other) }) { listOf(onBottle) }   // the target is covered; only the other is seen
        assertEquals(1, core.lock.id)
        assertEquals(Mode.TRACKING_LOST, core.mode)
        assertEquals("The bottle is blocked from view. Move your hand or the camera slightly. I'm still looking for it.",
            spoken.last())
    }

    @Test fun aTargetMissingPastEveryGraceEndsInTrackingLostAndOnlyAnExplicitRetryResumes() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        val mark = spoken.size
        run(4000, target = false) { listOf(leftOfBottle) }
        assertEquals(Mode.TRACKING_LOST, core.mode)
        assertEquals("I can't see the bottle anymore. Move the camera slowly. I'm still looking for it.", spokenSince(mark).last())
        run(3000) { listOf(leftOfBottle) } // detected again: said once, but guidance does not resume by itself
        assertEquals(Mode.TRACKING_LOST, core.mode)
        assertEquals("Found the bottle again. Say guide me to continue.", spoken.last())
        assertEquals(1, spokenSince(mark).count { it.startsWith("Found the bottle again") })
        assertTrue(core.startGuidance(t))  // deliberate retry; the hand is still the enrolled one
        run(4000) { listOf(leftOfBottle) }
        assertEquals(Mode.GUIDING, core.mode); assertEquals("Move right.", spoken.last())
    }

    // ---- turning away and back ----

    private fun runHeading(ms: Long, deg: (Long) -> Float, target: Boolean, hands: () -> List<HandResult> = { listOf(leftOfBottle) }) {
        val end = t + ms
        while (t < end) {
            core.onHeading(deg(t), t)
            if (target && t % 300 == 0L) core.onDetections(listOf(bottle), t, core.session)
            core.onFrame(hands(), emptyList(), t)
            t += 75
        }
    }

    @Test fun turningAwayPausesGuidanceKeepsTheTaskAndTurningBackResumesIt() {
        lockAndGuide()
        runHeading(3000, { 10f }, target = true)
        val mark = spoken.size
        val start = t
        runHeading(1500, { 10f + minOf(1f, (it - start) / 1000f) * 100f }, target = false) // turns ~100° to the right
        runHeading(2000, { 110f }, target = false)
        assertEquals(TurnMonitor.State.AWAY, core.onFrame(listOf(leftOfBottle), emptyList(), t).turn)
        val said = spokenSince(mark)
        val turnLine = "You may have turned away from the bottle. Turn back toward your previous direction, to your left."
        assertTrue(said.toString(), turnLine in said); assertEquals(said.toString(), 1, said.count { it == turnLine })
        assertTrue(said.toString(), said.drop(said.indexOf(turnLine)).none { it in directions })
        assertTrue(said.toString(), said.none { "anymore" in it || "blocked" in it }) // a turn is not a lost target
        assertEquals(Mode.GUIDING, core.mode); assertEquals("bottle", core.targetName); assertEquals(1, core.lock.id)
        assertEquals(HapticCue.HOLD, sent.last())                    // the band pauses too, once
        val bandMark = sent.size
        runHeading(1500, { 12f }, target = false)                     // turns back; the bottle is not detected yet
        assertTrue(spoken.toString(), "Hold there. I'm looking for the bottle." in spoken)
        runHeading(3000, { 12f }, target = true)
        assertTrue(spoken.toString(), "Found the bottle again." in spokenSince(mark))
        assertEquals("Move right.", spoken.last())
        // looking (no direction implied), found again, and only then a direction
        assertInOrder(listOf(HapticCue.SEARCH, HapticCue.REACQUIRED, HapticCue.MOVE_RIGHT), changes(bandMark))
        assertEquals(1, changes(bandMark).count { it == HapticCue.REACQUIRED })
    }

    @Test fun swayAndSensorNoiseAreNotATurn() {
        lockAndGuide()
        val rnd = java.util.Random(7)
        runHeading(15_000, { 30f * sin(it / 400.0).toFloat() + (rnd.nextFloat() - 0.5f) * 30f + if (it % 1500 == 0L) 90f else 0f },
            target = true)
        assertTrue(spoken.none { "turned away" in it }); assertEquals("Move right.", spoken.last { it in directions })
    }

    @Test fun whereWasItAnswersOnlyFromWhatIsKnown() {
        setHand(); lock()
        assertEquals("I can see the bottle now, in the middle of the camera view.", core.whereIsTarget(t))
        runHeading(1000, { 0f }, target = true)
        runHeading(3000, { -90f }, target = false)                  // turned 90° left; the bottle is out of view
        assertEquals("When I last saw the bottle, it was to the right. Turn right slowly and I'll look for it.", core.whereIsTarget(t))
        val noSensor = ReachCore({}); noSensor.onSearchResult(noSensor.beginSearch(), bottle, "bottle", 0.8, true, "", 0)
        assertTrue(noSensor.whereIsTarget(5000).contains("I can't tell which way you turned"))
        core.cancel()
        assertEquals("I'm not tracking anything right now. Tell me what to find.", core.whereIsTarget(t))
    }

    // ---- haptics ----

    @Test fun bandIn2DGivesDirectionsButNeverForwardOrNearAndFollowsReachedLostAndCancel() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        assertEquals(HapticCue.MOVE_RIGHT, sent.last())                    // the band says what the voice says
        run(4000) { listOf(onBottle) }                                      // lined up in 2D: "reach forward" is voice only
        assertNull(sent.last())
        assertTrue(sent.none { it == HapticCue.FORWARD || it?.name?.startsWith("NEAR") == true })
        assertEquals(HapticCommand.EQUAL_OR_UNCERTAIN, core.onFrame(listOf(onBottle), emptyList(), t).haptic) // 2D never picks a hand
        assertTrue(HapticCue.REACHED !in sent)                              // 2D overlap is never success
        core.confirmReached(); run(2000) { listOf(onBottle) }
        assertEquals(HapticCue.REACHED, sent.last())
        core.startGuidance(t); run(1000) { listOf(leftOfBottle) }
        run(4000, target = false) { listOf(leftOfBottle) }
        assertEquals(Mode.TRACKING_LOST, core.mode); assertEquals(HapticCue.TARGET_LOST, sent.last())
        core.cancel(); assertNull(sent.last())
        run(1000) { listOf(leftOfBottle) }
        assertNull(sent.last())
    }

    @Test fun bandSearchesWhileARequestIsInterpretedAndIsQuietOnceLocked() {
        setHand()
        val token = core.beginSearch(); run(300) { listOf(leftOfBottle) }
        assertEquals(HapticCue.SEARCH, sent.last())
        assertTrue(core.onSearchResult(token, bottle, "bottle", 0.8, true, "", t)); run(300) { listOf(leftOfBottle) }
        assertNull(sent.last())                                             // locked, waiting for "guide me": nothing implied
    }

    @Test fun forwardAndNearNeedValidDepthThisFrameAndTheFingertipLinedUp() {
        lockAndGuide(onBottle)
        run(2000, depth = DepthSample(0.60f, 0.30f)) { listOf(onBottle) }  // lined up, 30 cm short
        assertEquals(HapticCue.FORWARD, sent.last())
        run(2000, depth = DepthSample(0.60f, 0.49f)) { listOf(onBottle) }  // 11 cm
        assertEquals(HapticCue.NEAR_FAR, sent.last())
        run(2000, depth = DepthSample(0.60f, 0.53f)) { listOf(onBottle) }  // 7 cm
        assertEquals(HapticCue.NEAR_CLOSE, sent.last())
        run(2000, depth = DepthSample(0.60f, 0.57f)) { listOf(onBottle) }  // 3 cm
        assertEquals(HapticCue.NEAR_VERY_CLOSE, sent.last())
        core.onFrame(listOf(onBottle), emptyList(), t, DepthSample(Float.NaN, 0.57f)); t += 75 // depth drops out for a frame
        assertNull(sent.last())                                             // the held "near" cue is not trusted without it
        core.onFrame(listOf(onBottle), emptyList(), t, DepthSample(0.60f, 5f)); t += 75        // out of range: stale/invalid
        assertNull(sent.last())
        assertEquals(Mode.GUIDING, core.mode)                               // unvalidated depth contact never ends guidance
    }

    @Test fun validDepthWithTheFingertipOffToTheSideGivesADirectionNotProximity() {
        lockAndGuide()
        run(3000, depth = DepthSample(0.60f, 0.55f)) { listOf(leftOfBottle) } // 5 cm deep but 20% of the frame to the left
        assertEquals(HapticCue.MOVE_RIGHT, sent.last())
        assertTrue(changes().none { it == HapticCue.FORWARD || it?.name?.startsWith("NEAR") == true })
    }

    @Test fun depthReachedNeedsContactHeldOverManyValidFramesNeverOneFrame() {
        core = ReachCore({ spoken += it }, depthReachedValidated = true, haptic = { sent += it })
        lockAndGuide(onBottle)
        run(1000, depth = DepthSample(0.60f, 0.40f)) { listOf(onBottle) }
        core.onFrame(listOf(onBottle), emptyList(), t, DepthSample(0.60f, 0.60f, 0.02f)); t += 75 // a single contact frame
        assertTrue(HapticCue.REACHED !in sent); assertEquals(Mode.GUIDING, core.mode)
        run(3000, depth = DepthSample(0.60f, 0.60f, 0.02f)) { listOf(onBottle) }
        assertEquals(Mode.REACHED, core.mode); assertEquals(HapticCue.REACHED, sent.last())
        assertInOrder(listOf(HapticCue.FORWARD, HapticCue.HOLD, HapticCue.REACHED), changes()) // "stop" before "reached"
    }

    @Test fun anotherHandTooCloseHoldsTheBandOnceAndDirectionsResumeOnlyAfterThePause() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        val mark = sent.size
        val intruder = hand(0.38f, 0.75f, 0.20f, tipX = 0.38f, tipY = 0.55f)
        run(6000) { listOf(leftOfBottle, intruder) }
        assertEquals(listOf(HapticCue.HOLD), changes(mark).filterNotNull().distinct().take(1))
        assertTrue(changes(mark).toString(), changes(mark).none { it == HapticCue.MOVE_LEFT || it == HapticCue.MOVE_RIGHT })
    }

    @Test fun lostThenFoundAgainIsReacquiredWithoutADirectionUntilGuideMe() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        run(4000, target = false) { listOf(leftOfBottle) }
        assertEquals(HapticCue.TARGET_LOST, sent.last())
        val mark = sent.size
        run(3000) { listOf(leftOfBottle) }
        assertEquals(listOf(HapticCue.REACQUIRED), changes(mark))           // found again: no movement cue by itself
        assertTrue(core.startGuidance(t)); run(4000) { listOf(leftOfBottle) }
        assertEquals(HapticCue.MOVE_RIGHT, sent.last())
    }

    /** A hand whose landmarks are the projection of a 4 cm-radius world hand at depth [z] (m), centred at (cx, cy). */
    private fun depthHand(cx: Float, cy: Float, z: Float): HandResult {
        val world = List(21) { i -> val a = 2 * Math.PI * i / 21; Lm((0.04 * cos(a)).toFloat(), (0.04 * sin(a)).toFloat(), 0f) }
        val img = world.map { Lm(cx + 500f * it.x / z / 640f, cy + 500f * it.y / z / 480f) }
        return HandResult.fromLandmarks(img, 0.9f, null, world)
    }

    @Test fun withMeasuredDepthTheReliablyCloserHandIsSignalledAndAnUnseenHandIsNeverFarther() {
        val frame = DepthFrame(64, 48, ShortArray(64 * 48) { 600 }, Intrinsics(500f, 500f, 320f, 240f, 640, 480), 0,
            floatArrayOf(1f, 0f, 1f, 0f), 0L, 0L)
        lateinit var last: ReachSnapshot
        fun go(ms: Long, hands: () -> List<HandResult>) {
            val end = t + ms
            while (t < end) {
                if (t % 300 == 0L) core.onDetections(listOf(bottle), t, core.session)
                last = core.onFrame(hands(), emptyList(), t, depthFrame = frame); t += 75
            }
        }
        var l = depthHand(0.20f, 0.85f, 0.40f); val r = depthHand(0.80f, 0.85f, 0.40f)
        fun posed(h: HandResult, p: Pose) = hand(h.palmX, h.palmY, h.handSize, pose = p)
        core.beginHandSetup(t)
        go(450) { listOf(posed(l, Pose.OPEN)) }; go(450) { listOf(posed(l, Pose.FIST)) }
        go(450) { listOf(l, posed(r, Pose.OPEN)) }; go(450) { listOf(l, posed(r, Pose.FIST)) }
        lock(); core.startGuidance(t)
        go(2000) { listOf(l, r) }                                   // both about as far: no hand is picked
        assertEquals(HapticCommand.EQUAL_OR_UNCERTAIN, last.haptic)
        val s0 = core.onFrame(listOf(l, r), emptyList(), t, depthFrame = frame)
        assertNotNull(s0.leftM); assertNotNull(s0.rightM)
        for (i in 1..20) { l = depthHand(0.20f + i * 0.0125f, 0.85f - i * 0.0115f, 0.40f + i * 0.0075f); go(75) { listOf(l, r) } }
        go(1000) { listOf(l, r) }                                   // the left fingertip is now ~10 cm from it, the right ~27
        assertEquals(HapticCommand.LEFT_HAND_CLOSER, last.haptic)
        assertTrue(spoken.toString(), "Your left hand is closer. Guiding your left hand." in spoken)
        go(1000) { listOf(l) }                                      // the right hand leaves view: unknown, not "farther"
        assertEquals(HapticCommand.EQUAL_OR_UNCERTAIN, last.haptic)
        assertTrue(core.confirmReached()); go(300) { listOf(l) }
        assertEquals(HapticCommand.REACHED, last.haptic); assertEquals(HapticCue.REACHED, sent.last())
    }

    // ---- searches ----

    @Test fun cancelledOrSupersededSearchResultsAreDropped() {
        val first = core.beginSearch()
        core.cancel()
        assertFalse(core.onSearchResult(first, bottle, "bottle", 0.9, false, "", t))
        assertEquals(Mode.IDLE, core.mode); assertEquals(listOf("Reset."), spoken)

        val a = core.beginSearch(); val b = core.beginSearch()
        assertFalse(core.onSearchResult(a, bottle, "bottle", 0.9, false, "", t))
        assertTrue(core.onSearchResult(b, cand(2, "laptop"), "laptop", 0.9, false, "", t))
        assertEquals(Mode.TARGET_LOCKED, core.mode); assertEquals(2, core.lock.id)
    }

    @Test fun staleDetectionFromBeforeANewLockIsNotApplied() {
        val tokA = core.beginSearch(); core.onSearchResult(tokA, bottle, "bottle", 0.9, true, "", 0)
        val oldSession = core.session
        val tokB = core.beginSearch(); core.onSearchResult(tokB, cand(2, "laptop"), "laptop", 0.9, true, "", 0)
        val moved = Candidate(2, "laptop", "unknown", Box(0.05f, 0.05f, 0.15f, 0.15f), 0.9f, null)
        core.onDetections(listOf(moved), 100, oldSession)
        assertEquals(0.5f, core.lock.box!!.centerX(), 1e-6f)
    }

    @Test fun cancelDuringGuidanceSilencesIt() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        core.cancel()
        val mark = spoken.size
        run(5000) { listOf(leftOfBottle) }
        assertEquals(Mode.IDLE, core.mode); assertEquals(emptyList<String>(), spokenSince(mark))
    }

    @Test fun onlySettledUnambiguousDetectionsAreLockedAndUncertainOnesAreHedged() {
        fun lock(c: Candidate, name: String): Boolean = core.onSearchResult(core.beginSearch(), c, name, 0.9, true, "", 0)
            .also { assertTrue(it) }.let { core.mode == Mode.TARGET_LOCKED }

        assertFalse(lock(cand(1, "laptop", confirmed = false), "laptop"))            // one frame is not enough
        assertTrue(spoken.last(), "only saw" in spoken.last())
        assertFalse(lock(cand(2, "bottle", conflict = "handbag"), "bottle"))          // bottle or bag? say so
        assertEquals("I can't tell if that's a bottle or a handbag. Move the camera a little and ask again.", spoken.last())
        assertTrue(lock(cand(3, "bottle", conflict = "bottle"), "coke"))              // drink container is a coke candidate
        assertTrue(lock(cand(4, "laptop", score = 0.45f), "laptop"))                  // settled but weak: hedged
        assertTrue(spoken.last(), spoken.last().startsWith("I might see the laptop, but I'm not sure.") && "percent" !in spoken.last())
        assertTrue(lock(cand(5, "laptop", score = 0.9f, lowLight = true), "laptop"))  // dark scene: never certain
        assertTrue(spoken.last(), "because it's dark" in spoken.last())
        assertTrue(lock(cand(6, "laptop", score = 0.9f), "laptop"))
        assertTrue(spoken.last(), spoken.last().startsWith("Found the laptop, 90 percent sure."))
        assertFalse(lock(cand(7, "person"), "person"))                                // people are never targets
        assertEquals("I only help find objects, not people.", spoken.last())
    }

    /** Before: a candidate of another class (Claude's pick, or "laptop bag" -> laptop) was locked as what was asked for. */
    @Test fun aRequestIsNeverLockedOntoAnotherClassOrAnUnsupportedCategory() {
        assertTrue(core.onSearchResult(core.beginSearch(), cand(1, "handbag"), "bottle", 0.9, false, "", t)) // handled: by refusing
        assertEquals("That looks like a handbag to me, not a bottle. Ask again, or move the camera for a better view.", spoken.last())
        assertEquals(Mode.IDLE, core.mode); assertFalse(core.lock.active)
        core.onSearchResult(core.beginSearch(), cand(2, "laptop"), "laptop bag", 0.9, false, "", t)
        assertTrue(spoken.last(), spoken.last().startsWith("I can't tell a laptop bag from other bags."))
        assertEquals(Mode.IDLE, core.mode); assertFalse(core.lock.active)
        core.onSearchResult(core.beginSearch(), cand(3, "cup"), "clock", 0.9, false, "", t)
        assertTrue(spoken.last(), spoken.last().startsWith("I can't recognise a clock yet."))
        assertFalse(core.lock.active)
        assertTrue(core.lockOn(cand(4, "laptop"), "computer", 0.9, false, t))
        assertTrue(core.lockOn(cand(5, "bottle"), "water bottle", 0.9, false, t))
    }

    /** Before: a weak "laptop" in a dark frame was locked (hedged) and guided to. */
    @Test fun aWeakDetectionInTheDarkIsNeverLocked() {
        assertFalse(core.lockOn(cand(1, "laptop", score = 0.45f, lowLight = true), "laptop", 0.45, true, t))
        assertEquals("It's too dark for me to tell if that's a laptop. Turn on a light or move closer, and ask again.", spoken.last())
        assertEquals(Mode.IDLE, core.mode); assertFalse(core.lock.active)
    }

    /** Before: lockOn stamped the box with the answer time, so a box seconds old guided as if just measured. */
    @Test fun aBoxMeasuredBeforeASlowAnswerIsNeverUsedAsFresh() {
        setHand()
        val old = bottle.copy(frameMs = t)
        t += 3000 // the interpretation took 3 s; the bottle has not been detected since
        assertTrue(core.onSearchResult(core.beginSearch(), old, "bottle", 0.9, false, "", t))
        assertNull(core.lock.boxAt(t))
        assertTrue(core.startGuidance(t))
        val s = core.onFrame(listOf(leftOfBottle), emptyList(), t)
        assertNotEquals(TrackStatus.TRACKING, s.track)
        assertEquals(0f, core.ex, 0f) // the engine never saw the old box
        // seen 200 ms before the answer: still a measurement
        assertTrue(core.lockOn(bottle.copy(id = 9, frameMs = t - 200), "bottle", 0.9, false, t))
        assertNotNull(core.lock.boxAt(t))
    }

    @Test fun retryAfterReachedNeedsTheUserToAskAgain() {
        lockAndGuide()
        run(1000) { listOf(leftOfBottle) }
        core.confirmReached()
        assertFalse(core.confirmReached()) // already reached: no second confirmation
        assertTrue(core.startGuidance(t))  // "guide me" again is deliberate
        assertEquals(Mode.GUIDING, core.mode)
    }

    // ---- registered objects: identity is a photo match for one track, never a tracker id ----

    private fun lockRegistered() {
        setHand()
        assertTrue(core.onSearchResult(core.beginSearch(), bottle, "bottle", 0.9, true, "", t,
            prefix = "The bottle in the middle looks like your blue bottle from its saved photo. ", registered = "blue bottle"))
        assertEquals("blue bottle", core.identity); assertNull(core.identityCheckDue())
        assertTrue(spoken.last(), spoken.last().startsWith("The bottle in the middle looks like your blue bottle from its saved photo. Found"))
        assertTrue(core.startGuidance(t))
        run(4000) { listOf(leftOfBottle) }
        assertEquals("Move right.", spoken.last())
    }
    private val sameSpotNewId = cand(5, "bottle")

    @Test fun aRegisteredTargetOnANewTrackPausesUntilItsPhotoMatchesAgain() {
        lockRegistered()
        val before = spoken.size
        run(300, dets = { listOf(sameSpotNewId) }) { listOf(leftOfBottle) } // re-detected under a new tracker id
        assertEquals(5, core.lock.id); assertEquals("blue bottle" to 5, core.identityCheckDue())
        val mark = spoken.size
        run(3000, dets = { listOf(sameSpotNewId) }) { listOf(leftOfBottle) }
        val s = core.onFrame(listOf(leftOfBottle), emptyList(), t)
        assertFalse(s.identityVerified); assertEquals(Guidance.IDENTITY_CHECK, s.cue)
        assertTrue(spokenSince(mark).toString(), spokenSince(mark).none { it in directions })
        assertTrue(spokenSince(before).toString(), "Checking this is your blue bottle. Hold still." in spokenSince(before))
        assertEquals(HapticCommand.EQUAL_OR_UNCERTAIN, s.haptic); assertEquals(HapticCue.HOLD, s.band) // paused: the band holds

        core.onIdentityCheck("blue bottle", 5, IdMatch.Match(sameSpotNewId, 0.93f))
        assertEquals("This still looks like your blue bottle. Continuing.", spoken.last())
        assertNull(core.identityCheckDue())
        run(4000, dets = { listOf(sameSpotNewId) }) { listOf(leftOfBottle) }
        assertEquals("Move right.", spoken.last())
    }

    @Test fun anotherObjectMatchingThePhotoBetterDropsTheTarget() {
        lockRegistered()
        run(300, dets = { listOf(sameSpotNewId) }) { listOf(leftOfBottle) }
        core.onIdentityCheck("blue bottle", 5, IdMatch.Match(cand(9, "bottle"), 0.9f))
        assertEquals("Another bottle looks more like your blue bottle than this one. I've stopped. " +
            "Say find my blue bottle to look again, or tell me which one.", spoken.last())
        assertEquals(Mode.IDLE, core.mode); assertFalse(core.lock.active); assertNull(core.identity)
        val mark = spoken.size
        run(2000, dets = { listOf(sameSpotNewId) }) { listOf(leftOfBottle) }
        assertTrue(spokenSince(mark).none { it in directions })
    }

    @Test fun repeatedUnsurePhotoMatchesDropTheTargetAndStaleOrMissingChecksDoNot() {
        lockRegistered()
        run(300, dets = { listOf(sameSpotNewId) }) { listOf(leftOfBottle) }
        val unsure = IdMatch.Unsure(listOf(sameSpotNewId), 0.6f)
        core.onIdentityCheck("blue bottle", 1, IdMatch.Match(bottle, 0.9f)) // for the old track: ignored
        core.onIdentityCheck("blue bottle", 5, IdMatch.NotInView)           // nothing to compare: not a failure
        repeat(ReachCore.ID_CHECKS - 1) { core.onIdentityCheck("blue bottle", 5, unsure) }
        assertEquals(Mode.GUIDING, core.mode); assertEquals("blue bottle" to 5, core.identityCheckDue())
        core.onIdentityCheck("blue bottle", 5, unsure)
        assertEquals(Mode.IDLE, core.mode); assertNull(core.identity)
        assertTrue(spoken.last(), spoken.last().startsWith("I can't confirm this is your blue bottle from its photo."))
    }

    @Test fun anOrdinaryTargetIsNeverCalledTheUsersOwn() {
        lock()
        assertNull(core.identity); assertNull(core.identityCheckDue())
        assertFalse(spoken.last(), "your" in spoken.last())
    }
}
