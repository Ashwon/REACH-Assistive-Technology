package com.reach.app.ui

import com.reach.app.HandView
import com.reach.app.Mode
import com.reach.app.ReachSnapshot
import com.reach.app.grounding.TrackStatus
import com.reach.app.haptic.HapticCue
import com.reach.app.hand.HandSelector
import com.reach.app.hand.HandSelector.SlotState
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.TurnMonitor
import com.reach.app.spatial.phrase

/**
 * Turns the controller's real per-frame state into what the screen says. Pure Kotlin (JVM-tested):
 * "Guiding" only exists in GUIDING and "Reached" only in REACHED, which the core enters only on
 * depth-verified contact or the user's own confirmation. Distances, hand states, turns and vibration are shown only
 * from the values the core measured or sent this frame.
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
            Mode.TARGET_LOCKED -> when {
                s.turn == TurnMonitor.State.AWAY -> Line("Turned away from target", Tone.WARN)
                s.track == TrackStatus.LOST -> Line("Target lost", Tone.WARN)
                !s.identityVerified -> Line("Checking it's your ${s.identity}", Tone.WARN)
                else -> Line("${name(s)} detected", Tone.GOOD)
            }
            Mode.GUIDING -> {
                val g = s.guided?.let { s.hands(it) }
                when {
                    s.turn == TurnMonitor.State.AWAY -> Line("Paused · turned away", Tone.WARN)
                    s.track == TrackStatus.LOST -> Line("Target lost", Tone.WARN)
                    !s.identityVerified -> Line("Paused · checking it's your ${s.identity}", Tone.WARN)
                    s.track == TrackStatus.OCCLUDED -> Line("Paused · target covered", Tone.WARN)
                    s.track == TrackStatus.TEMPORARILY_MISSING || s.cue == Guidance.TARGET_LOST -> Line("Paused · target hidden", Tone.WARN)
                    s.enrolling != null || g == null || g.state == SlotState.NEEDS_RECOVERY || g.state == SlotState.NOT_ENROLLED ->
                        Line("Paused · confirm your hand", Tone.WARN)
                    g.state == SlotState.UNCERTAIN -> Line("Paused · hand uncertain", Tone.WARN)
                    g.state == SlotState.MISSING -> Line("Waiting for your ${g.side.word} hand", Tone.WARN)
                    else -> Line("Guiding", Tone.ACTIVE)
                }
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
            TrackStatus.OCCLUDED -> Line("${name(s)} covered by a hand · still locked", Tone.NEUTRAL)
            TrackStatus.TEMPORARILY_MISSING -> Line("${name(s)} briefly hidden · still locked", Tone.NEUTRAL)
            TrackStatus.LOST -> Line("${name(s)} out of view · still remembered", Tone.WARN)
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

    /** Both enrolled hands, or the enrolment step in progress. Identity comes from the gesture, never from position. */
    fun hand(s: ReachSnapshot?): Line? {
        if (s == null) return null
        s.enrolling?.let { side ->
            val w = side.word
            return when (s.enrollStatus) {
                HandSelector.Status.TOO_FAR -> Line("Bring your $w hand closer", Tone.NEUTRAL)
                HandSelector.Status.CONFIRM -> Line("Make a fist with your $w hand", Tone.ACTIVE)
                HandSelector.Status.AMBIGUOUS -> Line("More than one hand · show your $w hand again", Tone.WARN)
                HandSelector.Status.SAME_AS_OTHER -> Line("That looks like your other hand · show your $w hand", Tone.WARN)
                else -> Line("Raise your $w hand", Tone.ACTIVE)
            }
        }
        val l = s.left; val r = s.right
        val tone = when {
            listOf(l, r).any { it.state == SlotState.NEEDS_RECOVERY || it.state == SlotState.UNCERTAIN } -> Tone.WARN
            l.state == SlotState.TRACKED && r.state == SlotState.TRACKED -> Tone.GOOD
            else -> Tone.NEUTRAL
        }
        val others = if (s.otherHands.isEmpty()) "" else " · other hand ignored"
        return Line("Left: ${short(l)} · Right: ${short(r)}$others", tone)
    }

    private fun short(v: HandView) = when (v.state) {
        SlotState.NOT_ENROLLED -> "not set"
        SlotState.TRACKED -> "tracked"
        SlotState.MISSING -> "out of view ${v.missingMs / 1000}s"
        SlotState.UNCERTAIN -> "uncertain"
        SlotState.NEEDS_RECOVERY -> "confirm again"
    }

    /** Distance state from the real depth readings: null in the 2D camera mode. Every value shown is an estimate. */
    fun distance(s: ReachSnapshot?): Line? {
        if (s == null || !s.depthSource) return null
        val r = s.depth
        return when {
            s.mode != Mode.GUIDING -> Line("Depth camera on", Tone.NEUTRAL)
            s.leftM != null || s.rightM != null ->
                Line("Left ${s.leftM?.let(::cm) ?: "unknown"} · Right ${s.rightM?.let(::cm) ?: "unknown"} · estimate", Tone.ACTIVE)
            r == null -> Line("Distance: waiting", Tone.NEUTRAL)
            r.sample != null -> Line("Hand ≈ ${cm(r.sample.targetM - r.sample.tipM)} from target · estimate", Tone.ACTIVE)
            else -> Line("Distance unavailable · ${r.reason}", Tone.WARN)
        }
    }

    /** Orientation recovery: shown only while the user (camera) is turned away from where the target was. */
    fun turn(s: ReachSnapshot?): Line? {
        if (s == null || s.targetId < 0 || s.turn != TurnMonitor.State.AWAY) return null
        val way = s.turnDeg?.let { if (it > 0) " · turn right" else " · turn left" } ?: ""
        return Line("Turned away$way", Tone.WARN)
    }

    /**
     * The vibration band. [off]: why live guidance is not using it (BandSetup.offReason), or null when it is; then the
     * cue being requested this frame. A request is what the app sends, not proof that it was felt.
     */
    fun haptics(s: ReachSnapshot?, off: String?): Line = when {
        off != null -> Line("Vibration: $off", if (off == "motor test needed" || off == "connection failed") Tone.WARN else Tone.NEUTRAL)
        // Guiding without a band cue is not a fault: this voice cue has no vibration pattern. Say which, not "quiet".
        s?.band == null && s?.mode == Mode.GUIDING -> Line("Vibration: on · " + when (s.cue) {
            Guidance.LINED_UP_2D -> "lined up, reach forward by voice"
            Guidance.UP, Guidance.DOWN -> "up/down is by voice"
            else -> "waiting for a direction"
        }, Tone.ACTIVE)
        s?.band == null -> Line("Vibration: connected", Tone.NEUTRAL)
        else -> Line("Vibration: ${s.band.label.lowercase()}", when (s.band) {
            HapticCue.REACHED -> Tone.GOOD
            HapticCue.HOLD, HapticCue.TARGET_LOST -> Tone.WARN
            else -> Tone.ACTIVE
        })
    }

    /** Forward gap as the overlay draws it at the fingertip, or null without a valid depth sample. */
    fun gapLabel(s: ReachSnapshot?): String? = s?.depth?.sample?.let { "≈ ${cm(it.targetM - it.tipM)}" }

    private fun cm(m: Float) = "${(m * 100).toInt()} cm"

    fun guidance(s: ReachSnapshot?, camera: CameraState): GuidanceView = when {
        camera == CameraState.UNAVAILABLE -> GuidanceView("Camera unavailable", "Guidance needs the camera.", false)
        s == null || s.mode == Mode.IDLE -> GuidanceView("Ready to help", "Ask for an object, like “can you find my bottle?”", false)
        s.mode == Mode.SEARCHING -> GuidanceView("Looking for it…", "Keep the camera pointed at the area.", false)
        s.mode == Mode.TARGET_LOCKED -> when {
            s.turn == TurnMonitor.State.AWAY -> GuidanceView("You may have turned away", "Turn back toward where the ${s.target} was.", false)
            s.track == TrackStatus.LOST -> GuidanceView("I can't see the ${s.target}", "Point the camera back at it, or cancel and search again.", false)
            else -> GuidanceView("Found the ${s.target}", "Tap Start Guidance or say “guide me”.", false)
        }
        s.mode == Mode.GUIDING -> {
            val hand = s.guided?.let { "${it.word} hand" } ?: "hand"
            GuidanceView(
                s.cue.phrase(s.target, hand)
                    ?: if (s.hand.present) "Hold steady…" else "Hold your $hand out in front of the camera.",
                "Guiding your $hand’s index fingertip to the ${s.target}. Tap I’ve got it when you’re holding it.",
                s.depth?.sample == null)
        }
        s.mode == Mode.TRACKING_LOST -> GuidanceView(
            "I lost the ${s.target}", "Guidance is paused. Tap Start Guidance to try again, or Cancel.", false)
        else -> GuidanceView(Guidance.REACHED.phrase(s.target)!!, "Tap Find an Object to search again.", false)
    }

    private fun name(s: ReachSnapshot) = s.target.ifEmpty { "Object" }.replaceFirstChar { it.uppercase() }
}
