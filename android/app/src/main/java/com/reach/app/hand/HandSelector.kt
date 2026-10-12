package com.reach.app.hand

import kotlin.math.hypot

/**
 * Establishes which hand is the user's, then keeps following that same hand. Separate from object detection.
 *
 * Identity is a deliberate act, not a guess: after beginSelection() (the user is prompted to raise the hand
 * they will reach with and close it into a fist), the hand that is seen OPEN after the prompt and then held as
 * a FIST for FIST_MS is selected. Other hands in view are ignored unless they make the same gesture, in which
 * case nobody is selected (AMBIGUOUS) and the user is asked again. Nearest hand, first detection, handedness
 * and MediaPipe's list order are never used: MediaPipe gives hands no identity and no stable order.
 *
 * After selection every visible hand has its own track (palm position + size continuity; tracks seen a moment
 * ago claim their hands first), so a bystander's hand stays on its own track when the order changes.
 * The selection is dropped (LOST) when the hand has been unseen for LOSE_MS, or at once when another hand comes
 * close enough that the two tracks could swap (TOO_CLOSE): the caller pauses guidance and asks for the gesture
 * again. Guidance is never handed to another hand silently.
 *
 * This is not biometric identification: someone else who makes the gesture on cue would be indistinguishable.
 */
class HandSelector {
    enum class Status {
        NONE,       // no hand selected and not selecting
        SHOW,       // selecting: no hand in view
        TOO_FAR,    // selecting: hands in view, all too small to read a gesture from
        CONFIRM,    // selecting: hand(s) in view, waiting for one to close into a fist
        AMBIGUOUS,  // selecting: more than one hand made the gesture; asking again
        TRACKING,   // the selected hand is in view
        MISSING,    // the selected hand is briefly out of view (<= LOSE_MS)
        LOST        // the selection was just dropped; see [lost]
    }
    enum class Lost { GONE, TOO_CLOSE }

    companion object {
        const val MIN_SIZE = 0.06f      // wrist->middle knuckle (frame units); smaller = > ~1.5 m away, landmarks too coarse
        const val FIST_MS = 300L        // the fist must be held this long
        const val OPEN_BEFORE_MS = 3000L // ...and follow an open hand seen after the prompt, within this time
        const val SELECT_WINDOW_MS = 20_000L // a gesture answers a recent prompt; after this, prompt again
        const val LOSE_MS = 2000L       // the speaker says "I can't see your hand" well before this (SpatialEngine)
        const val GATE = 0.3f           // max palm jump between sightings, frame units
        const val RECENT_MS = 250L      // ~3 frames: a track seen this recently is "continuous"
        const val MIN_RATIO = 0.5f; const val MAX_RATIO = 2f // hand size change between sightings

        /** Palms this close could swap tracks: identity can no longer be guaranteed. */
        fun tooClose(a: HandResult, b: HandResult) =
            hypot(a.palmX - b.palmX, a.palmY - b.palmY) < maxOf(0.08f, 0.6f * a.handSize)
    }

    private class Track(val id: Int, var hand: HandResult, var seenMs: Long) {
        var openMs = -1L; var fistSince = -1L
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 0
    private var selectedId = -1
    private var selectingSince = 0L
    private var ambiguous = false
    var status = Status.NONE; private set
    /** Why the last selection was dropped. Set when status becomes LOST. */
    var lost: Lost? = null; private set
    /** Hands in view this frame that are not the selected one (drawn dimmed, never guided). */
    var others: List<HandResult> = emptyList(); private set

    val selected get() = status == Status.TRACKING || status == Status.MISSING
    val selecting get() = status == Status.SHOW || status == Status.TOO_FAR || status == Status.CONFIRM || status == Status.AMBIGUOUS

    /** Start (or restart) choosing the user's hand: forget any selection; wait for the gesture. */
    fun beginSelection(nowMs: Long) {
        selectedId = -1; selectingSince = nowMs; ambiguous = false; lost = null; status = Status.SHOW
    }

