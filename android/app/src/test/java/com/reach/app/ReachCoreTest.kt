package com.reach.app

import com.reach.app.grounding.Box
import com.reach.app.grounding.Candidate
import com.reach.app.hand.HandResult
import com.reach.app.hand.HandSelector
import com.reach.app.hand.Pose
import com.reach.app.hand.hand
import com.reach.app.spatial.DepthSample
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.phrase
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/** Frames every 75 ms; the detector reports every 300 ms. Bottle box centre (0.50, 0.55). */
class ReachCoreTest {
    private val spoken: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val core = ReachCore({ spoken += it })
    private var t = 0L

    private val bottle = cand(1, "bottle")
    private val leftOfBottle = hand(0.30f, 0.75f, 0.20f, tipX = 0.30f, tipY = 0.55f)   // -> "Move right."
    private val onBottle = hand(0.50f, 0.75f, 0.20f, tipX = 0.50f, tipY = 0.55f)
    private val bystander = hand(0.80f, 0.30f, 0.18f, tipX = 0.80f, tipY = 0.20f)      // would be "Move left."
    private val directions = listOf(Guidance.LEFT, Guidance.RIGHT, Guidance.UP, Guidance.DOWN, Guidance.CLOSER)
        .map { it.phrase("bottle")!! }

    private fun cand(id: Int, label: String, score: Float = 0.8f, confirmed: Boolean = true, conflict: String? = null,
                     lowLight: Boolean = false) =
        Candidate(id, label, "unknown", Box(0.45f, 0.40f, 0.55f, 0.70f), score, null, confirmed, conflict, lowLight)

    private fun run(ms: Long, target: Boolean = true, depth: DepthSample? = null, c: ReachCore = core,
                    hands: () -> List<HandResult>) {
        val end = t + ms
        while (t < end) {
            if (target && t % 300 == 0L) c.onDetections(listOf(bottle), t, c.session)
            c.onFrame(hands(), emptyList(), t, depth)
            t += 75
        }
    }

    /** The user raises the hand at [at]'s palm, opens it, then closes it into a fist. */
    private fun gesture(at: HandResult, vararg extra: HandResult, c: ReachCore = core) {
        run(450, c = c) { listOf(hand(at.palmX, at.palmY, at.handSize, pose = Pose.OPEN)) + extra }
        run(450, c = c) { listOf(hand(at.palmX, at.palmY, at.handSize, pose = Pose.FIST)) + extra }
    }

    private fun setHand(at: HandResult = leftOfBottle, c: ReachCore = core) { c.beginHandSetup(t); gesture(at, c = c) }

    private fun lock(c: ReachCore = core) =
        assertTrue(c.onSearchResult(c.beginSearch(), bottle, "bottle", 0.8, offline = true, question = "", nowMs = t))

    private fun lockAndGuide(at: HandResult = leftOfBottle) { setHand(at); lock(); assertTrue(core.startGuidance(t)) }

    private fun spokenSince(i: Int) = synchronized(spoken) { spoken.drop(i) }

    @Test fun handSetupComesFirstAndIsConfirmedByTheGestureOnly() {
        core.beginHandSetup(t, "REACH ready. ")
        assertEquals("REACH ready. ${ReachCore.HAND_SETUP}", spoken.last())
        run(3000) { listOf(leftOfBottle) }
        assertEquals(Guidance.HAND_FIST.phrase("")!!, spoken.last()) // a raised hand is asked to confirm, not assumed
        gesture(leftOfBottle)
        assertEquals("Got your hand. Keep it in view.", spoken.last())
        assertEquals(HandSelector.Status.TRACKING, core.onFrame(listOf(leftOfBottle), emptyList(), t).handStatus)
    }

    @Test fun anUnansweredSetupPromptExpiresButGuidanceKeepsAsking() {
        core.beginHandSetup(t)
        run(HandSelector.SELECT_WINDOW_MS + 1000) { emptyList() }
        assertEquals(HandSelector.Status.NONE, core.onFrame(emptyList(), emptyList(), t).handStatus) // idle: stops asking
        lock(); core.startGuidance(t)
        run(HandSelector.SELECT_WINDOW_MS + 5000) { listOf(leftOfBottle) } // guiding: the prompt is renewed
        gesture(leftOfBottle)
        run(4000) { listOf(leftOfBottle) }
        assertEquals("Move right.", spoken.last())
    }

