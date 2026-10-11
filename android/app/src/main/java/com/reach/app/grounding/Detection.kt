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

    /** Finite, inside the frame, and not a sliver. */
    fun isValid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
        right - left >= MIN_SIDE && bottom - top >= MIN_SIDE

    companion object {
        const val MIN_SIDE = 0.02f
        fun clamped(l: Float, t: Float, r: Float, b: Float) =
            Box(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }
}

/** One raw detector hit, before it gets a stable id. */
data class Det(val label: String, val score: Float, val box: Box)

/**
 * What the app is willing to guide the hand to. EfficientDet-Lite0 is a COCO model: it can only
 * ever report the 80 COCO names in its embedded labels.txt (verified: there is no "can" class).
 * Edit DETECTED to change what is surfaced; a unit test checks every name against the model's labels.
 */
object SupportedClasses {
    val DETECTED: Set<String> = linkedSetOf(
        "person", "chair", "cup", "bottle", "wine glass", "bowl", "cell phone", "remote", "mouse",
        "keyboard", "laptop", "book", "scissors", "vase", "backpack", "handbag", "clock",
        "banana", "apple", "orange", "teddy bear"
    )

    /** Containers a canned/bottled drink may be reported as. Beverage identity is a color guess only. */
    val DRINK_CONTAINERS = setOf("bottle", "cup", "wine glass", "vase")

    private val BEVERAGES = mapOf("coke" to "coke", "cola" to "coke", "pepsi" to "pepsi",
        "sprite" to "sprite", "monster" to "monster")

    /** Spoken word -> COCO labels it may refer to. */
    private val ALIASES: Map<String, Set<String>> = mapOf(
        "person" to setOf("person"), "someone" to setOf("person"), "people" to setOf("person"),
        "chair" to setOf("chair"), "seat" to setOf("chair"),
        "cup" to setOf("cup"), "mug" to setOf("cup"),
        "glass" to setOf("wine glass", "cup"), "wine glass" to setOf("wine glass"),
        "bottle" to setOf("bottle"),
        "bowl" to setOf("bowl"),
        "phone" to setOf("cell phone"), "cell phone" to setOf("cell phone"), "mobile" to setOf("cell phone"),
        "remote" to setOf("remote"), "controller" to setOf("remote"),
        "mouse" to setOf("mouse"), "keyboard" to setOf("keyboard"),
        "laptop" to setOf("laptop"), "computer" to setOf("laptop"),
        "book" to setOf("book"), "scissors" to setOf("scissors"), "vase" to setOf("vase"),
        "backpack" to setOf("backpack"), "bag" to setOf("backpack", "handbag"),
        "handbag" to setOf("handbag"), "purse" to setOf("handbag"),
        "clock" to setOf("clock"),
        "banana" to setOf("banana"), "apple" to setOf("apple"), "orange" to setOf("orange"),
        "teddy bear" to setOf("teddy bear"), "teddy" to setOf("teddy bear")
    )

    /** Every label some spoken word can resolve to (used by tests to catch typos). */
    val SPEAKABLE_LABELS: Set<String> get() = ALIASES.values.flatten().toSet() + DRINK_CONTAINERS

    /** First supported object named in the utterance (longest phrase wins), or null. */
    fun resolve(utterance: String): TargetSpec? {
        val words = utterance.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val padded = " ${words.joinToString(" ")} "
        BEVERAGES.entries.firstOrNull { " ${it.key} " in padded }?.let {
            return TargetSpec(it.value, DRINK_CONTAINERS, it.value)
        }
        return ALIASES.keys.sortedByDescending { it.length }.firstOrNull { " $it " in padded }
            ?.let { TargetSpec(it, ALIASES.getValue(it)) }
    }
}

data class TargetSpec(val name: String, val labels: Set<String>, val beverage: String? = null) {
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

fun clarifyQuestion(spec: TargetSpec, count: Int) =
    if (count == 2) "I see two ${spec.name}s. Say left or right."
    else "I see $count ${spec.name}s. Say left, middle, or right."

/**
 * Gives detections ids that survive across frames: same label, nearest center, within maxJump.
 * Tracks that are not seen for ttlMs are forgotten. Ids are never reused.
 */
class CandidateTracker(private val ttlMs: Long = 1500, private val maxJump: Float = 0.2f) {
    private class Track(val id: Int, val label: String, var box: Box, var seenMs: Long)

    private val tracks = ArrayList<Track>()
    private var nextId = 0

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
        }
        for (i in dets.indices) if (ids[i] < 0) {
            val t = Track(nextId++, dets[i].label, dets[i].box, nowMs)
            tracks.add(t); ids[i] = t.id
        }
        return ids
    }
}

enum class TrackStatus { NONE, TRACKING, HOLDING, LOST }

/**
 * The one object the user asked for. Follows that instance by id; a brief miss is held
 * (a reaching hand can occlude the object), then the box is dropped. It only re-acquires when
 * exactly one object of the same class is visible near where the target was, so it never
 * quietly jumps to a different cup among several.
 */
class TargetLock(private val holdMs: Long = 1500, private val reacquireDist: Float = 0.3f) {
    var label = ""; private set
    var id = -1; private set
    /** Last known box; null once the target has been lost for longer than the hold. */
    var box: Box? = null; private set
    var status = TrackStatus.NONE; private set
    private var lastBox: Box? = null
    private var seenMs = 0L

    val active get() = label.isNotEmpty()

    @Synchronized fun lock(c: Candidate, nowMs: Long) {
        label = c.label; id = c.id; box = c.box; lastBox = c.box; seenMs = nowMs; status = TrackStatus.TRACKING
    }

    @Synchronized fun clear() {
        label = ""; id = -1; box = null; lastBox = null; status = TrackStatus.NONE
    }

    /** Call with each fresh detection set. Returns the target if it is visible right now. */
    @Synchronized fun update(cands: List<Candidate>, nowMs: Long): Candidate? {
        if (!active) return null
        cands.firstOrNull { it.id == id }?.let {
            box = it.box; lastBox = it.box; seenMs = nowMs; status = TrackStatus.TRACKING
            return it
        }
        if (nowMs - seenMs <= holdMs) { status = TrackStatus.HOLDING; return null }
        box = null; status = TrackStatus.LOST
        val same = cands.filter { it.label == label }
        val last = lastBox
        if (same.size == 1 && last != null && same[0].box.distTo(last) < reacquireDist) {
            lock(same[0], nowMs); return same[0]
        }
        return null
    }

    /** The box guidance may use at nowMs: null once the last sighting is older than the hold, even if
     *  no detection result has arrived since (a stalled detector must not keep steering the hand). */
    @Synchronized fun boxAt(nowMs: Long): Box? = box?.takeIf { nowMs - seenMs <= holdMs }
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