    /** Forget the selection and stop choosing. */
    fun clear() { selectedId = -1; ambiguous = false; status = Status.NONE }

    /** Returns the selected hand if it is visible in this frame, else HandResult.NONE. */
    fun update(hands: List<HandResult>, nowMs: Long): HandResult {
        val seen = associate(hands, nowMs)
        for (t in tracks) when {
            t !in seen -> t.fistSince = -1L
            t.hand.pose == Pose.OPEN -> { t.openMs = nowMs; t.fistSince = -1L }
            t.hand.pose == Pose.FIST -> if (t.fistSince < 0) t.fistSince = nowMs
            else -> t.fistSince = -1L
        }

        val sel = tracks.firstOrNull { it.id == selectedId }
        status = when {
            selectedId >= 0 -> when {
                sel == null -> drop(Lost.GONE) // unseen for LOSE_MS; ids are never reused, so it cannot come back
                sel in seen && seen.any { it !== sel && tooClose(sel.hand, it.hand) } -> drop(Lost.TOO_CLOSE)
                sel in seen -> Status.TRACKING
                else -> Status.MISSING
            }
            selecting -> choose(seen, nowMs)
            status == Status.LOST -> Status.NONE // the caller had one frame to react to LOST
            else -> status
        }
        val visible = tracks.firstOrNull { it.id == selectedId && it in seen }?.takeIf { status == Status.TRACKING }
        others = tracks.filter { it in seen && it !== visible }.map { it.hand }
        return visible?.hand ?: HandResult.NONE
    }

    private fun associate(hands: List<HandResult>, nowMs: Long): Set<Track> {
        tracks.removeAll { nowMs - it.seenMs > LOSE_MS }
        val seen = HashSet<Track>()
        val used = BooleanArray(hands.size)
        val pairs = ArrayList<Triple<Float, Int, Track>>()
        for (i in hands.indices) for (t in tracks) {
            val d = hypot(hands[i].palmX - t.hand.palmX, hands[i].palmY - t.hand.palmY)
            val ratio = hands[i].handSize / t.hand.handSize
            if (d <= GATE && ratio in MIN_RATIO..MAX_RATIO) pairs.add(Triple(d, i, t))
        }
        // Tracks seen a moment ago claim their hands first, so a hand followed continuously (a bystander's) can
        // never be handed to a track that has been missing (the user's), however close it comes.
        for ((_, i, t) in pairs.sortedWith(compareBy({ nowMs - it.third.seenMs > RECENT_MS }, { it.first }))) {
            if (used[i] || t in seen) continue
            used[i] = true; seen.add(t); t.hand = hands[i]; t.seenMs = nowMs
        }
        for (i in hands.indices) if (!used[i]) Track(nextId++, hands[i], nowMs).also { tracks.add(it); seen.add(it) }
        return seen
    }

    private fun drop(why: Lost): Status { selectedId = -1; lost = why; return Status.LOST }

    private fun choose(seen: Set<Track>, nowMs: Long): Status {
        if (nowMs - selectingSince > SELECT_WINDOW_MS) { ambiguous = false; return Status.NONE } // nobody answered in time
        val usable = seen.filter { it.hand.handSize >= MIN_SIZE }
        val responded = usable.filter {
            it.openMs >= selectingSince && it.fistSince >= 0 && it.fistSince - it.openMs <= OPEN_BEFORE_MS &&
                nowMs - it.fistSince >= FIST_MS
        }
        if (responded.size == 1) { selectedId = responded[0].id; ambiguous = false; return Status.TRACKING }
        if (responded.size > 1) { ambiguous = true; selectingSince = nowMs } // everyone has to open and close again
        return when {
            ambiguous -> Status.AMBIGUOUS
            usable.isNotEmpty() -> Status.CONFIRM
            seen.isNotEmpty() -> Status.TOO_FAR
            else -> Status.SHOW
        }
    }
}
