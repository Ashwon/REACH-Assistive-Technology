package com.reach.app.grounding

import kotlin.math.abs

/**
 * Pure Kotlin (no android.* types) so the selection logic is unit-testable on the JVM.
 * Everything here works on normalized 0..1 frame coordinates.
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
    fun distTo(o: Box) = kotlin.math.hypot(centerX() - o.centerX(), centerY() - o.centerY())
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    /** Finite, inside the frame, and not a sliver. */
    fun isValid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
        right - left >= MIN_SIDE && bottom - top >= MIN_SIDE

    /** Not a near-whole-frame box: replaying dim frames on the A15, every junk box (laptop, tv, clock, airplane,
     *  surfboard...) covered >= 0.73 of the frame. Nothing that fills the view is a reachable target either. */
    fun isPlausible() = (right - left) * (bottom - top) <= MAX_AREA

    fun iou(o: Box): Float {
        val iw = minOf(right, o.right) - maxOf(left, o.left); val ih = minOf(bottom, o.bottom) - maxOf(top, o.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return inter / ((right - left) * (bottom - top) + (o.right - o.left) * (o.bottom - o.top) - inter)
    }

    companion object {
        const val MIN_SIDE = 0.02f
        const val MAX_AREA = 0.6f
        fun clamped(l: Float, t: Float, r: Float, b: Float) =
            Box(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }
}

/** One raw detector hit, before it gets a stable id. */
data class Det(val label: String, val score: Float, val box: Box)

/** Detector score a new object needs. Not lowered: on recorded A15 frames the junk sits at 0.10..0.31. */
const val MIN_SCORE = 0.35f

/** Why a raw detection is not tracked ("low", "invalid", "wholeframe", "darkflat"), or null when it is. A dark,
 *  featureless box (see [Appearance.darkFlat]) needs a strong score: a weak "laptop" on shadow is not a laptop. */
fun rejectReason(score: Float, box: Box, minScore: Float = MIN_SCORE, look: Appearance? = null): String? = when {
    score < minScore -> "low"
    !box.isValid() -> "invalid"
    !box.isPlausible() -> "wholeframe"
    look?.darkFlat == true && score < Candidate.STRONG_SCORE -> "darkflat"
    else -> null
}

/**
 * What the app is willing to guide the hand to: a deliberately small set (dataset/categories.json, kept in agreement
 * by CategorySpecTest). EfficientDet-Lite0 is a COCO model: it can only ever report the 80 COCO names in its embedded
 * labels.txt (verified: there is no "can" and no "laptop bag" class). Every other COCO class is still detected but
 * never offered as a target; it is tracked only so a spot the model calls two different things shows as a conflict.
 */
object SupportedClasses {
    val DETECTED: Set<String> = linkedSetOf("bottle", "laptop", "book", "backpack", "handbag", "cup")

    /** Containers a canned/bottled drink may be reported as. Beverage identity is a color guess only. */
    val DRINK_CONTAINERS = setOf("bottle", "cup")

    private val BEVERAGES = mapOf("coke" to "coke", "cola" to "coke", "pepsi" to "pepsi",
        "sprite" to "sprite", "monster" to "monster")

    /** Spoken word -> COCO labels it may refer to. Only true synonyms: a different object is never mapped here. */
    private val ALIASES: Map<String, Set<String>> = mapOf(
        "bottle" to setOf("bottle"), "water bottle" to setOf("bottle"), "flask" to setOf("bottle"),
        "laptop" to setOf("laptop"), "computer" to setOf("laptop"),
        "book" to setOf("book"),
        "backpack" to setOf("backpack"), "rucksack" to setOf("backpack"), "school bag" to setOf("backpack"),
        "handbag" to setOf("handbag"), "purse" to setOf("handbag"),
        "bag" to setOf("backpack", "handbag"),
        "cup" to setOf("cup"), "mug" to setOf("cup")
    )

    /** Objects users ask for that REACH will not find: no model class ("laptop bag", "can"), or a COCO class outside
     *  [DETECTED]. Listed so they get a clear answer instead of matching a shorter word ("laptop bag" -> "laptop"). */
    val UNSUPPORTED: Map<String, String> = mapOf(
        "laptop bag" to "laptop bag", "laptop case" to "laptop bag", "computer bag" to "laptop bag", "laptop sleeve" to "laptop bag",
        "soda can" to "can", "drink can" to "can", "tin can" to "can",
        "person" to "person", "someone" to "person", "people" to "person", "scissors" to "scissors",
        "phone" to "phone", "cell phone" to "phone", "mobile" to "phone", "glass" to "glass", "wine glass" to "glass",
        "vase" to "vase", "bowl" to "bowl", "chair" to "chair", "seat" to "chair", "remote" to "remote",
        "controller" to "remote", "mouse" to "mouse", "keyboard" to "keyboard", "clock" to "clock",
        "banana" to "banana", "apple" to "apple", "orange" to "orange", "teddy bear" to "teddy bear", "teddy" to "teddy bear"
    )

    /** Classes the model has that REACH deliberately never targets: people, and sharp objects for a reaching hand. */
    val REFUSED = setOf("person", "scissors")

    /** What to say when the user asks for a category REACH will not find. */
    fun cannotFind(spec: TargetSpec) = when (spec.name) {
        "person" -> "I only help find objects, not people."
        "scissors" -> "I don't guide hands to sharp things like scissors."
        "laptop bag" -> "I can't tell a laptop bag from other bags. Ask for a backpack or a handbag, or say find my bag. $SUPPORTED_LINE"
        else -> "I can't recognise ${if (spec.name[0] in "aeiou") "an" else "a"} ${spec.name} yet. $SUPPORTED_LINE"
    }

    const val SUPPORTED_LINE = "I can find a bottle, laptop, book, backpack, handbag, or cup."

    /** Every label some spoken word can resolve to (used by tests to catch typos). */
    val SPEAKABLE_LABELS: Set<String> get() = ALIASES.values.flatten().toSet() + DRINK_CONTAINERS

    /** First object named in the utterance (the longest phrase where several start there: "laptop bag", not "laptop"),
     *  or null. "the laptop next to my bottle" is a laptop. An unsupported one has no labels. */
    fun resolve(utterance: String): TargetSpec? {
        val words = utterance.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val padded = " ${words.joinToString(" ")} "
        val first = (BEVERAGES.keys + ALIASES.keys + UNSUPPORTED.keys)
            .mapNotNull { k -> padded.indexOf(" $k ").takeIf { it >= 0 }?.let { k to it } }
            .minWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenByDescending { it.first.length })?.first ?: return null
        BEVERAGES[first]?.let { return TargetSpec(it, DRINK_CONTAINERS, it) }
        return UNSUPPORTED[first]?.let { TargetSpec(it, emptySet()) } ?: TargetSpec(first, ALIASES.getValue(first))
    }
}

