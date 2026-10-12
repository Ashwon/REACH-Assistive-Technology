package com.reach.app.ui

import com.reach.app.Mode
import com.reach.app.ReachSnapshot
import com.reach.app.grounding.Box
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.TrackStatus
import com.reach.app.hand.HandResult
import com.reach.app.hand.HandSelector.Status
import com.reach.app.hand.hand
import com.reach.app.spatial.Guidance
import org.junit.Assert.*
import org.junit.Test

class ReachStatusTest {
    private val userHand = hand(0.5f, 0.6f, 0.2f)
    private fun cand(label: String, score: Float, id: Int = 1, confirmed: Boolean = true) =
        Candidate(id, label, "unknown", Box(0.4f, 0.4f, 0.6f, 0.7f), score, null, confirmed)
    private fun snap(mode: Mode, track: TrackStatus = TrackStatus.NONE, hs: Status = Status.NONE,
                     cue: Guidance = Guidance.NONE, cands: List<Candidate> = emptyList(), target: String = "bottle",
                     others: List<HandResult> = emptyList()) =
        ReachSnapshot(mode, if (mode == Mode.IDLE) "" else target, track, if (track == TrackStatus.NONE) -1 else 1, cands,
            if (hs == Status.TRACKING) userHand else HandResult.NONE, cue, hs, others)

    private val live = CameraState.LIVE

    @Test fun guidingOnlyWhileTheEngineIsGuidingWithTheSelectedHand() {
        for (m in Mode.values()) {
            val text = ReachStatus.status(snap(m, TrackStatus.TRACKING, Status.TRACKING, Guidance.LEFT), live).text
            assertEquals("$m", m == Mode.GUIDING, text == "Guiding")
        }
        assertEquals("Waiting for your hand", ReachStatus.status(snap(Mode.GUIDING, TrackStatus.TRACKING, Status.MISSING), live).text)
        for (hs in listOf(Status.NONE, Status.SHOW, Status.TOO_FAR, Status.CONFIRM, Status.AMBIGUOUS))
            assertEquals("$hs", "Paused · confirm your hand", ReachStatus.status(snap(Mode.GUIDING, TrackStatus.TRACKING, hs), live).text)
    }

    @Test fun reachedOnlyInReached() {
        for (m in Mode.values()) {
            // Even an engine REACHED cue does not show "Reached" until the core has entered REACHED.
            val text = ReachStatus.status(snap(m, TrackStatus.TRACKING, Status.TRACKING, Guidance.REACHED), live).text
            assertEquals("$m", m == Mode.REACHED, text == "Reached")
        }
    }

    @Test fun trackingLostSaysWhatWasLostAndHowToRetry() {
        val target = snap(Mode.TRACKING_LOST, TrackStatus.LOST, Status.TRACKING)
        assertEquals(Line("Target lost", Tone.WARN), ReachStatus.status(target, live))
        assertEquals("I lost the bottle", ReachStatus.guidance(target, live).headline)
        assertTrue("Start Guidance" in ReachStatus.guidance(target, live).detail)
    }

    @Test fun cameraProblemsOverrideEverything() {
        val s = snap(Mode.GUIDING, TrackStatus.TRACKING, Status.TRACKING, Guidance.LEFT)
        assertEquals("Starting camera", ReachStatus.status(s, CameraState.STARTING).text)
        assertEquals("Camera unavailable", ReachStatus.status(s, CameraState.UNAVAILABLE).text)
        assertEquals("Camera unavailable", ReachStatus.guidance(s, CameraState.UNAVAILABLE).headline)
    }

    @Test fun lockedAndLostTargets() {
        assertEquals(Line("Bottle detected", Tone.GOOD), ReachStatus.status(snap(Mode.TARGET_LOCKED, TrackStatus.TRACKING), live))
        assertEquals("Bottle detected", ReachStatus.status(snap(Mode.TARGET_LOCKED, TrackStatus.HOLDING), live).text)
        assertEquals(Tone.WARN, ReachStatus.status(snap(Mode.TARGET_LOCKED, TrackStatus.LOST), live).tone)
        assertEquals("Target lost", ReachStatus.status(snap(Mode.GUIDING, TrackStatus.TRACKING, Status.TRACKING, Guidance.TARGET_LOST), live).text)
        assertEquals("Bottle out of view", ReachStatus.objects(snap(Mode.GUIDING, TrackStatus.LOST))!!.text)
    }

    @Test fun guidanceCardShowsTheEnginesCueVerbatimAndLabels2D() {
        val g = ReachStatus.guidance(snap(Mode.GUIDING, TrackStatus.TRACKING, Status.TRACKING, Guidance.RIGHT), live)
        assertEquals("Move right.", g.headline); assertTrue(g.directionOnly)
        assertEquals("Hold your hand out in front of the camera.",
            ReachStatus.guidance(snap(Mode.GUIDING, TrackStatus.TRACKING), live).headline) // no cue yet, no hand
        assertFalse(ReachStatus.guidance(snap(Mode.TARGET_LOCKED, TrackStatus.TRACKING), live).directionOnly)
    }

    @Test fun recognisedObjectsAreOnlySettledOnesAndUncertainOnesSayMaybe() {
        assertNull(ReachStatus.objects(null)); assertNull(ReachStatus.hand(null))
        assertEquals("No objects recognised", ReachStatus.objects(snap(Mode.IDLE))!!.text)
        val many = listOf(cand("chair", 0.7f, 1), cand("bottle", 0.9f, 2), cand("bottle", 0.6f, 3), cand("cup", 0.65f, 4))
        assertEquals("Seeing bottle, chair +1", ReachStatus.objects(snap(Mode.IDLE, cands = many))!!.text)
        val dark = listOf(cand("laptop", 0.42f, 1), cand("cup", 0.9f, 2, confirmed = false))
        assertEquals("Seeing maybe laptop", ReachStatus.objects(snap(Mode.IDLE, cands = dark))!!.text)
    }

    @Test fun handChipFollowsTheSelector() {
        assertEquals("Your hand tracked", ReachStatus.hand(snap(Mode.IDLE, hs = Status.TRACKING))!!.text)
        assertEquals("Your hand tracked · other hand ignored",
            ReachStatus.hand(snap(Mode.IDLE, hs = Status.TRACKING, others = listOf(hand(0.8f, 0.2f, 0.05f))))!!.text)
        assertEquals("Make a fist to confirm your hand", ReachStatus.hand(snap(Mode.IDLE, hs = Status.CONFIRM))!!.text)
        assertEquals("More than one hand · confirm again", ReachStatus.hand(snap(Mode.IDLE, hs = Status.AMBIGUOUS))!!.text)
        // A visible hand that never made the gesture is not "your hand".
        assertEquals("Hand not set · tap the hand button", ReachStatus.hand(snap(Mode.IDLE, others = listOf(userHand)))!!.text)
        assertEquals("Hand not set", ReachStatus.hand(snap(Mode.IDLE))!!.text)
    }
}
