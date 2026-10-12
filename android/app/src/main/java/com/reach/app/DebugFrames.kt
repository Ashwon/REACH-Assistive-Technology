package com.reach.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.reach.app.hand.HandTracker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug builds only: records real analysis frames, and runs a folder of recorded frames through the on-device
 * models in one batch, writing what they output for the offline evaluator (dataset/README.md, tools/).
 *   capture: adb shell am start -f 0x20000000 -n com.reach.app/.MainActivity --ei capture 40
 *   replay:  adb shell am start -f 0x20000000 -n com.reach.app/.MainActivity --es replay eval_images \
 *              [--es model efficientdet_lite2.tflite] [--ei loops 5]
 * The model is an APK asset, or a .tflite pushed into the app's files dir (so candidates need no rebuild).
 * Output: files/eval/predictions_<model>.jsonl, one JSON object per image plus a final {"summary": ...} line
 * with latency percentiles, memory and thermal state; "replay done" is logged under the REACH-REPLAY tag.
 */
class DebugFrames(private val context: Context) {
    private val io = Executors.newSingleThreadExecutor()
    private val captureLeft = AtomicInteger(0)

    fun startCapture(n: Int) { captureLeft.set(n); Log.i(TAG, "capture $n frames") }

    /** Camera thread. The frame bitmap is never recycled, so writing it on another thread is safe. */
    fun onFrame(frame: Bitmap, tsMs: Long) {
        if (captureLeft.get() <= 0 || captureLeft.decrementAndGet() < 0) return
        io.execute {
            val dir = File(context.filesDir, "captures").apply { mkdirs() }
            File(dir, "f_%012d.png".format(tsMs)).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Images sorted by name; names f_<ms>.<ext> carry the real frame time (sequences), others are 100 ms apart. */
    fun replay(dirName: String, model: String = "efficientdet_lite0.tflite", loops: Int = 1) = io.execute {
        val files = File(context.filesDir, dirName).listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            ?.sortedBy { it.name }.orEmpty()
        Log.i(TAG, "replay $dirName: ${files.size} frames, model=$model loops=$loops")
        val out = File(context.filesDir, "eval/predictions_${model.substringBeforeLast('.')}.jsonl").apply { parentFile?.mkdirs() }
        var detector: ObjectDetector? = null; var hands: HandTracker? = null
        try {
            val local = File(context.filesDir, model)
            val base = BaseOptions.builder().apply { if (local.exists()) setModelAssetBuffer(direct(local)) else setModelAssetPath(model) }.build()
            detector = ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base).setRunningMode(RunningMode.IMAGE).setScoreThreshold(0.1f).setMaxResults(25).build())
            hands = HandTracker(context)
            val detMs = ArrayList<Double>(); val handMs = ArrayList<Double>()
            val power = context.getSystemService(PowerManager::class.java)
            fun thermal() = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
            val thermalStart = thermal(); val t0 = System.nanoTime()
            out.bufferedWriter().use { w ->
                var tsBase = 0L
                for (loop in 0 until loops) {
                    var lastTs = tsBase
                    for ((i, f) in files.withIndex()) {
                        val bmp = BitmapFactory.decodeFile(f.path) ?: continue
                        val ts = maxOf(lastTs + 1, tsBase + (f.nameWithoutExtension.removePrefix("f_").toLongOrNull() ?: (i * 100L)))
                        lastTs = ts
                        val a = System.nanoTime()
                        val dets = detector.detect(BitmapImageBuilder(bmp).build()).detections()
                        val b = System.nanoTime()
                        val hs = hands.detectAll(bmp, ts) // VIDEO mode: tracks across a sequence like the live app
                        val c = System.nanoTime()
                        detMs += (b - a) / 1e6; handMs += (c - b) / 1e6
                        if (loop > 0) continue // later loops only measure sustained latency
                        w.write(JSONObject().apply {
                            put("image", f.name); put("w", bmp.width); put("h", bmp.height)
                            put("det_ms", (b - a) / 1e6); put("hand_ms", (c - b) / 1e6); put("luma", meanLuma(bmp))
                            put("dets", JSONArray().apply { dets.forEach { d ->
                                val cat = d.categories()[0]; val r = d.boundingBox()
                                put(JSONObject().put("label", cat.categoryName()).put("score", cat.score().toDouble())
                                    .put("box", JSONArray(listOf(r.left / bmp.width, r.top / bmp.height, r.right / bmp.width, r.bottom / bmp.height).map { it.toDouble() })))
                            } })
                            put("hands", JSONArray().apply { hs.forEach { h ->
                                put(JSONObject().put("score", h.score.toDouble())
                                    .put("lm", JSONArray(h.landmarks.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble())) })))
                            } })
                        }.toString()); w.newLine()
                    }
                    tsBase = lastTs + 10_000
                }
                val pss = Debug.getPss() / 1024.0
                w.write(JSONObject().put("summary", JSONObject().apply {
                    put("model", model); put("frames", files.size); put("loops", loops); put("device", Build.MODEL)
                    put("det_ms", pct(detMs)); put("hand_ms", pct(handMs)); put("pss_mb", pss)
                    put("thermal_start", thermalStart); put("thermal_end", thermal()); put("wall_s", (System.nanoTime() - t0) / 1e9)
                }).toString()); w.newLine()
            }
            Log.i(TAG, "replay done -> ${out.path} det_ms=${pct(detMs)} hand_ms=${pct(handMs)}")
        } catch (e: Exception) { Log.e(TAG, "replay failed", e) }
        finally { detector?.close(); hands?.close() }
    }

    fun close() { io.shutdown() }

    private companion object {
        const val TAG = "REACH-REPLAY"

        fun direct(f: File): ByteBuffer {
            val bytes = f.readBytes()
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        }

        fun pct(v: List<Double>): JSONObject {
            val s = v.sorted()
            fun p(q: Double) = if (s.isEmpty()) 0.0 else s[((s.size - 1) * q).toInt()]
            return JSONObject().put("p50", p(0.5)).put("p90", p(0.9)).put("max", p(1.0)).put("n", s.size)
        }

        /** Mean luma (0..255), sampled every 8th pixel, as LocalDetectorGrounder computes it. */
        fun meanLuma(bmp: Bitmap): Double {
            var n = 0; var sum = 0.0
            for (y in 0 until bmp.height step 8) for (x in 0 until bmp.width step 8) {
                val p = bmp.getPixel(x, y)
                sum += 0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255); n++
            }
            return if (n == 0) 0.0 else sum / n
        }
    }
}
