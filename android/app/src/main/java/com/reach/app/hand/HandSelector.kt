package com.reach.app.hand

import kotlin.math.hypot

/** USER_LEFT_HAND / USER_RIGHT_HAND: the user's own hands, as the user named them at enrolment. */
enum class Side {
    LEFT, RIGHT;
    val other get() = if (this == LEFT) RIGHT else LEFT
    val word get() = name.lowercase()
}

/**
 * Enrols the user's two hands and keeps following each one independently. Separate from object detection.
 *
 * Enrolment is a deliberate act, one side at a time: after beginEnrollment(side) (the user is asked to raise that
 * hand and close it into a fist), the hand seen OPEN after the prompt and then held as a FIST for FIST_MS becomes
 * that side's hand. A hand already enrolled as the other side never answers, and a hand MediaPipe confidently labels
 * the same as the other enrolled hand is refused (SAME_AS_OTHER: two hands of one person are never both "Left").
 * Other hands making the gesture too make it AMBIGUOUS and the user is asked again. Nearest hand, first detection and
 * MediaPipe's list order are never used.
 *
 * Left/right come from the user, not from MediaPipe: its handedness label assumes a mirrored selfie image and this
 * is a rear camera, so the label is not mapped to a side. Instead the label each hand had AT ENROLMENT is stored and
 * later only compared with itself (supporting evidence, never identity on its own).
 *
 * Per side ([Slot]): TRACKED (in view), MISSING (out of view, identity kept for [Config.graceMs]), UNCERTAIN (another
 * hand came close enough that tracks could swap), NEEDS_RECOVERY (identity cannot be recovered safely: the user makes
 * the gesture with that hand again). One side's trouble never resets the other side.
 *
 * Every visible hand has its own track (palm position predicted from recent motion + size continuity + handedness
 * veto; tracks seen a moment ago claim their hands first), so a bystander's hand stays on its own track.
 * A missing hand comes back either
 *  - within [Config.shortGapMs] by continuity: within [Config.reacquireGate] of its predicted palm, similar size,
 *    confident, label not contradicting, and the only such hand; or
 *  - within [Config.graceMs] on a NEW track: born after the hand was last seen (a hand that was in view at the same
 *    time as the user's can never be it), labelled like the enrolled hand in every confident sighting, of a similar
 *    size, followed for [Config.returnConfirmMs], and the only hand that qualifies.
 * More than one candidate -> NEEDS_RECOVERY for that side only. Being near the old spot is never enough on its own.
 *
 * Not biometric: if the user's hand is out of view and another person's hand of the same side and size enters on
 * its own, it can pass these gates. That limit is why the grace period is bounded and why guidance pauses on doubt.
 */
class HandSelector(private val cfg: Config = Config()) {
    /** Enrolment prompt state (while [enrolling] != null). */
    enum class Status { NONE, SHOW, TOO_FAR, CONFIRM, AMBIGUOUS, SAME_AS_OTHER }
    enum class SlotState { NOT_ENROLLED, TRACKED, MISSING, UNCERTAIN, NEEDS_RECOVERY }

    /** Grace periods and association / reacquisition rules. Distances in frame units. */
    data class Config(
        val graceMs: Long = GRACE_MS,           // an enrolled hand out of view this long needs recovery
        val trackTtlMs: Long = TRACK_TTL_MS,    // a hand that is not the user's is forgotten after this
        val recentMs: Long = RECENT_MS,         // a track seen this recently is "continuous"
        val gate: Float = GATE,                 // max jump from the predicted palm for a continuous track
        val minRatio: Float = MIN_RATIO, val maxRatio: Float = MAX_RATIO,
        val shortGapMs: Long = 1000L,           // continuity reacquisition only within this gap
        val reacquireGate: Float = 0.15f,       // ...and this close to its predicted palm
        val reacquireMinRatio: Float = 0.75f, val reacquireMaxRatio: Float = 1.33f,
        val minReacquireScore: Float = 0.6f,    // MediaPipe's per-hand confidence
        val handednessMin: Float = 0.8f,        // a label counts only at this confidence
        val maxPredictMs: Long = 300L,          // motion is extrapolated at most this far
        val returnMinRatio: Float = 0.6f, val returnMaxRatio: Float = 1.6f, // new-track return: size vs last known
        val returnConfirmMs: Long = 300L        // new-track return: followed this long before it is adopted
    )

