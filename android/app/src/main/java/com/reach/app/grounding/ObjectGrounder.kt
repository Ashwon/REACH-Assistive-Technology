package com.reach.app.grounding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * V2 — Object grounding abstraction. The rest of the app only talks to
 * ObjectGrounder, so the backend (local detector today, remote
 * open-vocab model tomorrow) can be swapped without touching anything else.
 */
data class Candidate(
    val id: Int,              // stable across frames (see CandidateTracker)
    val label: String,        // COCO class from the model's labels.txt, e.g. "bottle"
    val beverage: String,     // color-derived guess for drink containers: coke/pepsi/sprite/monster/unknown
    val box: Box,             // normalized 0..1
    val score: Float,         // detector score, smoothed over this object's sightings; not a calibrated probability
    val crop: Bitmap?,        // small crop for Claude's thumbnail
    val confirmed: Boolean = true,  // seen in enough detector runs to be offered at all (CandidateTracker)
    val conflict: String? = null,   // another class the model reported for the same spot, e.g. "handbag" for a bottle
    val lowLight: Boolean = false,  // the frame (or this dark, featureless box) was too dark for the detector to be trusted
    val look: Appearance? = null,   // colours of the box's centre, from [crop]; null when there are no pixels
    val frameMs: Long = -1L         // camera time of the frame this box was measured in; -1 = unknown
) {
    /** Enough evidence to name it without hedging. Even then it is a detector's guess, never "verified". */
    val sure get() = confirmed && conflict == null && !lowLight && score >= STRONG_SCORE

    companion object {
        const val STRONG_SCORE = 0.6f
        /** Mean frame luma (0..255). Replayed underexposed A15 frames (mean 9..42) produced only junk boxes. */
        const val LOW_LIGHT_LUMA = 45f
    }
}

interface ObjectGrounder {
    /** Returns current candidates in the frame. Must be fast enough for ~2-5 Hz. */
    fun ground(frame: Bitmap, timestampMs: Long): List<Candidate>
    fun close()
}

/**
 * Default grounder: MediaPipe EfficientDet-Lite0 (COCO, 320x320; MediaPipe applies the model's
 * own resize/normalization). Detections are kept only for [allowed] classes above [minScore]
 * with a valid box, and get stable ids from a tracker. HSV color maps drink containers to the
 * demo beverages. It cannot recognize anything outside the COCO label set.
 * Requires app/src/main/assets/efficientdet_lite0.tflite.
 */
