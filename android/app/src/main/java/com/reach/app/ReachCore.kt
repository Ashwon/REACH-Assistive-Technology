package com.reach.app

import com.reach.app.grounding.Candidate
import com.reach.app.grounding.SupportedClasses
import com.reach.app.grounding.TargetLock
import com.reach.app.grounding.TrackStatus
import com.reach.app.hand.HandResult
import com.reach.app.hand.HandSelector
import com.reach.app.spatial.DepthSample
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.GuidanceThrottle
import com.reach.app.spatial.SpatialEngine
import com.reach.app.spatial.SpatialInput
import com.reach.app.spatial.phrase

/**
 *   IDLE -> SEARCHING -> TARGET_LOCKED -> GUIDING -> REACHED
 *                                          |    ^
 *                                          v    | "guide me" (deliberate retry)
 *                                        TRACKING_LOST (the target)
 * A new search can start from any state; cancel returns to IDLE.
 * The user's hand is a separate track of state (HandSelector): chosen by a deliberate gesture, before or at
 * "guide me". While GUIDING without the chosen hand in view, guidance is paused, never redirected.
 */
enum class Mode { IDLE, SEARCHING, TARGET_LOCKED, GUIDING, REACHED, TRACKING_LOST }

/** The state the screen shows, built once per camera frame from the same values guidance runs on. */
data class ReachSnapshot(
    val mode: Mode,
    val target: String,          // what the user asked for ("bottle", "coke"); "" when nothing is locked
    val track: TrackStatus,      // of the locked target
    val targetId: Int,           // -1 when nothing is locked
    val candidates: List<Candidate>,
    val hand: HandResult,        // the user's (selected) hand, NONE while it is not in view
    val cue: Guidance,           // engine output while guiding, NONE otherwise
    val handStatus: HandSelector.Status = HandSelector.Status.NONE,
    val otherHands: List<HandResult> = emptyList() // hands in view that are not guided
)

/**
 * The guidance state machine, pure Kotlin so it is unit-tested. Every state change and every spoken line it
 * produces happen under one lock, so a transition and the speech it allows cannot interleave across the
 * camera, detector and main threads (the old controller checked `mode` and spoke later, unlocked).
 *
 * Every transition bumps [session]. Async work (a detector run, a Claude interpretation) carries the session
 * it started in and is dropped once the session has moved on, so a late result can never restart, redirect
 * or re-lock guidance. REACHED and TRACKING_LOST end directional speech; only the user leaves them, by
 * "guide me" or a new search. Detection never touches the hand selection, and the hand never touches the target.
 *
 * Reached needs evidence: depth-verified contact from SpatialEngine (no depth is fed in this pipeline), or
 * the user saying / tapping that they have the object. A 2D overlap never ends guidance as "reached".
 */
class ReachCore(private val say: (String) -> Unit, private val log: (String) -> Unit = {}) {

    companion object {
        /** Let "guiding..." / "found it again" finish before direction cues resume. */
        const val INTRO_QUIET_MS = 3000L
        const val RELOCK_QUIET_MS = 1500L
        /** Target box gone (after TargetLock's hold) and not hidden by the hand this long -> TRACKING_LOST. */
        const val TARGET_GIVE_UP_MS = 4000L
        const val HAND_SETUP = "Raise the hand you'll reach with, then close it into a fist."
    }

    @Volatile var mode = Mode.IDLE; private set
    @Volatile var session = 0; private set
    @Volatile var targetName = ""; private set
    val lock = TargetLock()
    private val spatial = SpatialEngine()
    private val throttle = GuidanceThrottle()
    /** Hand-setup prompts outside guidance: said when they change, not repeated. */
    private val setupThrottle = GuidanceThrottle(statusRepeatMs = Long.MAX_VALUE / 4)
    private val hands = HandSelector()
    private var lastCue = Guidance.NONE
    private var introPending = false
    private var announcedRelocks = 0
    private var targetGoneSince = -1L
    val ex get() = spatial.ex
    val ey get() = spatial.ey

    /** "Set my hand": start choosing the user's hand. Independent of any object search. */
    @Synchronized fun beginHandSetup(nowMs: Long, intro: String = "") {
        hands.beginSelection(nowMs)
        setupThrottle.reset(); setupThrottle.filter(Guidance.HAND_SHOW, nowMs) // HAND_SETUP below already says it
        say(intro + HAND_SETUP)
    }

