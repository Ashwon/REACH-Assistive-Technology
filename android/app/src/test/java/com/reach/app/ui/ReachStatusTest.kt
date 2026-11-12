package com.reach.app.ui

import com.reach.app.HandView
import com.reach.app.Mode
import com.reach.app.ReachSnapshot
import com.reach.app.grounding.Box
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.TrackStatus
import com.reach.app.haptic.HapticCommand
import com.reach.app.haptic.HapticCue
import com.reach.app.hand.HandResult
import com.reach.app.hand.HandSelector
import com.reach.app.hand.HandSelector.SlotState
import com.reach.app.hand.Side
import com.reach.app.hand.hand
import com.reach.app.spatial.DepthReading
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.TurnMonitor
import org.junit.Assert.*
import org.junit.Test

class ReachStatusTest {
    private val userHand = hand(0.5f, 0.6f, 0.2f)
    private fun cand(label: String, score: Float, id: Int = 1, confirmed: Boolean = true) =
        Candidate(id, label, "unknown", Box(0.4f, 0.4f, 0.6f, 0.7f), score, null, confirmed)

    /** [right]: the state of the guided (right) hand; the left one is TRACKED unless given. */
    private fun snap(mode: Mode, track: TrackStatus = TrackStatus.NONE, right: SlotState = SlotState.NOT_ENROLLED,
                     cue: Guidance = Guidance.NONE, cands: List<Candidate> = emptyList(), target: String = "bottle",
                     others: List<HandResult> = emptyList(), left: SlotState = SlotState.TRACKED,
                     enrolling: Side? = null, enroll: HandSelector.Status = HandSelector.Status.NONE,
                     turn: TurnMonitor.State = TurnMonitor.State.UNKNOWN, turnDeg: Float? = null,
                     haptic: HapticCommand = HapticCommand.STOP, leftM: Float? = null, rightM: Float? = null,
                     depthSource: Boolean = false) =
        ReachSnapshot(mode, if (mode == Mode.IDLE) "" else target, track, if (track == TrackStatus.NONE) -1 else 1, cands,
            if (right == SlotState.TRACKED) userHand else HandResult.NONE, cue,
            HandView(Side.LEFT, left), HandView(Side.RIGHT, right, missingMs = 2300, hand = if (right == SlotState.TRACKED) userHand else HandResult.NONE),
            Side.RIGHT, enrolling, enroll, others, depthSource, if (depthSource) DepthReading(null, 0.6f, null, null, "fingertip depth not measurable") else null,
            leftM, rightM, turn, turnDeg, haptic)

    private val live = CameraState.LIVE

