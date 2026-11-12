package com.reach.app.eval

import com.reach.app.Mode
import com.reach.app.ReachCore
import com.reach.app.grounding.Box
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.CandidateTracker
import com.reach.app.grounding.Det
import com.reach.app.grounding.MIN_SCORE
import com.reach.app.grounding.SupportedClasses
import com.reach.app.grounding.TargetLock
import com.reach.app.grounding.rejectReason
import com.reach.app.hand.HandSelector
import com.reach.app.hand.Side
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.phrase

data class Counts(var tp: Int = 0, var fp: Int = 0, var fn: Int = 0, var negImages: Int = 0, var fpOnNeg: Int = 0) {
    val precision get() = if (tp + fp == 0) Double.NaN else tp.toDouble() / (tp + fp)
    val recall get() = if (tp + fn == 0) Double.NaN else tp.toDouble() / (tp + fn)
    /** False positives per image that contains none of this category (e.g. false laptops on laptop-free images). */
    val fpPerNegImage get() = if (negImages == 0) Double.NaN else fpOnNeg.toDouble() / negImages
}

/**
 * Scores one predictions file against one labelled dataset. Detection metrics at three levels:
 *  - "model":  every raw detection >= MIN_SCORE (what the app surfaced before temporal confirmation existed)
 *  - "app":    what the app now offers as a target (admitted, tracked, seen in enough frames)
 *  - "sure":   what the app names without hedging (also no class conflict, not low light, score >= STRONG)
 * plus AP@0.5, confusion, frame-to-frame stability, target locking, hand identity, reached/speech, latency.
 * Everything app-level runs the app's own code (CandidateTracker, TargetLock, HandSelector, ReachCore).
 * Detections the phone measured pixels for (luma/contrast, replays since 2026-10-13) get the app's pixel checks: a weak
 * box on a dark, featureless region is dropped and a dark box is never "sure". Older predictions files skip them.
 */
class VisionEval(private val spec: CategorySpec, private val frames: List<Frame>, private val preds: Predictions) {
    private val scored = frames.filter { it.file in preds.frames }
    private fun p(f: Frame) = preds.frames.getValue(f.file)
    private val sequences = scored.groupBy { it.sequence }

    /** The app's candidates per image, from running each sequence through the grounder's logic in frame order. */
    val appCandidates: Map<String, List<Candidate>> = HashMap<String, List<Candidate>>().also { out ->
        for ((_, seq) in sequences) {
            val tracker = CandidateTracker()
            for (f in seq) {
                val pf = p(f)
                // As LocalDetectorGrounder: pixels only for classes the app could surface.
                val kept = pf.dets.mapNotNull { d ->
                    val look = d.look.takeIf { d.label in SupportedClasses.DETECTED }
                    if (rejectReason(d.score, d.box, look = look) == null) Det(d.label, d.score, d.box) to look else null
                }
                val ids = tracker.assign(kept.map { it.first }, f.ms)
                out[f.file] = kept.indices.filter { kept[it].first.label in SupportedClasses.DETECTED }.map { i ->
                    val (d, look) = kept[i]; val ev = tracker.evidence(ids[i])!!
                    Candidate(ids[i], d.label, "unknown", d.box, ev.score, null, ev.confirmed, ev.conflict,
                        pf.luma < Candidate.LOW_LIGHT_LUMA || look?.darkFlat == true, look, f.ms)
                }
            }
        }
    }

    private fun modelLevel(f: Frame) = p(f).dets.filter { it.score >= MIN_SCORE && it.box.isValid() }
        .mapNotNull { d -> spec.categoryOf(d.label)?.let { Triple(it, d.score, d.box) } }
    private fun appLevel(f: Frame, sureOnly: Boolean) = appCandidates.getValue(f.file)
        .filter { it.confirmed && (!sureOnly || it.sure) }.mapNotNull { c -> spec.categoryOf(c.label)?.let { Triple(it, c.score, c.box) } }

    fun counts(level: String): Map<String, Counts> {
        val out = spec.scored.associateWith { Counts() }
        for (f in scored) {
            val dets = when (level) { "model" -> modelLevel(f); "app" -> appLevel(f, false); else -> appLevel(f, true) }
            for (c in spec.scored) {
                if (c !in f.labeled) continue // not exhaustively labelled here: neither credit nor blame
                val gts = f.objects.filter { it.category == c }.map { it.box }
                val ps = dets.filter { it.first == c }.sortedByDescending { it.second }.map { it.third }
                val (tp, fp, fn) = match(gts, ps)
                out.getValue(c).apply {
                    this.tp += tp; this.fp += fp; this.fn += fn
                    if (gts.isEmpty()) { negImages++; fpOnNeg += fp }
                }
            }
        }
        return out
    }