    /** Main thread. Returns the token the interpretation must hand back to [onSearchResult]. */
    @Synchronized fun beginSearch(): Int {
        lock.clear(); targetName = "" // a new request never leaves guidance pointed at the previous object
        enter(Mode.SEARCHING)
        return session
    }

    /** Result of the interpretation started by [beginSearch]; ignored unless that search is still current. */
    @Synchronized fun onSearchResult(token: Int, chosen: Candidate?, name: String, confidence: Double,
                                     offline: Boolean, question: String, nowMs: Long): Boolean {
        if (token != session || mode != Mode.SEARCHING) return false // cancelled or superseded
        if (chosen == null) {
            say(question.ifBlank { "I'm not sure which object you mean." })
            enter(Mode.IDLE)
        } else lockOn(chosen, name, confidence, offline, nowMs)
        return true
    }

    /** Locks only a settled detection, and says how sure it is. A detector score is never "verified". */
    @Synchronized fun lockOn(c: Candidate, name: String, confidence: Double, offline: Boolean, nowMs: Long): Boolean {
        val wanted = name.ifBlank { c.label }
        val spec = SupportedClasses.resolve(wanted)
        val accepted = spec?.labels ?: setOf(c.label)
        // e.g. Claude matched "laptop bag" to a detection: the detector cannot know laptop bags, so say what it saw.
        val unsupported = spec != null && !spec.supported
        val refusal = when {
            !c.confirmed -> "I only saw something like a $wanted for a moment. Hold the camera still and ask again."
            c.conflict != null && c.conflict !in accepted ->
                "I can't tell if that's a ${c.label} or a ${c.conflict}. Move the camera a little and ask again."
            else -> null
        }
        if (refusal != null) { say(refusal); enter(Mode.IDLE); return false }
        targetName = if (unsupported) c.label else wanted
        lock.lock(c, nowMs)
        announcedRelocks = lock.reacquisitions
        enter(Mode.TARGET_LOCKED)
        val src = if (offline) " Offline mode." else ""
        say(when {
            unsupported -> "I can't recognise ${spec!!.name}s, but this looks like a ${c.label} to me.$src Say guide me to try it."
            c.sure -> "Found the $wanted, ${(confidence * 100).toInt()} percent sure.$src Say guide me when ready."
            else -> "I might see the $wanted, but I'm not sure${if (c.lowLight) " because it's dark" else ""}.$src " +
                "Say guide me to try it, or move the camera for a better view."
        })
        return true
    }

    /** "guide me": start, restart or retry guidance to the locked target. Directions start only once the
     *  user's hand is selected and in view; if it is not selected yet, this asks for the gesture first. */
    @Synchronized fun startGuidance(nowMs: Long): Boolean {
        if (!lock.active || mode == Mode.IDLE || mode == Mode.SEARCHING) return false
        spatial.reset(); throttle.reset()
        lastCue = Guidance.NONE; targetGoneSince = -1L; introPending = true
        if (!hands.selected) hands.beginSelection(nowMs)
        enter(Mode.GUIDING)
        say("Guiding by direction only, not distance." + if (hands.selected) " Hold your hand out." else " First, $HAND_SETUP")
        return true
    }

    /** The user says or taps that they have the object: the only "reached" evidence 2D guidance has. */
    @Synchronized fun confirmReached(): Boolean {
        if (mode != Mode.GUIDING && mode != Mode.TRACKING_LOST) return false
        reach()
        return true
    }

    @Synchronized fun cancel() {
        lock.clear(); targetName = ""
        enter(Mode.IDLE)
        say("Reset.")
    }

    /** Detector thread. [token] is the session when the frame was taken; older results are dropped. */
    @Synchronized fun onDetections(cands: List<Candidate>, frameMs: Long, token: Int) {
        if (token != session) return
        if (mode == Mode.TARGET_LOCKED || mode == Mode.GUIDING || mode == Mode.TRACKING_LOST) lock.update(cands, frameMs)
    }

