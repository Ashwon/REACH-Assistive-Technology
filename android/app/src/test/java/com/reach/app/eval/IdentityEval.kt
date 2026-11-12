package com.reach.app.eval

import com.reach.app.grounding.Candidate
import com.reach.app.grounding.IdMatch
import com.reach.app.grounding.MIN_SCORE
import com.reach.app.grounding.ObjectMemory
import com.reach.app.grounding.SupportedClasses

/**
 * Registered-object matching ("find my work laptop") on a labelled set, with the app's own rule ([ObjectMemory.match]).
 * Labels: objects annotated with an "instance" id (the same physical object everywhere it appears), and images.csv
 * "reference" naming the instances registered from that image, as "remember this laptop as ..." would. The embeddings
 * are the phone's own, written by the replay for each supported detection.
 *
 * Each instance is registered from its reference images' detections. Every image from a DIFFERENT session (never a
 * near-duplicate of a reference) with at least one detection of its class is a query:
 *  - instance detected: correct / wrong object (a different object claimed: the critical error) / asked the user;
 *  - instance not detected, or absent from an image labelled exhaustively for its class: any match is a false claim.
 * The similarity ranges (this instance vs. other objects of the class) are what the thresholds must separate.
 */
class IdentityEval(private val frames: List<Frame>, private val preds: Predictions,
                   private val minSimilarity: Float = ObjectMemory.MIN_SIMILARITY, private val minMargin: Float = ObjectMemory.MIN_MARGIN) {

    data class Result(val instance: String, val label: String?, val refs: Int, val present: Int, val correct: Int,
                      val wrongObject: Int, val asked: Int, val notDetected: Int, val absent: Int, val falseClaims: Int,
                      val sameSims: List<Float>, val otherSims: List<Float>)

    private fun dets(f: Frame) = preds.frames[f.file]?.dets.orEmpty().filter {
        it.score >= MIN_SCORE && it.label in SupportedClasses.DETECTED && it.emb != null && it.box.isValid() && it.box.isPlausible()
    }

    fun run(): List<Result> = frames.flatMap { f -> f.objects.mapNotNull { it.instance } }.distinct().sorted().map { inst ->
        val memory = ObjectMemory(minSimilarity, minMargin)
        val refFrames = frames.filter { inst in it.references }
        var label: String? = null
        for (f in refFrames) {
            val gt = f.objects.firstOrNull { it.instance == inst } ?: continue
            val d = dets(f).filter { it.box.iou(gt.box) >= VisionEval.IOU }.maxByOrNull { it.box.iou(gt.box) } ?: continue
            if (label == null) label = d.label
            if (d.label == label) memory.add(inst, d.label, d.emb!!)
        }
        val obj = memory.get(inst)
        val category = frames.firstNotNullOfOrNull { f -> f.objects.firstOrNull { it.instance == inst }?.category }
        var present = 0; var correct = 0; var wrong = 0; var asked = 0; var missed = 0; var absent = 0; var claims = 0
        val same = ArrayList<Float>(); val other = ArrayList<Float>()
        val refSessions = refFrames.map { it.session }.toSet()
        if (obj != null) for (f in frames) {
            if (f.session in refSessions) continue
            val ds = dets(f).filter { it.label == obj.label }
            if (ds.isEmpty()) continue
            val gt = f.objects.firstOrNull { it.instance == inst }
            if (gt == null && category !in f.labeled) continue // not exhaustively labelled: unknown whether it is there
            val cands = ds.mapIndexed { i, d -> Candidate(i, d.label, "unknown", d.box, d.score, null) }
            val truth = gt?.let { g -> cands.filter { it.box.iou(g.box) >= VisionEval.IOU }.maxByOrNull { it.box.iou(g.box) }?.id }
            cands.forEach { c -> val s = obj.refs.maxOf { ObjectMemory.cosine(it, ds[c.id].emb!!) }; if (c.id == truth) same += s else other += s }
            val m = memory.match(obj, cands) { ds[it.id].emb }
            when {
                truth != null -> { present++; when { m is IdMatch.Match && m.c.id == truth -> correct++; m is IdMatch.Match -> wrong++; else -> asked++ } }
                else -> { if (gt != null) missed++ else absent++; if (m is IdMatch.Match) claims++ }
            }
        }
        Result(inst, obj?.label ?: label, obj?.refs?.size ?: 0, present, correct, wrong, asked, missed, absent, claims, same.sorted(), other.sorted())
    }

    companion object {
        fun report(results: List<Result>): String = buildString {
            if (results.isEmpty()) { appendLine("Registered-object matching: no instance-labelled objects in this dataset."); return@buildString }
            appendLine("**Registered-object matching** (similarity ≥ ${ObjectMemory.MIN_SIMILARITY}, margin ≥ ${ObjectMemory.MIN_MARGIN}; " +
                "queries only from sessions other than the reference photos'; wrong object and false claims must be 0):")
            appendLine()
            appendLine("| instance | class | ref photos | present: correct / wrong object / asked | not detected | absent | false claims | sim same (min–med–max) | sim others (min–med–max) |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            fun r(v: List<Float>) = if (v.isEmpty()) "–" else "%.2f–%.2f–%.2f".format(v.first(), v[v.size / 2], v.last())
            results.forEach { x ->
                appendLine("| ${x.instance} | ${x.label ?: "?"} | ${x.refs} | ${x.present}: ${x.correct} / ${x.wrongObject} / ${x.asked} | " +
                    "${x.notDetected} | ${x.absent} | ${x.falseClaims} | ${r(x.sameSims)} | ${r(x.otherSims)} |")
            }
            appendLine()
        }
    }
}