    /** AP at IoU 0.5 over all raw detections (score >= 0.10) of each category; NaN without ground truth. */
    fun averagePrecision(): Map<String, Double> = spec.scored.associateWith { c ->
        val hits = ArrayList<Pair<Float, Boolean>>(); var nGt = 0
        for (f in scored) {
            if (c !in f.labeled) continue
            val gts = f.objects.filter { it.category == c }.map { it.box }; nGt += gts.size
            val used = BooleanArray(gts.size)
            p(f).dets.filter { spec.categoryOf(it.label) == c }.sortedByDescending { it.score }.forEach { d ->
                val best = gts.indices.filter { !used[it] }.maxByOrNull { gts[it].iou(d.box) }
                val ok = best != null && gts[best].iou(d.box) >= IOU
                if (ok) used[best!!] = true
                hits += d.score to ok
            }
        }
        ap(hits, nGt)
    }

    /** GT category -> what the model called it (best-overlapping raw detection >= MIN_SCORE, any label) -> count. */
    fun confusion(): Map<String, Map<String, Int>> {
        val out = LinkedHashMap<String, MutableMap<String, Int>>()
        for (f in scored) for (o in f.objects) {
            val best = p(f).dets.filter { it.score >= MIN_SCORE }.maxByOrNull { it.box.iou(o.box) }
            val said = if (best != null && best.box.iou(o.box) >= IOU) best.label else "(missed)"
            out.getOrPut(o.category) { LinkedHashMap() }.merge(said, 1, Int::plus)
        }
        return out
    }

    data class Stability(val track: String, val category: String, val frames: Int, val detectedFrames: Int,
                         val labelFlips: Int, val appIdSwitches: Int)

    /** Per annotated object track: how often it is seen, how often the model's label for it flips, app id switches. */
    fun stability(): List<Stability> = sequences.flatMap { (seqName, seq) ->
        seq.flatMap { f -> f.objects.mapNotNull { it.track?.let { t -> t to it.category } } }.distinct().filter { it.second in spec.scored }.map { (track, cat) ->
            var n = 0; var seen = 0; var flips = 0; var switches = 0; var lastLabel: String? = null; var lastId: Int? = null
            for (f in seq) {
                val gt = f.objects.firstOrNull { it.track == track } ?: continue
                n++
                val raw = p(f).dets.filter { it.score >= MIN_SCORE && it.box.iou(gt.box) >= IOU }.maxByOrNull { it.score }
                if (raw != null) { if (lastLabel != null && raw.label != lastLabel) flips++; lastLabel = raw.label }
                val app = appCandidates.getValue(f.file).filter { it.confirmed && spec.categoryOf(it.label) == cat && it.box.iou(gt.box) >= IOU }
                    .maxByOrNull { it.box.iou(gt.box) }
                if (app != null) { seen++; if (lastId != null && app.id != lastId) switches++; lastId = app.id }
            }
            Stability("$seqName/$track", cat, n, seen, flips, switches)
        }
    }

    data class LockRun(val track: String, val lockedAtMs: Long?, val framesAfterLock: Int, val onTarget: Int,
                       val wrongTarget: Int, val reacquisitions: Int)

    /** Locks each annotated object like a user request would, then counts frames the lock points at anything else. */
    fun targetLocking(): List<LockRun> = sequences.flatMap { (seqName, seq) ->
        seq.flatMap { f -> f.objects.mapNotNull { it.track?.let { t -> t to it.category } } }.distinct().filter { it.second in spec.scored }.map { (track, cat) ->
            val lock = TargetLock(); var lockedAt: Long? = null; var after = 0; var on = 0; var wrong = 0
            for (f in seq) {
                val gt = f.objects.firstOrNull { it.track == track }
                val cands = appCandidates.getValue(f.file)
                if (lockedAt == null) {
                    val c = gt?.let { g -> cands.filter { it.confirmed && it.conflict == null && spec.categoryOf(it.label) == cat }
                        .maxByOrNull { it.box.iou(g.box) }?.takeIf { it.box.iou(g.box) >= IOU } }
                    if (c != null) { lock.lock(c, f.ms); lockedAt = f.ms }
                    continue
                }
                lock.update(cands, f.ms); after++
                val box = lock.boxAt(f.ms) ?: continue
                if (gt == null) continue // object not annotated here (e.g. fully occluded)
                if (box.iou(gt.box) >= 0.3f) on++ else wrong++
            }
            LockRun("$seqName/$track", lockedAt, after, on, wrong, lock.reacquisitions)
        }
    }