    companion object {
        const val MIN_SIZE = 0.06f      // wrist->middle knuckle (frame units); smaller = > ~1.5 m away, landmarks too coarse
        const val FIST_MS = 300L        // the fist must be held this long
        const val OPEN_BEFORE_MS = 3000L // ...and follow an open hand seen after the prompt, within this time
        const val SELECT_WINDOW_MS = 20_000L // a gesture answers a recent prompt; after this, stop asking
        const val GRACE_MS = 15_000L    // enrolled hand out of view longer than this: ask for the gesture again
        const val TRACK_TTL_MS = 2000L
        const val GATE = 0.3f           // max palm jump between sightings, frame units
        const val RECENT_MS = 250L      // ~3 frames: a track seen this recently is "continuous"
        const val MIN_RATIO = 0.5f; const val MAX_RATIO = 2f // hand size change between sightings
        const val MIN_DT_MS = 20L       // sightings closer than this in time do not update the velocity

        /** Palms this close could swap tracks: identity can no longer be guaranteed. */
        fun tooClose(a: HandResult, b: HandResult) =
            hypot(a.palmX - b.palmX, a.palmY - b.palmY) < maxOf(0.08f, 0.6f * a.handSize)
    }

    private class Track(val id: Int, var hand: HandResult, var seenMs: Long) {
        val bornMs = seenMs
        var openMs = -1L; var fistSince = -1L
        var vx = 0f; var vy = 0f // palm velocity, frame units per ms (EMA)
        val labels = HashSet<String>() // every confident handedness label this track has had
    }

    /** One of the user's hands. Read-only outside this class. */
    class Slot internal constructor(val side: Side) {
        var state = SlotState.NOT_ENROLLED; internal set
        var trackId = -1; internal set
        var label: String? = null; internal set    // MediaPipe's label for this hand at enrolment; null if unsure
        var size = 0f; internal set                // hand size, smoothed while tracked
        var last: HandResult? = null; internal set // last sighting
        var seenMs = 0L; internal set
        var reason = ""; internal set              // why UNCERTAIN / NEEDS_RECOVERY
        var via = ""; internal set                 // how it was last (re)acquired: enrolled / continuity / return
        internal var closeId = -1; internal var closeLabel: String? = null
        val enrolled get() = state != SlotState.NOT_ENROLLED
        /** Usable for guidance: identity known (TRACKED or briefly MISSING). */
        val trusted get() = state == SlotState.TRACKED || state == SlotState.MISSING
        fun missingMs(nowMs: Long) = if (state == SlotState.MISSING) nowMs - seenMs else 0L
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 0
    private val slots = mapOf(Side.LEFT to Slot(Side.LEFT), Side.RIGHT to Slot(Side.RIGHT))
    private val visible = HashMap<Side, HandResult>()
    private var selectingSince = 0L
    private var windowFrom = 0L
    private var retry: Status? = null // AMBIGUOUS / SAME_AS_OTHER: said until an enrolment succeeds
    private var nowMs = 0L
    /** Side being enrolled, or null. */
    var enrolling: Side? = null; private set
    /** Set for the one update in which [Side] was enrolled. */
    var enrolled: Side? = null; private set
    var status = Status.NONE; private set
    /** Hands in view this frame that are not a TRACKED enrolled hand (drawn dimmed, never guided). */
    var others: List<HandResult> = emptyList(); private set

    fun slot(side: Side) = slots.getValue(side)
    /** The [side] hand if it is TRACKED and visible this frame, else HandResult.NONE. */
    fun hand(side: Side) = visible[side] ?: HandResult.NONE
    val anyEnrolled get() = slots.values.any { it.enrolled }

