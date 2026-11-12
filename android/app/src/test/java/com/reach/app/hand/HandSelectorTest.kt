package com.reach.app.hand

import com.reach.app.hand.HandSelector.SlotState.*
import com.reach.app.hand.HandSelector.Status
import org.junit.Assert.*
import org.junit.Test

/**
 * A hand whose palm centre is (x, y), wrist->middle knuckle = size. OPEN / FIST place the index..little tips and
 * middle joints like a real open hand / fist; OTHER leaves the fingers neutral with the index tip at (tipX, tipY).
 */
fun hand(x: Float, y: Float, size: Float, tipX: Float = x, tipY: Float = y - size, pose: Pose = Pose.OTHER): HandResult {
    val lm = MutableList(21) { Lm(x, y) }
    lm[0] = Lm(x, y + size / 2); lm[9] = Lm(x, y - size / 2)
    if (pose == Pose.OTHER) lm[8] = Lm(tipX, tipY)
    else for ((tip, pip) in listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)) {
        lm[pip] = Lm(x, y - size)                                                  // 1.5 size from the wrist
        lm[tip] = Lm(x, if (pose == Pose.OPEN) y - 1.6f * size else y - 0.2f * size) // 2.1 (open) / 0.7 (fist)
    }
    return HandResult.fromLandmarks(lm, 0.9f)
}

/** Same hand with MediaPipe's handedness label (the rear camera's label is not the user's side; tests use it as-is). */
fun HandResult.labelled(label: String?, score: Float = 0.95f) = copy(handedness = label, score = score)

/** Frames every 75 ms (~13 fps on the A15). */
class HandSelectorTest {
    private var sel = HandSelector()
    private var t = 0L

    // Single-hand scenarios (the old behaviour, now one slot): an unlabelled hand at (0.30, 0.70).
    private fun user(pose: Pose = Pose.OTHER, dx: Float = 0f) = hand(0.30f + dx, 0.70f, 0.20f, pose = pose)
    private fun other(pose: Pose = Pose.OTHER, dx: Float = 0f) = hand(0.75f + dx, 0.30f, 0.20f, pose = pose)

    // Two-hand scenarios. The labels are deliberately the mirrored ones MediaPipe gives a rear camera: they are only
    // compared with what each hand had at enrolment, never mapped to a side.
    private fun leftHand(x: Float = 0.30f, y: Float = 0.70f, pose: Pose = Pose.OTHER, size: Float = 0.20f) =
        hand(x, y, size, pose = pose).labelled("Right")
    private fun rightHand(x: Float = 0.70f, y: Float = 0.70f, pose: Pose = Pose.OTHER, size: Float = 0.20f) =
        hand(x, y, size, pose = pose).labelled("Left")

    private fun feed(ms: Long, hands: () -> List<HandResult>) {
        val end = t + ms
        while (t < end) { sel.update(hands(), t); t += 75 }
    }

    private fun state(side: Side) = sel.slot(side).state

    /** The user answers the prompt for [side]: [h] open, then a fist held long enough. */
    private fun enroll(side: Side, h: (Pose) -> HandResult, vararg extra: HandResult) {
        sel.beginEnrollment(side, t)
        feed(450) { listOf(h(Pose.OPEN)) + extra }
        feed(450) { listOf(h(Pose.FIST)) + extra }
    }

    private fun enrollBoth() {
        enroll(Side.LEFT, { leftHand(pose = it) })
        enroll(Side.RIGHT, { rightHand(pose = it) }, leftHand(pose = Pose.FIST)) // the left stays in view, still a fist
        feed(150) { listOf(leftHand(), rightHand()) }
        assertEquals(TRACKED, state(Side.LEFT)); assertEquals(TRACKED, state(Side.RIGHT))
    }

    @Test fun poseIsReadFromTheFingerJoints() {
        assertEquals(Pose.OPEN, hand(0.5f, 0.5f, 0.2f, pose = Pose.OPEN).pose)
        assertEquals(Pose.FIST, hand(0.5f, 0.5f, 0.2f, pose = Pose.FIST).pose)
        assertEquals(Pose.OTHER, hand(0.5f, 0.5f, 0.2f).pose)
    }