    data class HandRun(val sequence: String, val selectedAtMs: Long?, val userFrames: Int, val otherHandFrames: Int,
                       val pausedWithUserVisible: Int)

    /** Sequences with hand boxes: the user makes the gesture; a frame guided by an "other hand" box is an error. */
    fun handIdentity(): List<HandRun> = sequences.filter { (_, seq) -> seq.any { it.hands.isNotEmpty() } }.map { (seqName, seq) ->
        val sel = HandSelector(); sel.beginEnrollment(Side.RIGHT, seq.first().ms) // the sequences record one hand
        var at: Long? = null; var user = 0; var other = 0; var paused = 0
        for (f in seq) {
            sel.update(p(f).hands, f.ms)
            val h = sel.hand(Side.RIGHT)
            val userBox = f.hands.firstOrNull { it.category == "user hand" }
            if (h.present) {
                if (at == null) at = f.ms
                when (f.hands.firstOrNull { it.box.contains(h.palmX, h.palmY) }?.category) {
                    "user hand" -> user++; "other hand" -> other++
                }
            } else if (at != null && userBox != null) paused++
        }
        HandRun(seqName, at, user, other, paused)
    }

    data class ReachRun(val sequence: String, val reached: Boolean, val directionsAfterReached: Int, val directionsSpoken: Int)

    /** Sequences with a target and reach_ms: guide with the app's core, the user confirms at reach_ms. */
    fun reachRuns(): List<ReachRun> = sequences.filter { (_, seq) -> seq.first().target != null && seq.first().reachMs != null }
        .map { (seqName, seq) ->
            val said = ArrayList<String>(); val core = ReachCore({ said += it })
            val target = seq.first().target!!; val reachAt = seq.first().reachMs!!
            val directions = listOf(Guidance.LEFT, Guidance.RIGHT, Guidance.UP, Guidance.DOWN, Guidance.CLOSER).map { it.phrase(target)!! }
            core.beginHandSetup(seq.first().ms, side = Side.RIGHT)
            var reachedAt = -1
            for (f in seq) {
                val cands = appCandidates.getValue(f.file)
                core.onDetections(cands, f.ms, core.session)
                if (core.mode == Mode.IDLE) cands.firstOrNull { it.confirmed && spec.categoryOf(it.label) == target }?.let { c ->
                    core.onSearchResult(core.beginSearch(), c, target, c.score.toDouble(), true, "", f.ms)
                    core.startGuidance(f.ms)
                }
                core.onFrame(p(f).hands, cands, f.ms)
                if (f.ms >= reachAt && core.confirmReached()) reachedAt = said.size
            }
            ReachRun(seqName, core.mode == Mode.REACHED,
                if (reachedAt < 0) 0 else said.drop(reachedAt).count { it in directions }, said.count { it in directions })
        }

    fun latency(): Map<String, Double> {
        fun pct(v: List<Double>, q: Double) = v.sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * q).toInt()] }
        val det = scored.map { p(it).detMs }; val hand = scored.map { p(it).handMs }
        return mapOf("det_p50" to pct(det, 0.5), "det_p90" to pct(det, 0.9), "hand_p50" to pct(hand, 0.5), "hand_p90" to pct(hand, 0.9))
    }

    companion object {
        const val IOU = 0.5f

        /** Greedy matching (predictions in descending score order). Returns TP, FP, FN. */
        fun match(gts: List<Box>, preds: List<Box>): Triple<Int, Int, Int> {
            val used = BooleanArray(gts.size); var tp = 0
            for (pb in preds) {
                val best = gts.indices.filter { !used[it] }.maxByOrNull { gts[it].iou(pb) } ?: continue
                if (gts[best].iou(pb) >= IOU) { used[best] = true; tp++ }
            }
            return Triple(tp, preds.size - tp, gts.size - tp)
        }

        /** All-point interpolated average precision (PASCAL VOC 2010+). */
        fun ap(hits: List<Pair<Float, Boolean>>, nGt: Int): Double {
            if (nGt == 0) return Double.NaN
            val sorted = hits.sortedByDescending { it.first }
            var tp = 0; var fp = 0
            val rp = sorted.map { (_, ok) -> if (ok) tp++ else fp++; tp.toDouble() / nGt to tp.toDouble() / (tp + fp) }
            val interp = DoubleArray(rp.size) // precision made monotone: best precision at this recall or beyond
            for (i in rp.indices.reversed()) interp[i] = maxOf(rp[i].second, if (i + 1 < rp.size) interp[i + 1] else 0.0)
            var ap = 0.0; var prevR = 0.0
            for (i in rp.indices) { ap += (rp[i].first - prevR) * interp[i]; prevR = rp[i].first }
            return ap
        }
    }
}