    /** Start (or restart) enrolling [side]: wait for the gesture. The current enrolment of that side stays until then.
     *  [heardAtMs]: when the user will have heard the prompt (after a long introduction); the answer window runs from then. */
    fun beginEnrollment(side: Side, nowMs: Long, heardAtMs: Long = nowMs) {
        enrolling = side; selectingSince = nowMs; windowFrom = maxOf(nowMs, heardAtMs); retry = null; status = Status.SHOW
    }

    fun cancelEnrollment() { enrolling = null; status = Status.NONE }

    /** Forget both hands and every track (positions may be from another camera). */
    fun clear() {
        for (s in slots.values) { s.state = SlotState.NOT_ENROLLED; s.trackId = -1; s.label = null; s.last = null; s.reason = "" }
        tracks.clear(); visible.clear(); others = emptyList(); cancelEnrollment()
    }

    fun update(hands: List<HandResult>, nowMs: Long) {
        this.nowMs = nowMs; enrolled = null
        val seen = associate(hands, nowMs)
        for (t in tracks) when {
            t !in seen -> t.fistSince = -1L
            t.hand.pose == Pose.OPEN -> { t.openMs = nowMs; t.fistSince = -1L }
            t.hand.pose == Pose.FIST -> if (t.fistSince < 0) t.fistSince = nowMs
            else -> t.fistSince = -1L
        }
        enrolling?.let { status = choose(it, seen, nowMs) }
        for (s in slots.values) updateSlot(s, seen, nowMs)
        visible.clear()
        for (s in slots.values) if (s.state == SlotState.TRACKED) track(s.trackId)?.takeIf { it in seen }?.let { visible[s.side] = it.hand }
        others = seen.filter { t -> visible.values.none { it === t.hand } }.map { it.hand }
    }

    private fun track(id: Int) = if (id < 0) null else tracks.firstOrNull { it.id == id }
    private fun slotOf(t: Track) = slots.values.firstOrNull { it.trackId == t.id }
    private fun confident(h: HandResult) = h.handedness != null && h.score >= cfg.handednessMin
    private fun labelIs(h: HandResult, label: String?) = label != null && confident(h) && h.handedness == label

    private fun updateSlot(s: Slot, seen: Set<Track>, nowMs: Long) {
        if (s.state == SlotState.NOT_ENROLLED || s.state == SlotState.NEEDS_RECOVERY) return
        val t = track(s.trackId)
        if (t != null && t in seen) {
            s.last = t.hand; s.seenMs = nowMs; s.size += 0.2f * (t.hand.handSize - s.size)
            val near = seen.firstOrNull { it !== t && tooClose(t.hand, it.hand) }
            if (near != null) { // either track could now be following the other hand
                s.state = SlotState.UNCERTAIN; s.reason = "another hand is too close"; s.closeId = near.id
                if (confident(near.hand)) s.closeLabel = near.hand.handedness
                return
            }
            if (s.state == SlotState.UNCERTAIN) {
                // Separated again. Safe only if the two hands were told apart by their labels: ours is still labelled
                // like the enrolled hand and the one that came close was confidently labelled the other way.
                if (labelIs(t.hand, s.label) && s.closeLabel != null && s.closeLabel != s.label) s.via = "labels after crossing"
                else return needsRecovery(s, "hands crossed and I can't tell them apart")
            }
            s.state = SlotState.TRACKED; s.reason = ""; s.closeId = -1; s.closeLabel = null
            return
        }
        if (s.state == SlotState.UNCERTAIN) return needsRecovery(s, "it left view while another hand was close")
        if (s.trackId >= 0 && t == null) s.trackId = -1
        s.state = SlotState.MISSING
        if (nowMs - s.seenMs > cfg.graceMs) return needsRecovery(s, "it was out of view too long")
        tryReturn(s, seen, nowMs)
    }