    // ---- enrolment ----

    @Test fun nothingIsEnrolledWithoutTheDeliberateGesture() {
        sel.beginEnrollment(Side.RIGHT, t)
        feed(5000) { listOf(user()) }                     // a raised hand alone is not enough
        assertEquals(Status.CONFIRM, sel.status)
        feed(2000) { listOf(user(Pose.FIST)) }            // a fist that never opened after the prompt
        assertEquals(NOT_ENROLLED, state(Side.RIGHT)); assertEquals(HandResult.NONE, sel.hand(Side.RIGHT))
    }

    @Test fun aHandThatWasOpenBeforeThePromptAndClosesAfterItHasNotAnsweredIt() {
        feed(450) { listOf(user(Pose.OPEN)) }  // open before the prompt (e.g. someone gesturing anyway)
        sel.beginEnrollment(Side.RIGHT, t)
        feed(2000) { listOf(user(Pose.FIST)) } // closes right after: no open hand was seen after the prompt
        assertEquals(NOT_ENROLLED, state(Side.RIGHT))
        enroll(Side.RIGHT, { user(it) })         // a real answer: open, then close
        assertEquals(TRACKED, state(Side.RIGHT))
    }

    @Test fun aGestureLongAfterThePromptIsNotAnAnswerToIt() {
        sel.beginEnrollment(Side.RIGHT, t)
        feed(HandSelector.SELECT_WINDOW_MS + 500) { listOf(other()) } // nobody answers; a hand is just in view
        assertEquals(Status.NONE, sel.status); assertNull(sel.enrolling)
        feed(450) { listOf(user(Pose.OPEN)) }; feed(450) { listOf(user(Pose.FIST)) }
        assertEquals(NOT_ENROLLED, state(Side.RIGHT))
    }

    @Test fun theAnswerWindowRunsFromWhenALongIntroductionHasBeenHeard() {
        sel.beginEnrollment(Side.RIGHT, t, heardAtMs = t + 25_000)       // the prompt ends a 25 s introduction
        feed(HandSelector.SELECT_WINDOW_MS + 3000) { listOf(other()) }  // past 20 s from the start: still waiting
        assertEquals(Side.RIGHT, sel.enrolling)
        feed(450) { listOf(user(Pose.OPEN), other()) }; feed(450) { listOf(user(Pose.FIST), other()) }
        assertEquals(TRACKED, state(Side.RIGHT))
    }

    @Test fun theHandThatOpensAndClosesOnCueIsEnrolledEvenWithABystanderInView() {
        enroll(Side.RIGHT, { user(it) }, other(Pose.OPEN)) // someone else's open hand is in view the whole time
        assertEquals(TRACKED, state(Side.RIGHT))
        sel.update(listOf(other(Pose.OPEN), user()), t)
        assertEquals(0.30f, sel.hand(Side.RIGHT).palmX, 1e-6f); assertEquals(1, sel.others.size)
    }

    @Test fun bigCloseOrFirstListedHandsAreNotAssumedToBeTheUsers() {
        val near = hand(0.70f, 0.40f, 0.35f) // biggest, nearest, first in MediaPipe's list; never makes the gesture
        enroll(Side.RIGHT, { user(it) }, near)
        sel.update(listOf(near, user()), t)
        assertEquals(0.30f, sel.hand(Side.RIGHT).palmX, 1e-6f)
    }

    @Test fun twoHandsMakingTheGestureAreAmbiguousAndNobodyIsEnrolled() {
        sel.beginEnrollment(Side.RIGHT, t)
        feed(450) { listOf(user(Pose.OPEN), other(Pose.OPEN)) }
        feed(450) { listOf(user(Pose.FIST), other(Pose.FIST)) }
        assertEquals(Status.AMBIGUOUS, sel.status); assertEquals(NOT_ENROLLED, state(Side.RIGHT))
        feed(450) { listOf(user(Pose.OPEN), other(Pose.FIST)) } // asked again: only the user opens and closes now
        feed(450) { listOf(user(Pose.FIST), other(Pose.FIST)) }
        assertEquals(TRACKED, state(Side.RIGHT))
    }