/** labels empty = a category the detector cannot recognise (see SupportedClasses.UNSUPPORTED). */
data class TargetSpec(val name: String, val labels: Set<String>, val beverage: String? = null) {
    val supported get() = labels.isNotEmpty()
    fun matches(c: Candidate) = c.label in labels && (beverage == null || c.beverage == beverage)
}

sealed interface TargetPick {
    object None : TargetPick
    data class One(val c: Candidate) : TargetPick
    /** Two or more matches, ordered left to right. Never resolved silently. */
    data class Ambiguous(val options: List<Candidate>) : TargetPick
}

fun pickTarget(spec: TargetSpec, cands: List<Candidate>): TargetPick {
    val m = cands.filter { spec.matches(it) }.sortedBy { it.box.centerX() }
    return when (m.size) { 0 -> TargetPick.None; 1 -> TargetPick.One(m[0]); else -> TargetPick.Ambiguous(m) }
}

/** Resolves "left" / "right" / "middle" among options ordered left to right; null if no such word. */
fun pickByPosition(options: List<Candidate>, spoken: String): Candidate? {
    val w = spoken.lowercase().split(Regex("[^a-z]+")).toSet()
    return when {
        options.isEmpty() -> null
        "left" in w -> options.first()
        "right" in w -> options.last()
        "middle" in w || "center" in w || "centre" in w -> options.minByOrNull { abs(it.box.centerX() - 0.5f) }
        else -> null
    }
}