    /** Re-adopts a NEW track as [s]'s hand when it is the only hand that can be it (see the class comment). */
    private fun tryReturn(s: Slot, seen: Set<Track>, nowMs: Long) {
        val otherLabel = slot(s.side.other).label
        if (s.label == null || s.label == otherLabel) return // no label evidence that tells the user's hands apart
        val passing = seen.filter { t ->
            slotOf(t) == null && t.bornMs > s.seenMs && t.labels == setOf(s.label) && labelIs(t.hand, s.label) &&
                t.hand.score >= cfg.minReacquireScore && (t.hand.handSize / s.size) in cfg.returnMinRatio..cfg.returnMaxRatio
        }
        when {
            passing.size > 1 -> needsRecovery(s, "more than one hand could be your ${s.side.word} hand")
            passing.size == 1 && nowMs - passing[0].bornMs >= cfg.returnConfirmMs -> {
                s.trackId = passing[0].id; s.state = SlotState.TRACKED; s.via = "return"; s.reason = ""
                s.last = passing[0].hand; s.seenMs = nowMs
            }
        }
    }

    private fun needsRecovery(s: Slot, why: String) {
        s.state = SlotState.NEEDS_RECOVERY; s.reason = why; s.trackId = -1; s.closeId = -1; s.closeLabel = null
    }

    private fun predicted(t: Track, nowMs: Long): Pair<Float, Float> {
        val dt = minOf(nowMs - t.seenMs, cfg.maxPredictMs).toFloat()
        return Pair(t.hand.palmX + t.vx * dt, t.hand.palmY + t.vy * dt)
    }

    /** Distance from [t]'s predicted palm to [h], or null if [h] cannot be [t]'s hand at all. */
    private fun fit(t: Track, h: HandResult, nowMs: Long, gate: Float, minR: Float, maxR: Float): Float? {
        val (px, py) = predicted(t, nowMs)
        val d = hypot(h.palmX - px, h.palmY - py)
        val ratio = h.handSize / t.hand.handSize
        val sidesDisagree = h.handedness != null && t.hand.handedness != null && h.handedness != t.hand.handedness &&
            h.score >= cfg.handednessMin && t.hand.score >= cfg.handednessMin
        return d.takeIf { d <= gate && ratio in minR..maxR && !sidesDisagree }
    }

    private fun claim(t: Track, h: HandResult, nowMs: Long) {
        val dt = nowMs - t.seenMs
        // Velocity only from continuous sightings, never across a gap; and not from (near-)duplicate timestamps,
        // where a small palm jump divided by ~1 ms would throw the prediction off the frame.
        if (dt in MIN_DT_MS..cfg.recentMs) {
            t.vx = 0.5f * t.vx + 0.5f * (h.palmX - t.hand.palmX) / dt
            t.vy = 0.5f * t.vy + 0.5f * (h.palmY - t.hand.palmY) / dt
        } else if (dt > cfg.recentMs) { t.vx = 0f; t.vy = 0f }
        t.hand = h; t.seenMs = nowMs
        if (confident(h)) t.labels.add(h.handedness!!)
    }