    @Test fun tooSmallHandsAreNotReadForTheGesture() {
        sel.beginEnrollment(Side.RIGHT, t)
        feed(450) { listOf(hand(0.5f, 0.5f, 0.04f, pose = Pose.OPEN)) }
        feed(900) { listOf(hand(0.5f, 0.5f, 0.04f, pose = Pose.FIST)) }
        assertEquals(Status.TOO_FAR, sel.status); assertEquals(NOT_ENROLLED, state(Side.RIGHT))
    }

    @Test fun bothHandsAreEnrolledSeparatelyAndTrackedIndependently() {
        enrollBoth()
        assertEquals("Right", sel.slot(Side.LEFT).label); assertEquals("Left", sel.slot(Side.RIGHT).label) // as MediaPipe said
        var flip = false
        repeat(30) { i ->
            val l = leftHand(x = 0.30f + i * 0.004f); val r = rightHand(y = 0.70f - i * 0.004f)
            flip = !flip
            sel.update(if (flip) listOf(r, l) else listOf(l, r), t); t += 75 // MediaPipe's order is no identity
            assertEquals(l.palmX, sel.hand(Side.LEFT).palmX, 1e-6f); assertEquals(r.palmY, sel.hand(Side.RIGHT).palmY, 1e-6f)
            assertEquals(TRACKED, state(Side.LEFT)); assertEquals(TRACKED, state(Side.RIGHT))
        }
        assertNotEquals(sel.slot(Side.LEFT).trackId, sel.slot(Side.RIGHT).trackId)
    }

    @Test fun theEnrolledLeftHandRepeatingTheGestureCannotBecomeTheRightHand() {
        enroll(Side.LEFT, { leftHand(pose = it) })
        sel.beginEnrollment(Side.RIGHT, t)
        feed(450) { listOf(leftHand(pose = Pose.OPEN)) }; feed(900) { listOf(leftHand(pose = Pose.FIST)) }
        assertEquals(NOT_ENROLLED, state(Side.RIGHT)); assertEquals(Side.RIGHT, sel.enrolling)
        assertEquals(TRACKED, state(Side.LEFT))
        assertEquals(Status.SHOW, sel.status) // "raise your right hand", not "too far": the left hand is no answer
    }

    @Test fun aHandLabelledLikeTheOtherEnrolledHandIsRefused() {
        enroll(Side.LEFT, { leftHand(pose = it) })
        val stranger = { p: Pose -> hand(0.70f, 0.70f, 0.20f, pose = p).labelled("Right") } // same label as the left hand
        sel.beginEnrollment(Side.RIGHT, t)
        feed(450) { listOf(leftHand(), stranger(Pose.OPEN)) }; feed(450) { listOf(leftHand(), stranger(Pose.FIST)) }
        assertEquals(Status.SAME_AS_OTHER, sel.status); assertEquals(NOT_ENROLLED, state(Side.RIGHT))
        feed(450) { listOf(leftHand(), rightHand(pose = Pose.OPEN)) }; feed(450) { listOf(leftHand(), rightHand(pose = Pose.FIST)) }
        assertEquals(TRACKED, state(Side.RIGHT))
    }

    @Test fun allFiveTipsComeFromTheEnrolledHand() {
        val far = hand(0.75f, 0.30f, 0.05f, tipX = 0.9f, tipY = 0.1f)
        enroll(Side.RIGHT, { user(it) }, far)
        val u = hand(0.30f, 0.70f, 0.20f, tipX = 0.31f, tipY = 0.48f)
        sel.update(listOf(far, u), t)
        assertSame(u, sel.hand(Side.RIGHT)); assertEquals(0.31f, sel.hand(Side.RIGHT).index!!.x, 0f)
    }

    // ---- bystanders ----

    @Test fun aBystanderHandEnteringWhileBothAreTrackedIsIgnored() {
        enrollBoth()
        val by = hand(0.50f, 0.30f, 0.18f).labelled("Left") // same label as the right hand
        feed(3000) { listOf(by, leftHand(), rightHand()) }
        assertEquals(TRACKED, state(Side.LEFT)); assertEquals(TRACKED, state(Side.RIGHT))
        assertEquals(0.70f, sel.hand(Side.RIGHT).palmX, 1e-6f); assertEquals(listOf(by), sel.others)
    }

