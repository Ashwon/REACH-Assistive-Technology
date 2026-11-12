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
    val label: String,        // COCO class from the model's labels.txt, e.g. "bottle" or "laptop"
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
 * High-speed, high-recall grounder for REACH's 2 core targets: "bottle" and "laptop".
 * - Uses MediaPipe's native C++ category allowlist so background furniture/people never consume NMS slots.
 * - Fuses "laptop", "keyboard" (laptop deck), and desk-sized "tv" (laptop display) into a unified "laptop" detection
 *   so laptops are detected reliably from any angle or distance, including dark/black laptops.
 * - Maps tall cylindrical "vase"/"cup" flasks alongside native "bottle" so insulated water bottles are never missed.
 */
class LocalDetectorGrounder(
    context: Context,
    private val allowed: Set<String> = SupportedClasses.DETECTED,
    private val minScore: Float = LIVE_MIN_SCORE
) : ObjectGrounder {

    private val tracker = CandidateTracker(ttlMs = 4500L, maxJump = 0.38f, confirmHits = 2)
    private var lastRawLog = 0L

    private val detector: ObjectDetector = run {
        val asset = listOf("efficientdet_lite2_int8.tflite", "efficientdet_lite0_int8.tflite", "efficientdet_lite0.tflite")
            .firstOrNull { name ->
                try {
                    context.assets.open(name).close()
                    true
                } catch (_: Exception) {
                    false
                }
            } ?: "efficientdet_lite0.tflite"
        Log.i("REACH", "LocalDetectorGrounder loading model=$asset minScore=$minScore allowed=$allowed")
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(asset).build())
                .setRunningMode(RunningMode.VIDEO)
                .setCategoryAllowlist(NATIVE_ALLOWLIST)
                .setScoreThreshold(KEEP_SCORE_FLOOR)
                .setMaxResults(20)
                .build()
        )
    }

    override fun ground(frame: Bitmap, timestampMs: Long): List<Candidate> {
        val result = detector.detectForVideo(BitmapImageBuilder(frame).build(), timestampMs)
        val luma = meanLuma(frame)
        val frameDark = luma < Candidate.LOW_LIGHT_LUMA
        val rawTargets = ArrayList<RawDetection>()
        val rawRivals = ArrayList<RawDetection>()

        for (det in result.detections()) {
            val cat = det.categories().firstOrNull() ?: continue
            val rawName = cat.categoryName().lowercase()
            val box = det.boundingBox().let { b ->
                Box.clamped(b.left / frame.width, b.top / frame.height, b.right / frame.width, b.bottom / frame.height)
            }
            if (!box.isValid()) continue
            val raw = RawDetection(rawName, box, cat.score())
            if (rawName == "bottle" || rawName == "cup" || rawName == "laptop") {
                rawTargets.add(raw)
            } else {
                rawRivals.add(raw)
            }
        }

        val (dets, rejectedAll) = GrounderFilters.filterAndMap(
            rawTargets = rawTargets,
            rawRivals = rawRivals,
            allowed = allowed,
            frameWidth = frame.width,
            frameHeight = frame.height,
            minScoreOverride = minScore.takeIf { it != LIVE_MIN_SCORE },
            hasActiveTrack = { label, box -> !frameDark && tracker.hasActiveTrackNear(label, box, timestampMs) }
        )
        val rejected = ArrayList(rejectedAll)
        val near = ArrayList<String>()
        val crops = HashMap<Det, Pair<Bitmap?, Appearance?>>()
        val filteredDets = ArrayList<Det>()
        for (d in dets) {
            val pix = safeCrop(frame, d.box).let { it to it?.let(::lookOf) }
            val look = pix.second
            val flatBrightSurface = look != null && look.luma > 235f && look.contrast < 8f && d.score < 0.65f
            if (flatBrightSurface) {
                rejected.add("${d.label}:${"%.2f".format(d.score)}:flatbright")
                continue
            }
            val why = if (frameDark) rejectReason(d.score, d.box, KEEP_SCORE_FLOOR, look) else null
            if (why == null) {
                filteredDets.add(d)
                crops[d] = pix
            } else {
                rejected.add("${d.label}:${"%.2f".format(d.score)}:$why")
            }
        }

        val ids = tracker.assign(filteredDets, timestampMs)
        val cands = filteredDets.indices.map { i ->
            val d = filteredDets[i]; val ev = tracker.evidence(ids[i])!!
            val (crop, look) = crops[d] ?: (null to null)
            val beverage = if (d.label in SupportedClasses.DRINK_CONTAINERS)
                crop?.let { ColorClassifier.classify(it) } ?: "unknown" else "unknown"
            val dark = frameDark || (luma < 55f && look?.darkFlat == true)
            val isConfirmed = ev.confirmed || (!dark && d.score >= 0.72f)
            val calibratedScore = if (!dark && ev.confirmed && ev.score >= 0.48f && ev.conflict == null) {
                maxOf(ev.score, Candidate.STRONG_SCORE)
            } else ev.score
            Candidate(ids[i], d.label, beverage, d.box, calibratedScore, crop, isConfirmed, ev.conflict, dark, look, timestampMs)
        }
        if (timestampMs - lastRawLog >= 1000) {
            lastRawLog = timestampMs
            Log.i("REACH", "kept=" + cands.map { c ->
                "#${c.id} ${c.label}:${"%.2f".format(c.score)}${if (c.confirmed) "" else "(new)"}"
            } + " near=$near rejected=$rejected luma=${luma.toInt()}")
        }
        return cands
    }

    /** Merges overlapping or nested boxes of the same target class (such as a laptop's screen + keyboard + full box). */
    private fun mergeSameClassDetections(raw: List<Det>): List<Det> {
        if (raw.size <= 1) return raw
        val sorted = raw.sortedByDescending { it.score }.toMutableList()
        val out = ArrayList<Det>()
        while (sorted.isNotEmpty()) {
            var cur = sorted.removeAt(0)
            val it = sorted.iterator()
            while (it.hasNext()) {
                val other = it.next()
                if (other.label != cur.label) continue
                val overlapX = minOf(cur.box.right, other.box.right) - maxOf(cur.box.left, other.box.left)
                val minW = minOf(cur.box.right - cur.box.left, other.box.right - other.box.left).coerceAtLeast(1e-4f)
                val sameObject = cur.box.iou(other.box) > 0.18f ||
                    cur.box.contains(other.box.centerX(), other.box.centerY()) ||
                    other.box.contains(cur.box.centerX(), cur.box.centerY()) ||
                    (cur.label == "laptop" && overlapX / minW > 0.55f && kotlin.math.abs(cur.box.centerY() - other.box.centerY()) < 0.28f)
                if (sameObject) {
                    val mergedBox = Box.clamped(
                        minOf(cur.box.left, other.box.left),
                        minOf(cur.box.top, other.box.top),
                        maxOf(cur.box.right, other.box.right),
                        maxOf(cur.box.bottom, other.box.bottom)
                    )
                    // Boost score slightly when multiple parts (e.g. laptop + keyboard) agree on the same object
                    val boostedScore = (maxOf(cur.score, other.score) + 0.06f).coerceAtMost(0.95f)
                    cur = Det(cur.label, boostedScore, mergedBox)
                    it.remove()
                }
            }
            out.add(cur)
        }
        return out
    }

    /** Mean luma (0..255) of the frame, sampled every 8th pixel using bulk row reads instead of per-pixel JNI. */
    private fun meanLuma(frame: Bitmap): Float {
        val w = frame.width; val h = frame.height
        if (w <= 0 || h <= 0) return 0f
        val row = IntArray(w)
        var sum = 0L; var n = 0
        for (y in 0 until h step 8) {
            frame.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step 8) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += (77 * r + 150 * g + 29 * b) shr 8
                n++
            }
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    override fun close() = detector.close()

    private companion object {
        const val LIVE_MIN_SCORE = 0.28f
        const val KEEP_SCORE_FLOOR = 0.22f
        val NATIVE_ALLOWLIST = listOf(
            "bottle", "cup", "laptop",
            "tv", "book", "cell phone", "dining table", "keyboard", "suitcase",
            "handbag", "backpack", "microwave", "oven", "toaster", "refrigerator",
            "remote", "mouse", "clock", "vase", "wine glass", "bowl", "chair", "couch", "bed", "bench"
        )
    }
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
 */
object ColorClassifier {
    fun classify(crop: Bitmap): String {
        val x0 = crop.width / 4; val y0 = crop.height / 4
        val w = crop.width / 2; val h = crop.height / 2
        if (w <= 0 || h <= 0) return "unknown"
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, x0, y0, w, h)
        var red = 0; var blue = 0; var green = 0; var dark = 0; var total = 0
        val hsv = FloatArray(3)
        val step = 4
        for (y in 0 until h step step) {
            val rowOff = y * w
            for (x in 0 until w step step) {
                val c = px[rowOff + x]
                Color.colorToHSV(c, hsv)
                val hue = hsv[0]; val s = hsv[1]; val v = hsv[2]
                total++
                when {
                    v < 0.22f -> dark++
                    s < 0.25f -> { /* gray/white, ignore */ }
                    hue < 20f || hue > 340f -> red++
                    hue in 90f..165f -> green++
                    hue in 200f..260f -> blue++
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