    @Test fun findingAnObjectNeverSelectsAHand() {
        lock()
        run(3000) { listOf(leftOfBottle) }
        assertEquals(HandSelector.Status.NONE, core.onFrame(listOf(leftOfBottle), emptyList(), t).handStatus)
        assertTrue(core.startGuidance(t))                  // guidance asks for the hand first...
        assertTrue(spoken.last(), spoken.last().endsWith(ReachCore.HAND_SETUP))
        run(5000) { listOf(leftOfBottle, bystander) }      // ...and gives no direction until it has one
        assertTrue(spoken.none { it in directions })
        gesture(leftOfBottle, bystander)
        run(4000) { listOf(leftOfBottle, bystander) }
        assertTrue("Got your hand. Keep it in view." in spoken)
        assertEquals("Move right.", spoken.last())         // the user's fingertip, never the bystander's ("Move left.")
        assertFalse("Move left." in spoken)
    }

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
        lockAndGuide(onBottle)
        run(3000, depth = DepthSample(0.60f, 0.60f)) { listOf(onBottle) }
        assertEquals(Mode.REACHED, core.mode)
        val mark = spoken.size
        run(1000, depth = DepthSample(Float.NaN, 0.6f)) { listOf(leftOfBottle) }  // invalid depth, misaligned
        run(1000, target = false) { emptyList() }                                // target and hand gone
        assertEquals(Mode.REACHED, core.mode)
        assertEquals(1, spoken.count { it == "Bottle reached." }); assertEquals(emptyList<String>(), spokenSince(mark))
    }

    @Test fun invalidOrMissingDepthNeverReaches() {
        lockAndGuide(onBottle)
        run(10_000, depth = DepthSample(Float.NaN, 0.60f)) { listOf(onBottle) }
        run(10_000) { listOf(onBottle) } // 2D overlap only, fingertip dead centre for 10 s
        assertEquals(Mode.GUIDING, core.mode); assertFalse("Bottle reached." in spoken)
        assertTrue("Lined up. Reach forward slowly." in spoken)
    }

    @Test fun lostHandPausesGuidanceAndOnlyTheUsersGestureResumesIt() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        val mark = spoken.size
        run(HandSelector.LOSE_MS + 500) { listOf(bystander) } // user's hand gone, someone else's in view
        val said = spokenSince(mark)
        assertTrue(said.toString(), "I can't see your hand." in said && "I lost your hand. Guidance paused." in said)
        run(6000) { listOf(bystander) }                         // the bystander's hand is never adopted
        assertEquals(Mode.GUIDING, core.mode)
        assertTrue(spokenSince(mark).toString(), spokenSince(mark).none { it in directions })
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
        assertTrue(said.toString(), "Another hand came too close to yours. Guidance paused." in said)
        assertTrue(said.toString(), said.none { it in directions })
    }

    @Test fun lostTargetEndsInTrackingLostAndOnlyAnExplicitRetryResumes() {
        lockAndGuide()
        run(4000) { listOf(leftOfBottle) }
        val mark = spoken.size
        run(1500 + ReachCore.TARGET_GIVE_UP_MS + 500, target = false) { listOf(leftOfBottle) }
        assertEquals(Mode.TRACKING_LOST, core.mode)
        val said = spokenSince(mark)
        assertTrue("I can't see the bottle. Hold still." in said)
        assertEquals("I lost the bottle. Say guide me to try again, or ask for it again.", said.last())
        run(3000) { listOf(leftOfBottle) } // the bottle is detected again, but nothing resumes by itself
        assertEquals(Mode.TRACKING_LOST, core.mode); assertEquals(said.size, spokenSince(mark).size)
        assertTrue(core.startGuidance(t))  // deliberate retry; the hand is still the selected one
        run(4000) { listOf(leftOfBottle) }
        assertEquals(Mode.GUIDING, core.mode); assertEquals("Move right.", spoken.last())
    }

    @Test fun handCoveringTheTargetDoesNotCountAsLosingIt() {
        lockAndGuide(onBottle)
        run(4000) { listOf(onBottle) }
        run(10_000, target = false) { listOf(onBottle) } // fingertip over where the bottle was
        assertEquals(Mode.GUIDING, core.mode)
        assertTrue(Guidance.HAND_COVERS_TARGET.phrase("bottle") in spoken)
    }

    @Test fun cancelledOrSupersededSearchResultsAreDropped() {
        val first = core.beginSearch()
        core.cancel()
        assertFalse(core.onSearchResult(first, bottle, "bottle", 0.9, false, "", t))
        assertEquals(Mode.IDLE, core.mode); assertEquals(listOf("Reset."), spoken)

        val a = core.beginSearch(); val b = core.beginSearch()
        assertFalse(core.onSearchResult(a, bottle, "bottle", 0.9, false, "", t))
        assertTrue(core.onSearchResult(b, cand(2, "cup"), "cup", 0.9, false, "", t))
        assertEquals(Mode.TARGET_LOCKED, core.mode); assertEquals(2, core.lock.id)
    }

    @Test fun staleDetectionFromBeforeANewLockIsNotApplied() {
        val tokA = core.beginSearch(); core.onSearchResult(tokA, bottle, "bottle", 0.9, true, "", 0)
        val oldSession = core.session
        val tokB = core.beginSearch(); core.onSearchResult(tokB, cand(2, "cup"), "cup", 0.9, true, "", 0)
        val moved = Candidate(2, "cup", "unknown", Box(0.05f, 0.05f, 0.15f, 0.15f), 0.9f, null)
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
        assertTrue(lock(cand(3, "bottle", conflict = "vase"), "coke"))                // any drink container is a coke candidate
        assertTrue(lock(cand(4, "laptop", score = 0.45f), "laptop"))                  // settled but weak: hedged
        assertTrue(spoken.last(), spoken.last().startsWith("I might see the laptop, but I'm not sure.") && "percent" !in spoken.last())
        assertTrue(lock(cand(5, "laptop", score = 0.9f, lowLight = true), "laptop"))  // dark scene: never certain
        assertTrue(spoken.last(), "because it's dark" in spoken.last())
        assertTrue(lock(cand(6, "laptop", score = 0.9f), "laptop"))
        assertTrue(spoken.last(), spoken.last().startsWith("Found the laptop, 90 percent sure."))
    }

    @Test fun retryAfterReachedNeedsTheUserToAskAgain() {
        lockAndGuide()
        run(1000) { listOf(leftOfBottle) }
        core.confirmReached()
        assertFalse(core.confirmReached()) // already reached: no second confirmation
        assertTrue(core.startGuidance(t))  // "guide me" again is deliberate
        assertEquals(Mode.GUIDING, core.mode)
    }
}
