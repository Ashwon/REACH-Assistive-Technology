package com.reach.app.grounding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
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
    val id: Int,
    val label: String,        // detector class, e.g. "bottle"
    val beverage: String,     // color-derived guess: coke/pepsi/sprite/monster/unknown
    val box: RectF,           // normalized 0..1
    val score: Float,
    val crop: Bitmap?         // small crop for Claude, null once selection done
)

interface ObjectGrounder {
    /** Returns current candidates in the frame. Must be fast enough for ~2-5 Hz. */
    fun ground(frame: Bitmap, timestampMs: Long): List<Candidate>
    fun close()
}

/**
 * Default grounder: MediaPipe EfficientDet-Lite0 proposes container-like
 * objects; HSV dominant color maps them to the 4 demo beverages.
 * Requires app/src/main/assets/efficientdet_lite0.tflite.
 *
 * This IS the controlled demo mode from the spec — it is a real detector,
 * not a hard-coded fake, but its vocabulary is deliberately curated.
 */
class LocalDetectorGrounder(context: Context) : ObjectGrounder {

    private var lastRawLog = 0L
    private val acceptedClasses = setOf("bottle", "cup", "vase", "wine glass", "can")

    private val detector: ObjectDetector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.VIDEO)
            .setScoreThreshold(0.35f)
            .setMaxResults(8)
            .build()
    )

    override fun ground(frame: Bitmap, timestampMs: Long): List<Candidate> {
        val result = detector.detectForVideo(BitmapImageBuilder(frame).build(), timestampMs)
        if (timestampMs - lastRawLog >= 1000) {
            lastRawLog = timestampMs
            Log.i("REACH", "raw detections: " + result.detections().joinToString {
                it.categories().firstOrNull()?.let { c -> "${c.categoryName()}:${"%.2f".format(c.score())}" } ?: "?"
            })
        }
        val out = ArrayList<Candidate>()
        var id = 0
        for (det in result.detections()) {
            val cat = det.categories().firstOrNull() ?: continue
            if (cat.categoryName().lowercase() !in acceptedClasses) continue
            val b = det.boundingBox() // pixel coords
            val box = RectF(
                (b.left / frame.width).coerceIn(0f, 1f),
                (b.top / frame.height).coerceIn(0f, 1f),
                (b.right / frame.width).coerceIn(0f, 1f),
                (b.bottom / frame.height).coerceIn(0f, 1f)
            )
            val crop = safeCrop(frame, box)
            val beverage = crop?.let { ColorClassifier.classify(it) } ?: "unknown"
            out.add(Candidate(id++, cat.categoryName(), beverage, box, cat.score(), crop))
        }
        return out
    }

    private fun safeCrop(frame: Bitmap, box: RectF): Bitmap? = try {
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