private val NEAR_WORDS = listOf("next to", "near", "nearest to", "nearest", "closest to", "beside")

/** "the one next to my bottle" -> (the words before "next to", the bottle spec); null without a word for nearness. */
fun nearAnchor(spoken: String): Pair<String, TargetSpec>? {
    val t = " " + spoken.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.joinToString(" ") + " "
    val (at, w) = NEAR_WORDS.mapNotNull { w -> t.indexOf(" $w ").takeIf { it >= 0 }?.let { it to w } }.minByOrNull { it.first } ?: return null
    val anchor = SupportedClasses.resolve(t.substring(at + w.length + 1)) ?: return null
    return t.substring(0, at) to anchor
}

/** The option clearly nearest [anchor] (its centre at most [NEAR_RATIO] of the runner-up's distance), or null. */
fun pickNear(options: List<Candidate>, anchor: Candidate): Candidate? {
    val d = options.filter { it.id != anchor.id }.map { it to it.box.distTo(anchor.box) }.sortedBy { it.second }
    val best = d.firstOrNull() ?: return null
    return best.first.takeIf { d.size == 1 || best.second <= NEAR_RATIO * d[1].second }
}

const val NEAR_RATIO = 0.6f

fun clarifyQuestion(spec: TargetSpec, count: Int) =
    if (count == 2) "I see two ${spec.name}s. Do you want the one on the left or the right?"
    else "I see $count ${spec.name}s. Do you want the one on the left, in the middle, or on the right?"

/** Where a box is in the camera view, in words: only ever a statement about the current image. */
fun Box.sideWord() = when { centerX() < 0.4f -> "on the left"; centerX() > 0.6f -> "on the right"; else -> "in the middle" }

/**
 * Gives detections ids that survive across frames: same label, nearest center, within maxJump.
 * Tracks that are not seen for ttlMs are forgotten. Ids are never reused.
 * Also keeps the evidence for each track: a smoothed score, whether it has been seen in [confirmHits]
 * detector runs (one frame is never enough to accept an object), and whether a different class was
 * reported for the same spot (IoU >= CONFLICT_IOU) within ttlMs, i.e. the model cannot decide what it is.
 */
class CandidateTracker(private val ttlMs: Long = 1500, private val maxJump: Float = 0.2f, private val confirmHits: Int = 3) {
    private class Track(val id: Int, val label: String, var box: Box, var seenMs: Long, var score: Float, var hits: Int = 1) {
        var rival: String? = null; var rivalMs = 0L
    }

    data class Evidence(val score: Float, val confirmed: Boolean, val conflict: String?)

    private val tracks = ArrayList<Track>()
    private var nextId = 0
    private var nowMs = 0L

    /** The conflict is remembered for ttlMs: box jitter must not make a disputed spot look settled for a frame. */
    fun evidence(id: Int): Evidence? {
        val t = tracks.firstOrNull { it.id == id } ?: return null
        return Evidence(t.score, t.hits >= confirmHits, t.rival?.takeIf { nowMs - t.rivalMs <= ttlMs })
    }

    private companion object { const val CONFLICT_IOU = 0.5f; const val SCORE_ALPHA = 0.5f }

    fun assign(dets: List<Det>, nowMs: Long): IntArray {
        tracks.removeAll { nowMs - it.seenMs > ttlMs }
        val ids = IntArray(dets.size) { -1 }
        val usedTracks = HashSet<Track>()
        // Globally closest pairs first, so two neighbouring cups cannot swap ids.
        val pairs = ArrayList<Triple<Float, Int, Track>>()
        for (i in dets.indices) for (t in tracks) {
            if (t.label == dets[i].label) pairs.add(Triple(t.box.distTo(dets[i].box), i, t))
        }
        for ((d, i, t) in pairs.sortedBy { it.first }) {
            if (d > maxJump || ids[i] >= 0 || t in usedTracks) continue
            ids[i] = t.id; usedTracks.add(t); t.box = dets[i].box; t.seenMs = nowMs
            t.hits++; t.score += SCORE_ALPHA * (dets[i].score - t.score)
        }
        for (i in dets.indices) if (ids[i] < 0) {
            val t = Track(nextId++, dets[i].label, dets[i].box, nowMs, dets[i].score)
            tracks.add(t); ids[i] = t.id
        }
        this.nowMs = nowMs
        for (t in tracks) tracks.filter { it.label != t.label && it.box.iou(t.box) >= CONFLICT_IOU }.maxByOrNull { it.score }
            ?.let { t.rival = it.label; t.rivalMs = nowMs }
        return ids
    }
}