    @Test fun aBystanderSeenWhileTheUsersHandWasVisibleIsNeverAdoptedAsIt() {
        enrollBoth()
        val by = { x: Float -> hand(x, 0.50f, 0.20f).labelled("Left") } // same label and size as the right hand
        feed(600) { listOf(leftHand(), rightHand(), by(0.50f)) }        // in view together: a different hand
        feed(3000) { listOf(leftHand(), by(0.50f)) }                    // the right hand leaves; the bystander stays...
        for (i in 0 until 20) { sel.update(listOf(leftHand(), by(0.50f + i * 0.01f)), t); t += 75 } // ...and moves to its spot
        assertEquals(MISSING, state(Side.RIGHT)); assertEquals(HandResult.NONE, sel.hand(Side.RIGHT))
        assertEquals(TRACKED, state(Side.LEFT))
    }

    @Test fun whileTheUsersHandIsMissingABystanderAlreadyBeingTrackedCannotTakeItsPlace() {
        enroll(Side.RIGHT, { user(it) }, hand(0.75f, 0.30f, 0.05f))
        for (i in 0 until 20) { // the user's hand drops out; the bystander's hand (own track) moves to where it was
            val b = hand(0.75f - i * 0.0225f, 0.30f + i * 0.02f, 0.05f + i * 0.0075f)
            sel.update(listOf(b), t); t += 75
            assertEquals(HandResult.NONE, sel.hand(Side.RIGHT))
        }
        assertEquals(MISSING, state(Side.RIGHT))
    }

    @Test fun aHandWithTheOtherLabelAppearingWhereTheUsersVanishedIsNotAdopted() {
        enrollBoth()
        feed(1200) { listOf(leftHand()) } // the right hand leaves
        val stranger = rightHand().labelled("Right") // same place and size, confidently labelled like the LEFT hand
        feed(HandSelector.GRACE_MS) { listOf(leftHand(), stranger) }
        assertNotEquals(TRACKED, state(Side.RIGHT)); assertEquals(TRACKED, state(Side.LEFT))
    }

    // ---- leaving and returning ----

    @Test fun briefLossIsToleratedAndTheSameHandIsReacquiredByContinuity() {
        enroll(Side.RIGHT, { user(it) })
        feed(900) { emptyList() }; assertEquals(MISSING, state(Side.RIGHT))
        val back = hand(0.33f, 0.68f, 0.19f)
        sel.update(listOf(back), t)
        assertSame(back, sel.hand(Side.RIGHT)); assertEquals(TRACKED, state(Side.RIGHT))
    }

    @Test fun oneHandLeavingAndReturningElsewhereIsReacquiredByItsLabelAndTheOtherIsUntouched() {
        enrollBoth()
        val leftTrack = sel.slot(Side.LEFT).trackId
        feed(4000) { listOf(leftHand()) } // the right hand is out of view for 4 s: identity kept, no re-enrolment
        assertEquals(MISSING, state(Side.RIGHT)); assertTrue(sel.slot(Side.RIGHT).missingMs(t) >= 3900)
        val back = rightHand(x = 0.55f, y = 0.85f, size = 0.22f) // comes back somewhere else
        feed(150) { listOf(leftHand(), back) }
        assertEquals(MISSING, state(Side.RIGHT))                   // followed a moment before it is adopted
        feed(300) { listOf(leftHand(), back) }
        assertEquals(TRACKED, state(Side.RIGHT)); assertEquals(0.55f, sel.hand(Side.RIGHT).palmX, 1e-6f)
        assertEquals(TRACKED, state(Side.LEFT)); assertEquals(leftTrack, sel.slot(Side.LEFT).trackId)
    }

    @Test fun withoutAConfidentLabelAtEnrolmentALongGapIsNeverBridged() {
        enroll(Side.RIGHT, { user(it).labelled("Left", score = 0.7f) })
        assertNull(sel.slot(Side.RIGHT).label)
        feed(1500) { emptyList() }
        feed(3000) { listOf(user().labelled("Left", score = 0.95f)) } // same place, but after the short gap
        assertEquals(MISSING, state(Side.RIGHT))
    }

