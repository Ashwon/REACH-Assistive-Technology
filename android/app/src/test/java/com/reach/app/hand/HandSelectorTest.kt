package com.reach.app.hand

import com.reach.app.hand.HandSelector.Status.*
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

/** Frames every 75 ms (~13 fps on the A15). */
class HandSelectorTest {
    private val sel = HandSelector()
    private var t = 0L

    private fun user(pose: Pose = Pose.OTHER, dx: Float = 0f) = hand(0.30f + dx, 0.70f, 0.20f, pose = pose)
    private fun other(pose: Pose = Pose.OTHER, dx: Float = 0f) = hand(0.75f + dx, 0.30f, 0.20f, pose = pose)

    private fun feed(ms: Long, hands: () -> List<HandResult>): HandResult {
        var out = HandResult.NONE
        val end = t + ms
        while (t < end) { out = sel.update(hands(), t); t += 75 }
        return out
    }

    /** The user answers the prompt: open hand, then a fist held long enough. */
    private fun gesture(vararg extra: HandResult) {
        feed(450) { listOf(user(Pose.OPEN)) + extra }
        feed(450) { listOf(user(Pose.FIST)) + extra }
    }

    @Test fun poseIsReadFromTheFingerJoints() {
        assertEquals(Pose.OPEN, hand(0.5f, 0.5f, 0.2f, pose = Pose.OPEN).pose)
        assertEquals(Pose.FIST, hand(0.5f, 0.5f, 0.2f, pose = Pose.FIST).pose)
        assertEquals(Pose.OTHER, hand(0.5f, 0.5f, 0.2f).pose)
    }

    @Test fun nothingIsSelectedWithoutTheDeliberateGesture() {
        sel.beginSelection(t)
        assertEquals(HandResult.NONE, feed(5000) { listOf(user()) })      // a raised hand alone is not enough
        assertEquals(CONFIRM, sel.status)
        assertEquals(HandResult.NONE, feed(2000) { listOf(user(Pose.FIST)) }) // a fist that never opened after the prompt
        assertFalse(sel.selected)
    }

    @Test fun aHandThatWasOpenBeforeThePromptAndClosesAfterItHasNotAnsweredIt() {
        feed(450) { listOf(user(Pose.OPEN)) }  // open before the prompt (e.g. someone gesturing anyway)
        sel.beginSelection(t)
        feed(2000) { listOf(user(Pose.FIST)) } // closes right after: no open hand was seen after the prompt
        assertFalse(sel.selected)
        gesture()                              // a real answer: open, then close
        assertTrue(sel.selected)
    }

    @Test fun aGestureLongAfterThePromptIsNotAnAnswerToIt() {
        sel.beginSelection(t)
        feed(HandSelector.SELECT_WINDOW_MS + 500) { listOf(other()) } // nobody answers; a hand is just in view
        assertEquals(NONE, sel.status)
        gesture()                                                    // someone opens and closes a hand later
        assertFalse(sel.selected)
    }

    @Test fun theHandThatOpensAndClosesOnCueIsSelectedEvenWithABystanderInView() {
        sel.beginSelection(t)
        gesture(other(Pose.OPEN))                       // someone else's open hand is in view the whole time
        assertEquals(TRACKING, sel.status)
        val got = feed(75) { listOf(other(Pose.OPEN), user()) }
        assertEquals(0.30f, got.palmX, 1e-6f); assertEquals(1, sel.others.size)
    }

    @Test fun bigCloseOrFirstListedHandsAreNotAssumedToBeTheUsers() {
        sel.beginSelection(t)
        val near = hand(0.70f, 0.40f, 0.35f) // biggest, nearest, first in MediaPipe's list; never makes the gesture
        gesture(near)
        assertEquals(0.30f, feed(75) { listOf(near, user()) }.palmX, 1e-6f)
    }