    private fun associate(hands: List<HandResult>, nowMs: Long): Set<Track> {
        // A missing enrolled hand keeps its track (and so its motion) while its slot still points at it.
        tracks.removeAll { nowMs - it.seenMs > cfg.trackTtlMs && slotOf(it) == null }
        val seen = HashSet<Track>()
        val used = BooleanArray(hands.size)
        fun greedy(among: List<Track>) {
            val pairs = ArrayList<Triple<Float, Int, Track>>()
            for (i in hands.indices) if (!used[i]) for (t in among)
                fit(t, hands[i], nowMs, cfg.gate, cfg.minRatio, cfg.maxRatio)?.let { pairs.add(Triple(it, i, t)) }
            for ((_, i, t) in pairs.sortedBy { it.first }) {
                if (used[i] || t in seen) continue
                used[i] = true; seen.add(t); claim(t, hands[i], nowMs)
            }
        }
        val (recent, gone) = tracks.partition { nowMs - it.seenMs <= cfg.recentMs }
        // 1. Tracks seen a moment ago claim their hands first, so a hand followed continuously (a bystander's) can
        //    never be handed to a track that has been missing (the user's), however close it comes.
        greedy(recent)
        // 2. A briefly missing enrolled hand comes back by continuity only through the strict gates, and only if
        //    exactly one hand passes them and no other missing track has a better claim on it.
        for (sel in gone) {
            val s = slotOf(sel) ?: continue
            if (nowMs - sel.seenMs > cfg.shortGapMs) continue // longer gaps: only as a new track (tryReturn)
            val fits = hands.indices.filter { !used[it] }.mapNotNull { i ->
                fit(sel, hands[i], nowMs, cfg.reacquireGate, cfg.reacquireMinRatio, cfg.reacquireMaxRatio)
                    ?.takeIf { hands[i].score >= cfg.minReacquireScore }?.let { i to it }
            }
            val contested = fits.singleOrNull()?.let { (i, d) ->
                gone.any { it !== sel && (fit(it, hands[i], nowMs, cfg.gate, cfg.minRatio, cfg.maxRatio) ?: Float.MAX_VALUE) < d }
            } ?: false
            when {
                fits.size > 1 || contested -> needsRecovery(s, "two hands appeared where your ${s.side.word} hand was")
                fits.size == 1 -> { val i = fits[0].first; used[i] = true; seen.add(sel); claim(sel, hands[i], nowMs); s.via = "continuity" }
            }
        }
        // 3. Other briefly-missing tracks (never an enrolled one) pick up what is left.
        greedy(gone.filter { slotOf(it) == null })
        for (i in hands.indices) if (!used[i]) Track(nextId++, hands[i], nowMs).also {
            if (confident(hands[i])) it.labels.add(hands[i].handedness!!)
            tracks.add(it); seen.add(it)
        }
        return seen
    }

    private fun choose(side: Side, seen: Set<Track>, nowMs: Long): Status {
        if (nowMs - maxOf(selectingSince, windowFrom) > SELECT_WINDOW_MS) { enrolling = null; return Status.NONE } // nobody answered in time
        val other = slot(side.other)
        val candidates = seen.filter { it.id != other.trackId } // the other enrolled hand is not an answer
        val usable = candidates.filter { it.hand.handSize >= MIN_SIZE }
        val responded = usable.filter {
            it.openMs >= selectingSince && it.fistSince >= 0 && it.fistSince - it.openMs <= OPEN_BEFORE_MS &&
                nowMs - it.fistSince >= FIST_MS
        }
        if (responded.size == 1) {
            val t = responded[0]
            if (other.label != null && labelIs(t.hand, other.label)) { // the other hand again, or a misread: ask again
                selectingSince = nowMs; retry = Status.SAME_AS_OTHER; return Status.SAME_AS_OTHER
            }
            val s = slot(side)
            s.trackId = t.id; s.state = SlotState.TRACKED; s.reason = ""; s.via = "enrolled"
            s.label = t.hand.handedness?.takeIf { confident(t.hand) }
            s.size = t.hand.handSize; s.last = t.hand; s.seenMs = nowMs
            enrolling = null; retry = null; enrolled = side
            return Status.NONE
        }
        if (responded.size > 1) { retry = Status.AMBIGUOUS; selectingSince = nowMs } // everyone has to open and close again
        return retry ?: when {
            usable.isNotEmpty() -> Status.CONFIRM
            candidates.isNotEmpty() -> Status.TOO_FAR
            else -> Status.SHOW
        }
    }

    /** One line per side for the diagnostics panel and the log. */
    fun describe(nowMs: Long = this.nowMs): String = slots.values.joinToString("  ") { s ->
        "${s.side.name[0]}:${s.state}" + (if (s.trackId >= 0) " #${s.trackId}" else "") +
            (s.last?.let { " conf=%.2f".format(it.score) } ?: "") + " label=${s.label ?: "?"}" +
            (if (s.state == SlotState.MISSING) " missing=${s.missingMs(nowMs)}ms" else "") +
            (if (s.reason.isNotEmpty()) " (${s.reason})" else "") + (if (s.via.isNotEmpty()) " via=${s.via}" else "")
    }
}