    /** Camera thread, every frame. depth = null: this pipeline has no depth registered to its frames. */
    @Synchronized fun onFrame(detected: List<HandResult>, candidates: List<Candidate>, tsMs: Long,
                              depth: DepthSample? = null): ReachSnapshot {
        val wasSelecting = hands.selecting
        val hand = hands.update(detected, tsMs)
        trackHandIdentity(wasSelecting, tsMs)
        if (introPending) { throttle.quiet(tsMs + INTRO_QUIET_MS); introPending = false }

        // The lock only re-acquires an unambiguous, settled same-class object near the old spot; still, say so.
        if (lock.reacquisitions != announcedRelocks) {
            announcedRelocks = lock.reacquisitions
            log("relock $targetName#${lock.id}")
            if (mode == Mode.TRACKING_LOST) say("Found the $targetName again. Say guide me to continue.")
            else if (mode == Mode.TARGET_LOCKED || mode == Mode.GUIDING) say("Found the $targetName again.")
            throttle.quiet(tsMs + RELOCK_QUIET_MS)
        }

        if (mode == Mode.GUIDING) guide(hand, tsMs, depth)
        else setupPrompt()?.let { p -> setupThrottle.filter(p, tsMs)?.let { say(it.phrase(targetName)!!) } }
        val m = mode
        return ReachSnapshot(m, targetName, lock.status, if (lock.active) lock.id else -1, candidates, hand,
            if (m == Mode.GUIDING || m == Mode.REACHED) lastCue else Guidance.NONE, hands.status, hands.others)
    }

    /** Announces a confirmed hand; on a dropped one, pauses guidance and asks for the gesture again. */
    private fun trackHandIdentity(wasSelecting: Boolean, tsMs: Long) {
        when {
            wasSelecting && hands.status == HandSelector.Status.TRACKING -> {
                log("hand selected")
                say("Got your hand. Keep it in view.")
                throttle.quiet(tsMs + RELOCK_QUIET_MS)
            }
            hands.status == HandSelector.Status.LOST -> {
                log("hand dropped: ${hands.lost}")
                if (mode != Mode.GUIDING) { hands.clear(); return } // nothing depends on it now; chosen again before guiding
                say(if (hands.lost == HandSelector.Lost.TOO_CLOSE) "Another hand came too close to yours. Guidance paused."
                    else "I lost your hand. Guidance paused.")
                hands.beginSelection(tsMs)
                throttle.quiet(tsMs + RELOCK_QUIET_MS)
            }
        }
    }

    private fun setupPrompt(): Guidance? = when (hands.status) {
        HandSelector.Status.SHOW -> Guidance.HAND_SHOW
        HandSelector.Status.TOO_FAR -> Guidance.HAND_TOO_FAR
        HandSelector.Status.CONFIRM -> Guidance.HAND_FIST
        HandSelector.Status.AMBIGUOUS -> Guidance.HAND_AMBIGUOUS
        else -> null
    }

    private fun guide(hand: HandResult, tsMs: Long, depth: DepthSample?) {
        if (hands.status == HandSelector.Status.NONE) hands.beginSelection(tsMs) // guiding needs a hand: keep asking
        val tip = hand.index // all five tips and this one come from the one selected hand
        val box = lock.boxAt(tsMs)
        val engine = spatial.update(SpatialInput(box, tip?.x, tip?.y, depth, tsMs))
        val cue = setupPrompt() ?: engine // no selected hand: say how to give one, never a direction
        lastCue = cue
        if (cue == Guidance.REACHED) { reach(); return }

        if (box != null || cue == Guidance.HAND_COVERS_TARGET) targetGoneSince = -1L
        else if (targetGoneSince < 0) targetGoneSince = tsMs
        else if (tsMs - targetGoneSince >= TARGET_GIVE_UP_MS) {
            lose("I lost the $targetName. Say guide me to try again, or ask for it again.")
            return
        }

        throttle.filter(cue, tsMs)?.let { spoken ->
            log("cue=$spoken target=${box?.let { "%.2f,%.2f".format(it.centerX(), it.centerY()) }} " +
                "tip=${tip?.let { "%.2f,%.2f".format(it.x, it.y) }} err=%+.3f,%+.3f hand=${hands.status}".format(spatial.ex, spatial.ey))
            spoken.phrase(targetName)?.let(say)
        }
    }

    /** Terminal for guidance: one confirmation; the flush in the speaker drops anything still queued. */
    private fun reach() {
        enter(Mode.REACHED)
        lastCue = Guidance.REACHED
        say(Guidance.REACHED.phrase(targetName)!!)
    }

    private fun lose(line: String) {
        enter(Mode.TRACKING_LOST)
        lastCue = Guidance.NONE
        say(line)
    }

    private fun enter(m: Mode) {
        if (m != mode) log("mode $mode -> $m")
        mode = m; session++
    }
}