    @Test fun guidingOnlyWhileTheEngineIsGuidingWithAUsableHand() {
        for (m in Mode.values()) {
            val text = ReachStatus.status(snap(m, TrackStatus.TRACKING, SlotState.TRACKED, Guidance.LEFT), live).text
            assertEquals("$m", m == Mode.GUIDING, text == "Guiding")
        }
        assertEquals("Waiting for your right hand", ReachStatus.status(snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.MISSING), live).text)
        assertEquals("Paused · hand uncertain", ReachStatus.status(snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.UNCERTAIN), live).text)
        for (hs in listOf(SlotState.NOT_ENROLLED, SlotState.NEEDS_RECOVERY))
            assertEquals("$hs", "Paused · confirm your hand", ReachStatus.status(snap(Mode.GUIDING, TrackStatus.TRACKING, hs), live).text)
        assertEquals("Paused · confirm your hand", ReachStatus.status(snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.TRACKED,
            enrolling = Side.RIGHT, enroll = HandSelector.Status.CONFIRM), live).text)
    }

    @Test fun anUnverifiedRegisteredTargetShowsTheCheckNotGuidingOrDetected() {
        val g = snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.TRACKED, Guidance.IDENTITY_CHECK).copy(identity = "work laptop", identityVerified = false)
        assertEquals("Paused · checking it's your work laptop", ReachStatus.status(g, live).text)
        assertEquals("Checking it's your work laptop", ReachStatus.status(g.copy(mode = Mode.TARGET_LOCKED), live).text)
        assertEquals("Guiding", ReachStatus.status(g.copy(identityVerified = true, cue = Guidance.LEFT), live).text)
    }

    @Test fun reachedOnlyInReached() {
        for (m in Mode.values()) {
            // Even an engine REACHED cue does not show "Reached" until the core has entered REACHED.
            val text = ReachStatus.status(snap(m, TrackStatus.TRACKING, SlotState.TRACKED, Guidance.REACHED), live).text
            assertEquals("$m", m == Mode.REACHED, text == "Reached")
        }
    }

    @Test fun trackingLostSaysWhatWasLostAndHowToRetry() {
        val target = snap(Mode.TRACKING_LOST, TrackStatus.LOST, SlotState.TRACKED)
        assertEquals(Line("Target lost", Tone.WARN), ReachStatus.status(target, live))
        assertEquals("I lost the bottle", ReachStatus.guidance(target, live).headline)
        assertTrue("Start Guidance" in ReachStatus.guidance(target, live).detail)
    }

    @Test fun cameraProblemsOverrideEverything() {
        val s = snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.TRACKED, Guidance.LEFT)
        assertEquals("Starting camera", ReachStatus.status(s, CameraState.STARTING).text)
        assertEquals("Camera unavailable", ReachStatus.status(s, CameraState.UNAVAILABLE).text)
        assertEquals("Camera unavailable", ReachStatus.guidance(s, CameraState.UNAVAILABLE).headline)
    }

    @Test fun theTargetsTrackingStateIsShownHonestly() {
        assertEquals(Line("Bottle detected", Tone.GOOD), ReachStatus.status(snap(Mode.TARGET_LOCKED, TrackStatus.TRACKING), live))
        assertEquals("Bottle detected", ReachStatus.status(snap(Mode.TARGET_LOCKED, TrackStatus.OCCLUDED), live).text)
        assertEquals(Tone.WARN, ReachStatus.status(snap(Mode.TARGET_LOCKED, TrackStatus.LOST), live).tone)
        assertEquals("Paused · target hidden", ReachStatus.status(snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.TRACKED, Guidance.TARGET_LOST), live).text)
        assertEquals("Paused · target covered", ReachStatus.status(snap(Mode.GUIDING, TrackStatus.OCCLUDED, SlotState.TRACKED), live).text)
        assertEquals("Bottle covered by a hand · still locked", ReachStatus.objects(snap(Mode.GUIDING, TrackStatus.OCCLUDED))!!.text)
        assertEquals("Bottle briefly hidden · still locked", ReachStatus.objects(snap(Mode.GUIDING, TrackStatus.TEMPORARILY_MISSING))!!.text)
        assertEquals("Bottle out of view · still remembered", ReachStatus.objects(snap(Mode.GUIDING, TrackStatus.LOST))!!.text)
    }

    @Test fun guidanceCardShowsTheEnginesCueVerbatimAndLabels2D() {
        val g = ReachStatus.guidance(snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.TRACKED, Guidance.RIGHT), live)
        assertEquals("Move right.", g.headline); assertTrue(g.directionOnly)
        assertTrue(g.detail, "right hand" in g.detail)
        assertEquals("Hold your right hand out in front of the camera.",
            ReachStatus.guidance(snap(Mode.GUIDING, TrackStatus.TRACKING), live).headline) // no cue yet, no hand
        assertEquals("I can't see your right hand.",
            ReachStatus.guidance(snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.MISSING, Guidance.HAND_LOST), live).headline)
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

    @Test fun handChipShowsBothEnrolledHandsOrTheEnrolmentStep() {
        assertEquals(Line("Left: tracked · Right: tracked", Tone.GOOD), ReachStatus.hand(snap(Mode.IDLE, right = SlotState.TRACKED)))
        assertEquals("Left: tracked · Right: tracked · other hand ignored",
            ReachStatus.hand(snap(Mode.IDLE, right = SlotState.TRACKED, others = listOf(hand(0.8f, 0.2f, 0.05f))))!!.text)
        assertEquals("Left: tracked · Right: out of view 2s", ReachStatus.hand(snap(Mode.IDLE, right = SlotState.MISSING))!!.text)
        assertEquals(Line("Left: tracked · Right: confirm again", Tone.WARN), ReachStatus.hand(snap(Mode.IDLE, right = SlotState.NEEDS_RECOVERY)))
        assertEquals("Left: not set · Right: not set", ReachStatus.hand(snap(Mode.IDLE, left = SlotState.NOT_ENROLLED))!!.text)
        assertEquals("Make a fist with your left hand",
            ReachStatus.hand(snap(Mode.IDLE, enrolling = Side.LEFT, enroll = HandSelector.Status.CONFIRM))!!.text)
        assertEquals("That looks like your other hand · show your right hand",
            ReachStatus.hand(snap(Mode.IDLE, enrolling = Side.RIGHT, enroll = HandSelector.Status.SAME_AS_OTHER))!!.text)
    }

    @Test fun distancesAreShownOnlyWhenMeasuredAndAlwaysAsEstimates() {
        assertNull(ReachStatus.distance(snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.TRACKED, leftM = 0.3f)))   // 2D mode
        assertEquals("Left 30 cm · Right unknown · estimate",
            ReachStatus.distance(snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.TRACKED, leftM = 0.3f, depthSource = true))!!.text)
        assertEquals("Distance unavailable · fingertip depth not measurable",
            ReachStatus.distance(snap(Mode.GUIDING, TrackStatus.TRACKING, SlotState.TRACKED, depthSource = true))!!.text)
    }

    @Test fun turnAndVibrationIndicatorsFollowRealState() {
        assertNull(ReachStatus.turn(snap(Mode.GUIDING, TrackStatus.TRACKING, turn = TurnMonitor.State.ALIGNED)))
        assertEquals("Turned away · turn left", ReachStatus.turn(snap(Mode.GUIDING, TrackStatus.LOST, turn = TurnMonitor.State.AWAY, turnDeg = -80f))!!.text)
        assertEquals("Paused · turned away", ReachStatus.status(snap(Mode.GUIDING, TrackStatus.LOST, SlotState.TRACKED, turn = TurnMonitor.State.AWAY), live).text)
        // Never a cue while live haptics are off, whatever the core requested; the reason is shown instead.
        val moving = snap(Mode.GUIDING).copy(band = HapticCue.MOVE_RIGHT)
        assertEquals("Vibration: not connected", ReachStatus.haptics(moving, "not connected").text)
        assertEquals("Vibration: motor test needed", ReachStatus.haptics(moving, "motor test needed").text)
        assertEquals("Vibration: move right", ReachStatus.haptics(moving, null).text)
        assertEquals("Vibration: connected", ReachStatus.haptics(snap(Mode.TARGET_LOCKED), null).text)
        assertEquals("Vibration: on · lined up, reach forward by voice", ReachStatus.haptics(snap(Mode.GUIDING, cue = Guidance.LINED_UP_2D), null).text)
        assertEquals("Vibration: on · up/down is by voice", ReachStatus.haptics(snap(Mode.GUIDING, cue = Guidance.UP), null).text)
        assertEquals("Vibration: on · waiting for a direction", ReachStatus.haptics(snap(Mode.GUIDING, haptic = HapticCommand.RIGHT_HAND_CLOSER), null).text)
    }
}