    @Test fun twoHandsThatCouldBeTheReturningOneNeedRecoveryForThatSideOnly() {
        enrollBoth()
        val leftTrack = sel.slot(Side.LEFT).trackId
        feed(1500) { listOf(leftHand()) }
        val a = rightHand(x = 0.60f, y = 0.85f); val b = rightHand(x = 0.85f, y = 0.55f)
        feed(150) { listOf(leftHand(), a, b) }
        assertEquals(NEEDS_RECOVERY, state(Side.RIGHT)); assertTrue(sel.slot(Side.RIGHT).reason, "more than one" in sel.slot(Side.RIGHT).reason)
        feed(2000) { listOf(leftHand(), a) }                  // one leaves: still not guessed
        assertEquals(NEEDS_RECOVERY, state(Side.RIGHT))
        assertEquals(TRACKED, state(Side.LEFT)); assertEquals(leftTrack, sel.slot(Side.LEFT).trackId)
        // Deliberate recovery: the right hand alone makes the gesture.
        sel.beginEnrollment(Side.RIGHT, t)
        feed(450) { listOf(leftHand(), rightHand(x = 0.60f, y = 0.85f, pose = Pose.OPEN)) }
        feed(450) { listOf(leftHand(), rightHand(x = 0.60f, y = 0.85f, pose = Pose.FIST)) }
        assertEquals(TRACKED, state(Side.RIGHT)); assertEquals(leftTrack, sel.slot(Side.LEFT).trackId)
    }

    @Test fun twoHandsAtTheSpotOfABrieflyMissingHandNeedRecovery() {
        enroll(Side.RIGHT, { user(it) })
        feed(600) { emptyList() }
        val a = hand(0.27f, 0.70f, 0.20f); val b = hand(0.34f, 0.72f, 0.20f) // both within the continuity gate
        sel.update(listOf(a, b), t); t += 75
        assertEquals(NEEDS_RECOVERY, state(Side.RIGHT))
        feed(2000) { listOf(a) }; assertEquals(NEEDS_RECOVERY, state(Side.RIGHT)) // and stays so
    }

    @Test fun aLowConfidenceOrDifferentSizedReturnIsNotEnough() {
        fun selectThenHide() { enroll(Side.RIGHT, { user(it) }); feed(300) { listOf(user()) }; feed(600) { emptyList() } }
        selectThenHide()
        feed(300) { listOf(user().copy(score = 0.5f)) }  // unsure detection
        assertEquals(MISSING, state(Side.RIGHT))
        sel = HandSelector(); t = 0; selectThenHide()
        feed(300) { listOf(hand(0.30f, 0.70f, 0.30f)) }  // 1.5x the size: a nearer hand
        assertEquals(MISSING, state(Side.RIGHT))
        sel = HandSelector(); t = 0; selectThenHide()
        feed(300) { listOf(hand(0.48f, 0.70f, 0.20f)) }  // 0.18 from where it should be
        assertEquals(MISSING, state(Side.RIGHT))
    }

    @Test fun theUsersHandReturningAlongItsOwnMotionPathIsReacquired() {
        fun moveHideReturn(): Boolean {
            enroll(Side.RIGHT, { user(it) })
            var x = 0.30f // moving right fast: 0.035 per frame
            repeat(10) { sel.update(listOf(hand(x, 0.70f, 0.20f)), t); t += 75; x += 0.035f }
            assertEquals(TRACKED, state(Side.RIGHT))
            repeat(4) { sel.update(emptyList(), t); t += 75; x += 0.035f } // 300 ms behind something
            assertEquals(MISSING, state(Side.RIGHT))
            // It reappears 0.175 further on: beyond the reacquire gate from where it was last seen, on its predicted path.
            sel.update(listOf(hand(x, 0.70f, 0.20f)), t)
            return sel.hand(Side.RIGHT).present
        }
        assertTrue(moveHideReturn()); assertEquals(TRACKED, state(Side.RIGHT))
        sel = HandSelector(HandSelector.Config(maxPredictMs = 0)); t = 0 // control: without motion prediction
        assertFalse(moveHideReturn())
    }