/**
 * The locked object's tracking state. OCCLUDED: not detected while a hand is over where it was. TEMPORARILY_MISSING:
 * not detected, nothing over it (a detector miss, or the camera moved). LOST: hidden past the grace period; the
 * identity (class, id, last box, look) is still kept and can be re-found, but nothing is guided towards it.
 */
enum class TrackStatus { NONE, TRACKING, OCCLUDED, TEMPORARILY_MISSING, LOST }

/**
 * The one object the user asked for. Follows that instance by its tracker id; while it is not detected its identity
 * is kept (class, id, last box, motion, look) and the status says why. A box is handed to guidance only while it is
 * fresh ([freshMs]): a remembered position is not a measurement.
 *
 * Re-finding it under a new tracker id (after a long occlusion the tracker forgets the id) needs ALL of: same class;
 * a settled, undisputed detection; near its predicted position or overlapping its last box (skipped only after a
 * turn, see [relaxPosition]); colours similar to the locked object's when both are known; never an object that was
 * in view at the same time as the target (that is a different object); and exactly one detection passing. So it
 * never quietly moves to the bag next to the bottle or to a second bottle.
 */
class TargetLock(
    private val freshMs: Long = 1000,          // one missed detector run is tolerated, two are not
    private val occludedGraceMs: Long = 8000,  // a hand may cover it this long while reaching
    private val missingGraceMs: Long = 2500,   // not detected and not covered this long -> LOST
    private val reacquireDist: Float = 0.3f,
    private val minLookSimilarity: Float = 0.5f
) {
    var label = ""; private set
    var id = -1; private set
    /** Last known box. Kept while the target is locked, even when lost: identity, not a position measurement. */
    var box: Box? = null; private set
    var look: Appearance? = null; private set
    /** Counts re-finds under a new tracker id (diagnostics and logs). */
    @Volatile var reacquisitions = 0; private set
    /** A hand has been over the target since it was last seen (for "it's blocked from view"). */
    val wasOccluded get() = overlapMs > seenMs
    private var seenMs = 0L
    private var overlapMs = -1L
    private var clock = 0L
    private var vx = 0f; private var vy = 0f
    private var relaxed = false
    private val coVisible = HashSet<Int>()

    val active get() = label.isNotEmpty()
    val status get() = statusAt(clock)

    @Synchronized fun lock(c: Candidate, nowMs: Long) {
        label = c.label; id = c.id; box = c.box; look = c.look; seenMs = nowMs; clock = maxOf(clock, nowMs)
        vx = 0f; vy = 0f; overlapMs = -1L; relaxed = false; coVisible.clear()
    }

    @Synchronized fun clear() {
        label = ""; id = -1; box = null; look = null; relaxed = false; coVisible.clear()
    }

    /** Camera thread, every frame: whether any hand is over the target's last box. */
    @Synchronized fun noteHands(overlap: Boolean, nowMs: Long) {
        clock = maxOf(clock, nowMs)
        if (overlap && active) overlapMs = nowMs
    }

    /** After the user turned away and back: the object may now be anywhere in view, so drop the position gate once. */
    @Synchronized fun relaxPosition() { relaxed = true }

    /** Call with each fresh detection set. Returns the target if it is visible right now. */
    @Synchronized fun update(cands: List<Candidate>, nowMs: Long): Candidate? {
        clock = maxOf(clock, nowMs)
        if (!active) return null
        cands.firstOrNull { it.id == id }?.let { seen(it, nowMs, cands); return it }
        val last = box ?: return null
        val ref = look
        val dt = minOf(nowMs - seenMs, 500L).toFloat()
        val px = last.centerX() + vx * dt; val py = last.centerY() + vy * dt
        val same = cands.filter { it.label == label && it.id !in coVisible } // co-visible ones are known to be others
        val passing = same.filter { c ->
            c.confirmed && c.conflict == null &&
                (relaxed || kotlin.math.hypot(c.box.centerX() - px, c.box.centerY() - py) < reacquireDist || c.box.iou(last) >= 0.3f) &&
                (ref == null || c.look == null || ref.similarity(c.look) >= minLookSimilarity)
        }
        if (passing.size != 1 || same.count { it.confirmed } != 1) return null // two candidates: never a guess
        id = passing[0].id; reacquisitions++; relaxed = false; vx = 0f; vy = 0f
        seen(passing[0], nowMs, cands)
        return passing[0]
    }

    private fun seen(c: Candidate, nowMs: Long, all: List<Candidate>) {
        val b = box
        val dt = (nowMs - seenMs).toFloat()
        if (b != null && dt in 20f..600f) { // motion only from consecutive sightings, never from duplicate timestamps
            vx = 0.5f * vx + 0.5f * (c.box.centerX() - b.centerX()) / dt
            vy = 0.5f * vy + 0.5f * (c.box.centerY() - b.centerY()) / dt
        }
        box = c.box; seenMs = nowMs
        if (c.look != null) look = c.look
        all.forEach { if (it.id != c.id) coVisible.add(it.id) }
    }

    @Synchronized fun statusAt(nowMs: Long): TrackStatus {
        if (!active) return TrackStatus.NONE
        if (nowMs - seenMs <= freshMs) return TrackStatus.TRACKING
        val covered = wasOccluded && nowMs - overlapMs <= OVERLAP_RECENT_MS
        return when {
            covered -> if (nowMs - seenMs <= occludedGraceMs) TrackStatus.OCCLUDED else TrackStatus.LOST
            nowMs - maxOf(seenMs, overlapMs) <= missingGraceMs -> TrackStatus.TEMPORARILY_MISSING
            else -> TrackStatus.LOST
        }
    }

    /** The box guidance may use at nowMs: null unless the target was seen within [freshMs], even if no detection
     *  result has arrived since (a stalled detector must not keep steering the hand). */
    @Synchronized fun boxAt(nowMs: Long): Box? = box?.takeIf { active && nowMs - seenMs <= freshMs }

    /** How long since the target was last detected. */
    @Synchronized fun unseenMs(nowMs: Long) = if (active) nowMs - seenMs else 0L

    private companion object { const val OVERLAP_RECENT_MS = 500L }
}