    @Test fun twoHandsMakingTheGestureAreAmbiguousAndNobodyIsSelected() {
        sel.beginSelection(t)
        feed(450) { listOf(user(Pose.OPEN), other(Pose.OPEN)) }
        feed(450) { listOf(user(Pose.FIST), other(Pose.FIST)) }
        assertEquals(AMBIGUOUS, sel.status); assertFalse(sel.selected)
        gesture(other(Pose.FIST))                       // asked again: only the user opens and closes now
        assertEquals(TRACKING, sel.status)
    }

    @Test fun selectionSurvivesMediaPipeReorderingTheHands() {
        sel.beginSelection(t); gesture()
        var flip = false
        repeat(40) {
            val u = user(dx = it * 0.004f); val o = other(dx = -it * 0.004f)
            flip = !flip
            assertEquals(u.palmX, sel.update(if (flip) listOf(o, u) else listOf(u, o), t).palmX, 1e-6f); t += 75
            assertEquals(TRACKING, sel.status)
        }
    }

    @Test fun briefLossIsToleratedAndTheSameHandIsReacquiredByContinuity() {
        sel.beginSelection(t); gesture()
        assertEquals(HandResult.NONE, feed(900) { emptyList() }); assertEquals(MISSING, sel.status)
        val back = hand(0.33f, 0.68f, 0.19f)
        assertSame(back, feed(75) { listOf(back) }); assertEquals(TRACKING, sel.status)
    }

    @Test fun longLossDropsTheSelectionAndAStrangerAtTheSameSpotIsNeverTakenOver() {
        sel.beginSelection(t); gesture()
        val seen = ArrayList<HandSelector.Status>()
        repeat(((HandSelector.LOSE_MS + 300) / 75).toInt()) { sel.update(emptyList(), t); t += 75; seen += sel.status }
        assertTrue(seen.toString(), LOST in seen); assertEquals(HandSelector.Lost.GONE, sel.lost)
        assertEquals(NONE, sel.status) // LOST lasts one frame: the caller pauses guidance / asks again on it
        assertEquals(HandResult.NONE, feed(3000) { listOf(user()) }) // same place, same size: still not selected
        assertFalse(sel.selected)
    }

    @Test fun whileTheUsersHandIsMissingABystanderAlreadyBeingTrackedCannotTakeItsPlace() {
        sel.beginSelection(t); gesture(hand(0.75f, 0.30f, 0.05f))
        for (i in 0 until 20) { // the user's hand drops out; the bystander's hand (own track) moves to where it was
            val b = hand(0.75f - i * 0.0225f, 0.30f + i * 0.02f, 0.05f + i * 0.0075f)
            assertEquals(HandResult.NONE, sel.update(listOf(b), t)); t += 75
        }
        assertEquals(MISSING, sel.status)
    }

    @Test fun anotherHandComingTooCloseDropsTheSelectionInsteadOfRiskingASwap() {
        sel.beginSelection(t); gesture()
        for (i in 0 until 20) {
            sel.update(listOf(user(), hand(0.60f - i * 0.02f, 0.70f, 0.20f)), t); t += 75
            if (sel.status != TRACKING) break
        }
        assertEquals(LOST, sel.status); assertEquals(HandSelector.Lost.TOO_CLOSE, sel.lost)
    }

    @Test fun allFiveTipsComeFromTheSelectedHand() {
        sel.beginSelection(t); gesture(hand(0.75f, 0.30f, 0.05f, tipX = 0.9f, tipY = 0.1f))
        val u = hand(0.30f, 0.70f, 0.20f, tipX = 0.31f, tipY = 0.48f)
        val got = feed(75) { listOf(hand(0.75f, 0.30f, 0.05f, tipX = 0.9f, tipY = 0.1f), u) }
        assertSame(u, got); assertEquals(u.tips, got.tips); assertEquals(0.31f, got.index!!.x, 0f)
    }

    @Test fun tooSmallHandsAreNotReadForTheGesture() {
        sel.beginSelection(t)
        feed(450) { listOf(hand(0.5f, 0.5f, 0.04f, pose = Pose.OPEN)) }
        feed(900) { listOf(hand(0.5f, 0.5f, 0.04f, pose = Pose.FIST)) }
        assertEquals(TOO_FAR, sel.status); assertFalse(sel.selected)
    }
}