    @Test fun twoFramesWithTheSameTimestampDoNotThrowTheMotionPredictionOff() {
        enroll(Side.RIGHT, { user(it) })
        sel.update(listOf(hand(0.50f, 0.70f, 0.20f)), t)   // a palm jump of 0.2 ...
        sel.update(listOf(hand(0.30f, 0.70f, 0.20f)), t)   // ...and back, at the same timestamp (dt 0)
        feed(1000) { listOf(user()) }
        assertEquals(TRACKED, state(Side.RIGHT)); assertEquals(0.30f, sel.hand(Side.RIGHT).palmX, 1e-6f)
    }

    @Test fun anEnrolledHandOutOfViewPastTheGracePeriodNeedsRecoveryAndTheGraceIsConfigurable() {
        sel = HandSelector(HandSelector.Config(graceMs = 3000))
        enrollBoth()
        feed(2900) { listOf(leftHand()) }; assertEquals(MISSING, state(Side.RIGHT))
        feed(300) { listOf(leftHand()) }
        assertEquals(NEEDS_RECOVERY, state(Side.RIGHT)); assertEquals(TRACKED, state(Side.LEFT))
        feed(2000) { listOf(leftHand(), rightHand()) }                 // it returns: still not guessed
        assertEquals(NEEDS_RECOVERY, state(Side.RIGHT))
    }

    @Test fun theLastSeenPositionIsKeptForDiagnosticsWhileMissing() {
        enroll(Side.RIGHT, { user(it) })
        feed(450) { emptyList() }
        assertEquals(HandResult.NONE, sel.hand(Side.RIGHT))              // nothing to guide with
        assertEquals(0.30f, sel.slot(Side.RIGHT).last!!.palmX, 1e-6f)    // but where it was is known, for the overlay
        assertTrue(sel.slot(Side.RIGHT).missingMs(t) in 300..525)
        feed(75) { listOf(user()) }
        assertEquals(0L, sel.slot(Side.RIGHT).missingMs(t))
    }

    // ---- hands close together ----

    @Test fun theUsersTwoHandsCrossingAreToldApartAgainByTheirLabels() {
        enrollBoth()
        val seen = HashSet<HandSelector.SlotState>()
        for (i in 0..14) { sel.update(listOf(leftHand(), rightHand(x = 0.70f - i * 0.025f)), t); t += 75; seen += state(Side.RIGHT) }
        assertTrue(seen.toString(), UNCERTAIN in seen); assertEquals(UNCERTAIN, state(Side.LEFT))
        assertEquals(HandResult.NONE, sel.hand(Side.RIGHT))              // nothing guided while they could swap
        for (i in 14 downTo 0) { sel.update(listOf(leftHand(), rightHand(x = 0.70f - i * 0.025f)), t); t += 75 }
        assertEquals(TRACKED, state(Side.LEFT)); assertEquals(TRACKED, state(Side.RIGHT))
        assertEquals(0.70f, sel.hand(Side.RIGHT).palmX, 1e-6f); assertEquals(0.30f, sel.hand(Side.LEFT).palmX, 1e-6f)
    }

    @Test fun aSameLabelledHandComingTooCloseMakesOnlyThatSideNeedRecovery() {
        enrollBoth()
        val by = { x: Float -> hand(x, 0.70f, 0.20f).labelled("Left") } // labelled like the right hand
        for (i in 0..12) { sel.update(listOf(leftHand(), rightHand(), by(0.95f - i * 0.016f)), t); t += 75 }
        assertEquals(UNCERTAIN, state(Side.RIGHT)); assertEquals(TRACKED, state(Side.LEFT))
        for (i in 12 downTo 0) { sel.update(listOf(leftHand(), rightHand(), by(0.95f - i * 0.016f)), t); t += 75 }
        assertEquals(NEEDS_RECOVERY, state(Side.RIGHT)); assertEquals(TRACKED, state(Side.LEFT))
    }
}