/**
 * Runs the detector off the hand-tracking thread: at most one frame in flight (bounded queue of one,
 * newer frames are simply skipped, so results arrive in frame order), at most one start per intervalMs,
 * and results whose frame is older than staleMs by the time they finish are rejected.
 */
class DetectorGate(private val intervalMs: Long, private val staleMs: Long) {
    private var inFlight = false
    private var lastStart = Long.MIN_VALUE / 2
    var staleDropped = 0; private set

    @Synchronized fun tryStart(nowMs: Long): Boolean {
        if (inFlight || nowMs - lastStart < intervalMs) return false
        inFlight = true; lastStart = nowMs
        return true
    }

    /** Must be called once per successful tryStart (also on failure). True = apply this result. */
    @Synchronized fun finish(frameMs: Long, nowMs: Long): Boolean {
        inFlight = false
        if (nowMs - frameMs > staleMs) { staleDropped++; return false }
        return true
    }
}

/**
 * What the user may pick right now: current detections plus any seen within graceMs. A score hovering
 * at the threshold makes objects blink in and out; a spoken request should not fail on a blink.
 */
class RecentCandidates(private val graceMs: Long = 1000) {
    private val seen = LinkedHashMap<Int, Pair<Candidate, Long>>()

    fun update(current: List<Candidate>, nowMs: Long): List<Candidate> {
        seen.values.removeAll { nowMs - it.second > graceMs }
        current.forEach { seen[it.id] = it to nowMs }
        return seen.values.map { it.first }
    }
}
