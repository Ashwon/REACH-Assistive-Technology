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
    val score: Float,
    val crop: Bitmap?         // small crop for Claude's thumbnail
)

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
    private val minScore: Float = 0.35f
) : ObjectGrounder {

    private val tracker = CandidateTracker()
    private var lastRawLog = 0L

    private val detector: ObjectDetector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.VIDEO)
            .setScoreThreshold(minScore)
            // Unsupported classes (tv, dining table, ...) still occupy result slots, so leave headroom.
            .setMaxResults(20)
            .build()
    )

    override fun ground(frame: Bitmap, timestampMs: Long): List<Candidate> {
        val result = detector.detectForVideo(BitmapImageBuilder(frame).build(), timestampMs)
        val dets = ArrayList<Det>()
        val rejected = ArrayList<String>()
        for (det in result.detections()) {
            val cat = det.categories().firstOrNull() ?: continue
            val name = cat.categoryName().lowercase()
            val b = det.boundingBox() // pixel coords in the frame we passed
            val box = Box.clamped(
                b.left / frame.width, b.top / frame.height, b.right / frame.width, b.bottom / frame.height
            )
            when {
                name !in allowed -> rejected.add("$name:${"%.2f".format(cat.score())}")
                cat.score() < minScore || !box.isValid() -> rejected.add("$name:invalid")
                else -> dets.add(Det(name, cat.score(), box))
            }
        }
        val ids = tracker.assign(dets, timestampMs)
        if (timestampMs - lastRawLog >= 1000) {
            lastRawLog = timestampMs
            Log.i("REACH", "kept=" + dets.mapIndexed { i, d ->
                "#${ids[i]} ${d.label}:${"%.2f".format(d.score)}"
            } + " rejected=$rejected")
        }
        return dets.mapIndexed { i, d ->
            val crop = safeCrop(frame, d.box)
            val beverage = if (d.label in SupportedClasses.DRINK_CONTAINERS)
                crop?.let { ColorClassifier.classify(it) } ?: "unknown" else "unknown"
            Candidate(ids[i], d.label, beverage, d.box, d.score, crop)
        }
    }

    private fun safeCrop(frame: Bitmap, box: Box): Bitmap? = try {
        val l = (box.left * frame.width).toInt().coerceIn(0, frame.width - 2)
        val t = (box.top * frame.height).toInt().coerceIn(0, frame.height - 2)
        val w = ((box.right - box.left) * frame.width).toInt().coerceIn(2, frame.width - l)
        val h = ((box.bottom - box.top) * frame.height).toInt().coerceIn(2, frame.height - t)
        Bitmap.createScaledBitmap(Bitmap.createBitmap(frame, l, t, w, h), 96, 128, true)
    } catch (e: Exception) { null }

    override fun close() = detector.close()
}

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
