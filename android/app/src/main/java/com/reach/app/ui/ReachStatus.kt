package com.reach.app.ui

import com.reach.app.Mode
import com.reach.app.ReachSnapshot
import com.reach.app.grounding.TrackStatus
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.phrase

/**
 * Turns the controller's real per-frame state into what the screen says. Pure Kotlin (JVM-tested):
 * "Guiding" only exists in REACHING and "Reached" only in DONE, which the controller enters only after
 * the guidance engine actually reported REACHED.
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
            Mode.INTERPRETING -> Line("Searching", Tone.ACTIVE)
            Mode.TARGET_LOCKED ->
                if (s.track == TrackStatus.LOST) Line("Target lost", Tone.WARN) else Line("${name(s)} detected", Tone.GOOD)
            Mode.REACHING -> when {
                s.track == TrackStatus.LOST || s.cue == Guidance.TARGET_LOST -> Line("Target lost", Tone.WARN)
                !s.hand.likelyWearer -> Line("Waiting for your hand", Tone.WARN)
                else -> Line("Guiding", Tone.ACTIVE)
            }
            Mode.DONE -> Line("Reached", Tone.GOOD)
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
        val labels = s.candidates.sortedByDescending { it.score }.map { it.label }.distinct()
        return when {
            labels.isEmpty() -> Line("No objects recognised", Tone.NEUTRAL)
            labels.size <= 2 -> Line("Seeing ${labels.joinToString(", ")}", Tone.NEUTRAL)
            else -> Line("Seeing ${labels.take(2).joinToString(", ")} +${labels.size - 2}", Tone.NEUTRAL)
        }
    }

    fun hand(s: ReachSnapshot?): Line? = when {
        s == null -> null
        s.hand.likelyWearer -> Line("Hand tracked", Tone.GOOD)
        s.hand.present -> Line("Hand too far away", Tone.NEUTRAL) // ignored for guidance
        else -> Line("No hand in view", Tone.NEUTRAL)
    }

    fun guidance(s: ReachSnapshot?, camera: CameraState): GuidanceView = when {
        camera == CameraState.UNAVAILABLE -> GuidanceView("Camera unavailable", "Guidance needs the camera.", false)
        s == null || s.mode == Mode.IDLE -> GuidanceView("Ready to help", "Say or type an object, like “bottle”.", false)
        s.mode == Mode.INTERPRETING -> GuidanceView("Looking for it…", "Keep the camera pointed at the area.", false)
        s.mode == Mode.TARGET_LOCKED ->
            if (s.track == TrackStatus.LOST) GuidanceView("I lost the ${s.target}", "Point the camera back at it, or cancel and search again.", false)
            else GuidanceView("Found the ${s.target}", "Tap Start Guidance or say “guide me”.", false)
        s.mode == Mode.REACHING -> GuidanceView(
            s.cue.phrase(s.target)
                ?: if (s.hand.likelyWearer) "Hold steady…" else "Hold your hand out in front of you.",
            "Your index fingertip is guided towards the ${s.target}.", true)
        else -> GuidanceView(Guidance.REACHED.phrase(s.target)!!, "Tap Find an Object to search again.", false)
    }

    private fun name(s: ReachSnapshot) = s.target.ifEmpty { "Object" }.replaceFirstChar { it.uppercase() }
}
