package com.reach.app.ui

import com.reach.app.Mode
import com.reach.app.ReachSnapshot
import com.reach.app.grounding.TrackStatus
import com.reach.app.hand.HandSelector
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.phrase

/**
 * Turns the controller's real per-frame state into what the screen says. Pure Kotlin (JVM-tested):
 * "Guiding" only exists in GUIDING and "Reached" only in REACHED, which the core enters only on
 * depth-verified contact or the user's own confirmation.
 */
enum class Tone { NEUTRAL, ACTIVE, GOOD, WARN }

enum class CameraState { STARTING, LIVE, UNAVAILABLE }

data class Line(val text: String, val tone: Tone)

data class GuidanceView(val headline: String, val detail: String, val directionOnly: Boolean)

object ReachStatus {

    fun status(s: ReachSnapshot?, camera: CameraState): Line = when {
        camera == CameraState.STARTING -> Line("Starting camera", Tone.NEUTRAL)
        camera == CameraState.UNAVAILABLE -> Line("Camera unavailable", Tone.WARN)
        s == null -> Line("Camera ready", Tone.NEUTRAL)
        else -> when (s.mode) {
            Mode.IDLE -> Line("Camera ready", Tone.NEUTRAL)
            Mode.SEARCHING -> Line("Searching", Tone.ACTIVE)
            Mode.TARGET_LOCKED ->
                if (s.track == TrackStatus.LOST) Line("Target lost", Tone.WARN) else Line("${name(s)} detected", Tone.GOOD)
            Mode.GUIDING -> when {
                s.track == TrackStatus.LOST || s.cue == Guidance.TARGET_LOST -> Line("Target lost", Tone.WARN)
                s.handStatus == HandSelector.Status.MISSING -> Line("Waiting for your hand", Tone.WARN)
                s.handStatus != HandSelector.Status.TRACKING -> Line("Paused · confirm your hand", Tone.WARN)
                else -> Line("Guiding", Tone.ACTIVE)
            }
            Mode.REACHED -> Line("Reached", Tone.GOOD)
            Mode.TRACKING_LOST -> Line("Target lost", Tone.WARN)
        }
    }

    /** Recognised objects, or the locked target's tracking state. Null = nothing to show yet. */
    fun objects(s: ReachSnapshot?): Line? {
        if (s == null) return null
        if (s.targetId >= 0) return when (s.track) {
            TrackStatus.TRACKING -> Line("${name(s)} in view", Tone.GOOD)
            TrackStatus.HOLDING -> Line("${name(s)} briefly hidden", Tone.NEUTRAL)
            TrackStatus.LOST -> Line("${name(s)} out of view", Tone.WARN)
            TrackStatus.NONE -> null
        }
        // Only settled detections; anything the model is unsure of is said as "maybe".
        val seen = s.candidates.filter { it.confirmed }.sortedByDescending { it.score }
        val labels = seen.map { c -> if (seen.any { it.label == c.label && it.sure }) c.label else "maybe ${c.label}" }.distinct()
        return when {
            labels.isEmpty() -> Line("No objects recognised", Tone.NEUTRAL)
            labels.size <= 2 -> Line("Seeing ${labels.joinToString(", ")}", Tone.NEUTRAL)
            else -> Line("Seeing ${labels.take(2).joinToString(", ")} +${labels.size - 2}", Tone.NEUTRAL)
        }
    }

    /** The user's hand: chosen by a deliberate gesture (HandSelector), never by position or detection order. */
    fun hand(s: ReachSnapshot?): Line? = if (s == null) null else when (s.handStatus) {
        HandSelector.Status.TRACKING ->
            Line(if (s.otherHands.isEmpty()) "Your hand tracked" else "Your hand tracked · other hand ignored", Tone.GOOD)
        HandSelector.Status.MISSING -> Line("Your hand briefly lost", Tone.NEUTRAL)
        HandSelector.Status.SHOW -> Line("Raise your hand", Tone.ACTIVE)
        HandSelector.Status.TOO_FAR -> Line("Hand too far away", Tone.NEUTRAL)
        HandSelector.Status.CONFIRM -> Line("Make a fist to confirm your hand", Tone.ACTIVE)
        HandSelector.Status.AMBIGUOUS -> Line("More than one hand · confirm again", Tone.WARN)
        HandSelector.Status.LOST -> Line("Hand lost", Tone.WARN)
        HandSelector.Status.NONE -> Line(if (s.otherHands.isEmpty()) "Hand not set" else "Hand not set · tap the hand button", Tone.NEUTRAL)
    }

    fun guidance(s: ReachSnapshot?, camera: CameraState): GuidanceView = when {
        camera == CameraState.UNAVAILABLE -> GuidanceView("Camera unavailable", "Guidance needs the camera.", false)
        s == null || s.mode == Mode.IDLE -> GuidanceView("Ready to help", "Say or type an object, like “bottle”.", false)
        s.mode == Mode.SEARCHING -> GuidanceView("Looking for it…", "Keep the camera pointed at the area.", false)
        s.mode == Mode.TARGET_LOCKED ->
            if (s.track == TrackStatus.LOST) GuidanceView("I lost the ${s.target}", "Point the camera back at it, or cancel and search again.", false)
            else GuidanceView("Found the ${s.target}", "Tap Start Guidance or say “guide me”.", false)
        s.mode == Mode.GUIDING -> GuidanceView(
            s.cue.phrase(s.target)
                ?: if (s.handStatus == HandSelector.Status.TRACKING) "Hold steady…" else "Hold your hand out in front of the camera.",
            "Your index fingertip is guided towards the ${s.target}. Tap I’ve got it when you’re holding it.", true)
        s.mode == Mode.TRACKING_LOST -> GuidanceView(
            "I lost the ${s.target}", "Guidance is paused. Tap Start Guidance to try again, or Cancel.", false)
        else -> GuidanceView(Guidance.REACHED.phrase(s.target)!!, "Tap Find an Object to search again.", false)
    }

    private fun name(s: ReachSnapshot) = s.target.ifEmpty { "Object" }.replaceFirstChar { it.uppercase() }
}