class LocalDetectorGrounder(
    context: Context,
    private val allowed: Set<String> = SupportedClasses.DETECTED,
    private val minScore: Float = MIN_SCORE
) : ObjectGrounder {

    private val tracker = CandidateTracker()
    private var lastRawLog = 0L

    private val detector: ObjectDetector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.VIDEO)
            // Ask for more than we keep, so supported objects scoring just under minScore show up in the log.
            .setScoreThreshold(minOf(minScore, LOG_FLOOR))
            // Unsupported classes (tv, dining table, ...) still occupy result slots, so leave headroom.
            .setMaxResults(20)
            .build()
    )

    override fun ground(frame: Bitmap, timestampMs: Long): List<Candidate> {
        val result = detector.detectForVideo(BitmapImageBuilder(frame).build(), timestampMs)
        val dets = ArrayList<Det>()
        val rejected = ArrayList<String>()
        val near = ArrayList<String>()
        val crops = HashMap<Det, Pair<Bitmap?, Appearance?>>()
        for (det in result.detections()) {
            val cat = det.categories().firstOrNull() ?: continue
            val name = cat.categoryName().lowercase()
            val b = det.boundingBox() // pixel coords in the frame we passed
            val box = Box.clamped(
                b.left / frame.width, b.top / frame.height, b.right / frame.width, b.bottom / frame.height
            )
            val tag = "$name:${"%.2f".format(cat.score())}"
            // Pixels only for classes we could surface, and only past the cheap checks.
            val pix = if (name in allowed && rejectReason(cat.score(), box, minScore) == null)
                safeCrop(frame, box).let { it to it?.let(::lookOf) } else null
            when (val why = rejectReason(cat.score(), box, minScore, pix?.second)) {
                "low" -> (if (name in allowed) near else rejected).add(tag)
                // Unsupported classes are tracked too, only so a spot that is "bed" one frame and "person" the next shows as a conflict.
                null -> Det(name, cat.score(), box).also { dets.add(it); if (pix != null) crops[it] = pix }
                else -> rejected.add("$tag:$why")
            }
        }
        val ids = tracker.assign(dets, timestampMs)
        val luma = meanLuma(frame)
        val cands = dets.indices.filter { dets[it].label in allowed }.map { i ->
            val d = dets[i]; val ev = tracker.evidence(ids[i])!!
            val (crop, look) = crops[d] ?: (null to null)
            val beverage = if (d.label in SupportedClasses.DRINK_CONTAINERS)
                crop?.let { ColorClassifier.classify(it) } ?: "unknown" else "unknown"
            // A strong detection on a dark, featureless box is kept but never "sure".
            val dark = luma < Candidate.LOW_LIGHT_LUMA || look?.darkFlat == true
            Candidate(ids[i], d.label, beverage, d.box, ev.score, crop, ev.confirmed, ev.conflict, dark, look, timestampMs)
        }
        if (timestampMs - lastRawLog >= 1000) {
            lastRawLog = timestampMs
            Log.i("REACH", "kept=" + cands.map { c ->
                "#${c.id} ${c.label}:${"%.2f".format(c.score)}${if (c.confirmed) "" else "(new)"}${c.conflict?.let { "(or $it)" } ?: ""}"
            } + " other=" + dets.filter { it.label !in allowed }.map { "${it.label}:${"%.2f".format(it.score)}" } +
                " near=$near rejected=$rejected luma=${luma.toInt()}")
        }
        return cands
    }

    /** Mean luma (0..255) of the frame, sampled every 8th pixel. */
    private fun meanLuma(frame: Bitmap): Float {
        var sum = 0f; var n = 0
        for (y in 0 until frame.height step 8) for (x in 0 until frame.width step 8) {
            val p = frame.getPixel(x, y)
            sum += 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p); n++
        }
        return if (n == 0) 0f else sum / n
    }

    override fun close() = detector.close()

    private companion object { const val LOG_FLOOR = 0.2f }
}

/** Colours of the crop's central 60% (the edges are mostly background). Shared with the replay (DebugFrames). */
fun lookOf(crop: Bitmap): Appearance? {
    val x0 = crop.width / 5; val y0 = crop.height / 5; val w = crop.width - 2 * x0; val h = crop.height - 2 * y0
    val px = IntArray(w * h)
    crop.getPixels(px, 0, w, x0, y0, w, h)
    return Appearance.of(px)
}

fun safeCrop(frame: Bitmap, box: Box): Bitmap? = try {
    val l = (box.left * frame.width).toInt().coerceIn(0, frame.width - 2)
    val t = (box.top * frame.height).toInt().coerceIn(0, frame.height - 2)
    val w = ((box.right - box.left) * frame.width).toInt().coerceIn(2, frame.width - l)
    val h = ((box.bottom - box.top) * frame.height).toInt().coerceIn(2, frame.height - t)
    Bitmap.createScaledBitmap(Bitmap.createBitmap(frame, l, t, w, h), 96, 128, true)
} catch (e: Exception) { null }

/**
 * HSV dominant-color heuristic for the curated demo set.
 * Coke = red, Pepsi = blue, Sprite = green(light), Monster = black/dark.
 * Deliberately simple and deterministic — tune thresholds with your actual cans.
 */
object ColorClassifier {
    fun classify(crop: Bitmap): String {
        var red = 0; var blue = 0; var green = 0; var dark = 0; var total = 0
        val hsv = FloatArray(3)
        val step = 4
        // sample the middle band of the crop (label area), skip edges
        for (y in crop.height / 4 until 3 * crop.height / 4 step step) {
            for (x in crop.width / 4 until 3 * crop.width / 4 step step) {
                val c = crop.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
                total++
                when {
                    v < 0.22f -> dark++
                    s < 0.25f -> { /* gray/white, ignore */ }
                    h < 20f || h > 340f -> red++
                    h in 90f..165f -> green++
                    h in 200f..260f -> blue++
                }
            }
        }
        if (total == 0) return "unknown"
        val scores = mapOf(
            "coke" to red, "pepsi" to blue, "sprite" to green, "monster" to dark
        )
        val best = scores.maxByOrNull { it.value } ?: return "unknown"
        return if (best.value.toFloat() / total > 0.18f) best.key else "unknown"
    }
}
