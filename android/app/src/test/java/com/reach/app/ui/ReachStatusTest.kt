package com.reach.app.ui

import com.reach.app.Mode
import com.reach.app.ReachSnapshot
import com.reach.app.grounding.Box
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.TrackStatus
import com.reach.app.hand.HandResult
import com.reach.app.hand.Lm
import com.reach.app.spatial.Guidance
import org.junit.Assert.*
import org.junit.Test

class ReachStatusTest {
    private val wearerHand = HandResult.fromLandmarks(List(21) { Lm(0.5f, it / 40f) }, 0.9f) // wrist->MCP 0.225
    private val farHand = HandResult.fromLandmarks(List(21) { Lm(0.5f, it / 400f) }, 0.9f)   // 0.0225
    private fun cand(label: String, score: Float, id: Int = 1) = Candidate(id, label, "unknown", Box(0.4f, 0.4f, 0.6f, 0.7f), score, null)
    private fun snap(mode: Mode, track: TrackStatus = TrackStatus.NONE, hand: HandResult = HandResult.NONE,
                     cue: Guidance = Guidance.NONE, cands: List<Candidate> = emptyList(), target: String = "bottle") =
        ReachSnapshot(mode, if (mode == Mode.IDLE) "" else target, track, if (track == TrackStatus.NONE) -1 else 1, cands, hand, cue)

    private val live = CameraState.LIVE

    @Test fun guidingOnlyWhileTheEngineIsGuidingWithATrackedHand() {
        for (m in Mode.values()) {
            val text = ReachStatus.status(snap(m, TrackStatus.TRACKING, wearerHand, Guidance.LEFT), live).text
            assertEquals("$m", m == Mode.REACHING, text == "Guiding")
        }
        assertEquals("Waiting for your hand", ReachStatus.status(snap(Mode.REACHING, TrackStatus.TRACKING), live).text)
        assertEquals("Waiting for your hand", ReachStatus.status(snap(Mode.REACHING, TrackStatus.TRACKING, farHand), live).text)
    }

    @Test fun reachedOnlyInDone() {
        for (m in Mode.values()) {
            // Even an engine REACHED cue does not show "Reached" until the controller has entered DONE.
            val text = ReachStatus.status(snap(m, TrackStatus.TRACKING, wearerHand, Guidance.REACHED), live).text
            assertEquals("$m", m == Mode.DONE, text == "Reached")
        }
    }

    @Test fun cameraProblemsOverrideEverything() {
        val s = snap(Mode.REACHING, TrackStatus.TRACKING, wearerHand, Guidance.LEFT)
        assertEquals("Starting camera", ReachStatus.status(s, CameraState.STARTING).text)
        assertEquals("Camera unavailable", ReachStatus.status(s, CameraState.UNAVAILABLE).text)
        assertEquals("Camera unavailable", ReachStatus.guidance(s, CameraState.UNAVAILABLE).headline)
    }

    @Test fun lockedAndLostTargets() {
        assertEquals(Line("Bottle detected", Tone.GOOD), ReachStatus.status(snap(Mode.TARGET_LOCKED, TrackStatus.TRACKING), live))
        assertEquals("Bottle detected", ReachStatus.status(snap(Mode.TARGET_LOCKED, TrackStatus.HOLDING), live).text)
        assertEquals(Tone.WARN, ReachStatus.status(snap(Mode.TARGET_LOCKED, TrackStatus.LOST), live).tone)
        assertEquals("Target lost", ReachStatus.status(snap(Mode.REACHING, TrackStatus.TRACKING, wearerHand, Guidance.TARGET_LOST), live).text)
        assertEquals("Bottle out of view", ReachStatus.objects(snap(Mode.REACHING, TrackStatus.LOST))!!.text)
    }

    @Test fun guidanceCardShowsTheEnginesCueVerbatimAndLabels2D() {
        val g = ReachStatus.guidance(snap(Mode.REACHING, TrackStatus.TRACKING, wearerHand, Guidance.RIGHT), live)
        assertEquals("Move right.", g.headline); assertTrue(g.directionOnly)
        assertEquals("Hold your hand out in front of you.",
            ReachStatus.guidance(snap(Mode.REACHING, TrackStatus.TRACKING), live).headline) // no cue yet, no hand
        assertFalse(ReachStatus.guidance(snap(Mode.TARGET_LOCKED, TrackStatus.TRACKING), live).directionOnly)
    }

    @Test fun recognisedObjectsAndHandChips() {
        assertNull(ReachStatus.objects(null)); assertNull(ReachStatus.hand(null))
        assertEquals("No objects recognised", ReachStatus.objects(snap(Mode.IDLE))!!.text)
        val many = listOf(cand("chair", 0.5f, 1), cand("bottle", 0.9f, 2), cand("bottle", 0.6f, 3), cand("cup", 0.4f, 4))
        assertEquals("Seeing bottle, chair +1", ReachStatus.objects(snap(Mode.IDLE, cands = many))!!.text)
        assertEquals("Hand tracked", ReachStatus.hand(snap(Mode.IDLE, hand = wearerHand))!!.text)
        assertEquals("Hand too far away", ReachStatus.hand(snap(Mode.IDLE, hand = farHand))!!.text)
        assertEquals("No hand in view", ReachStatus.hand(snap(Mode.IDLE))!!.text)
    }
}
