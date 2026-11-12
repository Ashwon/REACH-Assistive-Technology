package com.reach.app.eval

import com.google.gson.GsonBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The batch evaluation entry point (skipped unless inputs are given):
 *   ./gradlew testDebugUnitTest --tests '*VisionEvalRun*' -PevalDataset=<dataset dir> -PevalPredictions=<a.jsonl[,b.jsonl]>
 * tools/eval_on_device.sh produces the predictions on the phone and runs this. Writes report.md / report.json
 * to app/build/reports/vision-eval/. Several predictions files (one per model) give a side-by-side comparison.
 */
class VisionEvalRunTest {
    @Test fun evaluate() {
        val dataset = System.getProperty("reach.eval.dataset").orEmpty()
        val predictions = System.getProperty("reach.eval.predictions").orEmpty()
        assumeTrue("no -PevalDataset / -PevalPredictions given", dataset.isNotEmpty() && predictions.isNotEmpty())
        val spec = CategorySpec.load(File(System.getProperty("reach.dataset"), "categories.json"))
        val frames = loadDataset(File(dataset), spec)
        val out = File(System.getProperty("reach.eval.out")).apply { mkdirs() }
        val md = StringBuilder(); val json = LinkedHashMap<String, Any?>()
        for (path in predictions.split(',')) {
            val pf = File(path)
            val run = VisionEval(spec, frames, loadPredictions(pf))
            val ids = IdentityEval(frames, loadPredictions(pf)).run()
            val name = pf.nameWithoutExtension.removePrefix("predictions_")
            md.append(report(name, run, loadPredictions(pf).summary?.toString(), frames.size)).append("\n")
                .append(IdentityEval.report(ids)).append("\n")
            json[name] = mapOf("counts" to listOf("model", "app", "sure").associateWith { run.counts(it) },
                "ap50" to run.averagePrecision(), "confusion" to run.confusion(), "stability" to run.stability(),
                "locking" to run.targetLocking(), "hands" to run.handIdentity(), "reach" to run.reachRuns(),
                "latency" to run.latency(), "identity" to ids, "device_summary" to loadPredictions(pf).summary?.toString())
        }
        File(out, "report.md").writeText(md.toString())
        File(out, "report.json").writeText(GsonBuilder().serializeSpecialFloatingPointValues().setPrettyPrinting().create().toJson(json))
        println(md)
        println("report: ${File(out, "report.md").absolutePath}")
    }

    private fun f(x: Double) = if (x.isNaN()) "–" else "%.2f".format(x)

    private fun report(name: String, e: VisionEval, deviceSummary: String?, nFrames: Int) = buildString {
        appendLine("## $name  ($nFrames labelled images)")
        appendLine()
        appendLine("Detection at IoU 0.5. model = raw detections ≥ ${com.reach.app.grounding.MIN_SCORE} (the app before temporal " +
            "confirmation); app = what the app now offers as a target; sure = what it names without hedging. " +
            "FP/neg = false positives per image with none of that category. Categories with no ground truth and no detections are omitted.")
        appendLine()
        appendLine("| category | AP50 | model P / R / FP/neg | app P / R / FP/neg | sure P / R / FP/neg |")
        appendLine("|---|---|---|---|---|")
        val levels = listOf("model", "app", "sure").map { e.counts(it) }
        val ap = e.averagePrecision()
        for (c in levels[0].keys) {
            if (levels.all { it.getValue(c).let { k -> k.tp + k.fp + k.fn == 0 } }) continue
            appendLine("| $c | ${f(ap.getValue(c))} | " + levels.joinToString(" | ") { lv ->
                lv.getValue(c).let { "${f(it.precision)} / ${f(it.recall)} / ${f(it.fpPerNegImage)} (tp ${it.tp} fp ${it.fp} fn ${it.fn})" }
            } + " |")
        }
        appendLine()
        appendLine("**What the model called each annotated object** (best-overlapping detection ≥ ${com.reach.app.grounding.MIN_SCORE}):")
        e.confusion().forEach { (gt, row) -> appendLine("- $gt → " + row.entries.sortedByDescending { it.value }.joinToString { "${it.key} ×${it.value}" }) }
        appendLine()
        val st = e.stability()
        if (st.isNotEmpty()) {
            appendLine("| object track | frames | app detected | model label flips | app id switches |")
            appendLine("|---|---|---|---|---|")
            st.forEach { appendLine("| ${it.track} (${it.category}) | ${it.frames} | ${it.detectedFrames} | ${it.labelFlips} | ${it.appIdSwitches} |") }
            appendLine()
        }
        val locks = e.targetLocking()
        if (locks.isNotEmpty()) {
            appendLine("| target lock | locked at | frames after | on target | wrong target | re-acquisitions |")
            appendLine("|---|---|---|---|---|---|")
            locks.forEach { appendLine("| ${it.track} | ${it.lockedAtMs ?: "never (no confirmed, unambiguous detection)"} | ${it.framesAfterLock} | ${it.onTarget} | ${it.wrongTarget} | ${it.reacquisitions} |") }
            appendLine()
        }
        val hands = e.handIdentity()
        appendLine(if (hands.isEmpty()) "Hand identity: no sequences with hand annotations in this dataset." else
            "Hand identity: " + hands.joinToString { "${it.sequence}: selected at ${it.selectedAtMs}, user-hand frames ${it.userFrames}, " +
                "OTHER-hand frames ${it.otherHandFrames}, paused while user hand visible ${it.pausedWithUserVisible}" })
        val reach = e.reachRuns()
        appendLine(if (reach.isEmpty()) "Reached/speech: no sequences with target + reach_ms in this dataset." else
            "Reached/speech: " + reach.joinToString { "${it.sequence}: reached=${it.reached}, directions after reached ${it.directionsAfterReached}, directions total ${it.directionsSpoken}" })
        appendLine()
        val lat = e.latency()
        appendLine("Latency on device (ms): detector p50 ${f(lat.getValue("det_p50"))} / p90 ${f(lat.getValue("det_p90"))}; " +
            "hand landmarker p50 ${f(lat.getValue("hand_p50"))} / p90 ${f(lat.getValue("hand_p90"))}")
        if (deviceSummary != null) appendLine("Device summary: `$deviceSummary`")
    }
}
