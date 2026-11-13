package com.reach.app.grounding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.reach.app.grounding.GrounderFilters.CLASS_BOTTLE
import com.reach.app.grounding.GrounderFilters.CLASS_CUP
import com.reach.app.grounding.GrounderFilters.CLASS_LAPTOP
import com.reach.app.grounding.GrounderFilters.YOLO_COCO_LABELS
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections

/**
 * Picks the highest-accuracy on-device detector available in assets:
 * 1. YOLO11n ONNX ([Yolo11Grounder]) with anchor + post-NMS rectangular rival suppression.
 * 2. MediaPipe EfficientDet fallback ([LocalDetectorGrounder]) if ONNX initialization fails.
 */
fun createObjectGrounder(context: Context): ObjectGrounder {
    val hasYolo = try {
        context.assets.list("")?.contains(Yolo11Grounder.MODEL_ASSET) == true
    } catch (_: Exception) {
        false
    }
    if (hasYolo) {
        try {
            return Yolo11Grounder(context)
        } catch (e: Throwable) {
            Log.e("REACH", "Yolo11Grounder init failed, falling back to LocalDetectorGrounder", e)
        }
    }
    return LocalDetectorGrounder(context)
}

/**
 * High-accuracy object grounder backed by Ultralytics `yolo11n.onnx` via ONNX Runtime.
 *
 * Uses letterboxed 640x640 NCHW float32 input with zero per-frame large allocations,
 * inspects all 80 COCO classes per candidate anchor plus post-NMS rival boxes so
 * rectangular objects (books, phones, TVs/monitors, keyboards, desks) are not mislabeled
 * as "laptop", and tracks detections across frames with [CandidateTracker].
 */
class Yolo11Grounder(
    context: Context,
    private val allowed: Set<String> = SupportedClasses.DETECTED,
    private val minScoreOverride: Float? = null
) : ObjectGrounder {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String
    private val numClasses = YOLO_COCO_LABELS.size

    private val tracker = CandidateTracker(ttlMs = 4500L, maxJump = 0.35f, confirmHits = 2)
    private val frameDarkHoldMs = 1500L
    private var darkUntilMs = -1L

    // Reused per-frame buffers (ground() runs sequentially on Controller's worker executor).
    private val letterboxBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxBitmap)
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputFloats = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
    private val inputBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 3 * INPUT_SIZE * INPUT_SIZE)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val inputShape = longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

    private var outputFloats = FloatArray(84 * 8400)
    private val anchorScores = FloatArray(numClasses)
    private var lastNearLogMs = 0L

    init {
        val modelFile = ensureModelFile(context, MODEL_ASSET)
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Big.LITTLE mobile SoCs usually have 2 fast prime/big cores; 2 threads avoid slow LITTLE-core barrier stalls.
            val threads = 2
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            runCatching { addConfigEntry("session.intra_op.allow_spinning", "0") }
            try {
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                Log.i("REACH", "Yolo11Grounder XNNPACK enabled threads=$threads")
            } catch (e: Throwable) {
                Log.w("REACH", "Yolo11Grounder XNNPACK unavailable (${e.message}), using CPU EP")
            }
        }
        session = opts.use { env.createSession(modelFile.absolutePath, it) }
        inputName = session.inputNames.first()
        outputName = session.outputNames.first()
        val outShape = (session.outputInfo[outputName]?.info as? TensorInfo)?.shape?.contentToString() ?: "?"
        Log.i("REACH", "Yolo11Grounder ready model=$MODEL_ASSET in=$inputName out=$outputName$outShape allowed=$allowed")
    }

    override fun ground(frame: Bitmap, timestampMs: Long): List<Candidate> {
        val fw = frame.width.coerceAtLeast(1)
        val fh = frame.height.coerceAtLeast(1)

        val luma = meanLuma(frame)
        val frameDark = if (luma < Candidate.LOW_LIGHT_LUMA) {
            darkUntilMs = timestampMs + frameDarkHoldMs
            true
        } else {
            timestampMs <= darkUntilMs && luma < Candidate.LOW_LIGHT_LUMA + 8f
        }

        // Letterbox into reusable 640x640 RGB NCHW [0..1] buffer.
        val scale = minOf(INPUT_SIZE / fw.toFloat(), INPUT_SIZE / fh.toFloat())
        val scaledW = fw * scale
        val scaledH = fh * scale
        val padX = (INPUT_SIZE - scaledW) * 0.5f
        val padY = (INPUT_SIZE - scaledH) * 0.5f

        letterboxCanvas.drawColor(LETTERBOX_BG)
        dstRect.set(padX, padY, padX + scaledW, padY + scaledH)
        letterboxCanvas.drawBitmap(frame, null, dstRect, scalePaint)
        letterboxBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val plane = INPUT_SIZE * INPUT_SIZE
        val gOffset = plane
        val bOffset = plane * 2
        val inv255 = 1f / 255f
        for (i in 0 until plane) {
            val p = pixels[i]
            inputFloats[i] = ((p shr 16) and 0xFF) * inv255
            inputFloats[gOffset + i] = ((p shr 8) and 0xFF) * inv255
            inputFloats[bOffset + i] = (p and 0xFF) * inv255
        }
        inputBuffer.clear()
        inputBuffer.put(inputFloats)
        inputBuffer.rewind()

        val rawTargets = ArrayList<RawDetection>()
        val rawRivals = ArrayList<RawDetection>()
        val near = ArrayList<String>()

        OnnxTensor.createTensor(env, inputBuffer, inputShape).use { tensor ->
            session.run(Collections.singletonMap(inputName, tensor)).use { result ->
                val outTensor = result[0] as OnnxTensor
                val shape = (outTensor.info as TensorInfo).shape
                val fb = outTensor.floatBuffer
                val total = fb.remaining()
                if (outputFloats.size < total) outputFloats = FloatArray(total)
                fb.get(outputFloats, 0, total)

                val channelsFirst = shape.size == 3 && shape[1] <= 128L && shape[2] > 128L
                val numAttrs = (if (channelsFirst) shape[1] else shape[2]).toInt()
                val numAnchors = (if (channelsFirst) shape[2] else shape[1]).toInt()
                val clsCount = (numAttrs - 4).coerceAtMost(numClasses)

                fun attr(attrIdx: Int, anchorIdx: Int): Float =
                    if (channelsFirst) outputFloats[attrIdx * numAnchors + anchorIdx]
                    else outputFloats[anchorIdx * numAttrs + attrIdx]

                fun decodeBox(a: Int): Box? {
                    val bwPx = attr(2, a)
                    val bhPx = attr(3, a)
                    if (bwPx <= 1f || bhPx <= 1f) return null
                    val cxPx = attr(0, a)
                    val cyPx = attr(1, a)
                    val box = Box.clamped(
                        (cxPx - bwPx * 0.5f - padX) / scaledW,
                        (cyPx - bhPx * 0.5f - padY) / scaledH,
                        (cxPx + bwPx * 0.5f - padX) / scaledW,
                        (cyPx + bhPx * 0.5f - padY) / scaledH
                    )
                    return if (box.isValid()) box else null
                }

                // Pass 1: fast scan of only the 3 target rows (bottle, cup, laptop) across 8400 anchors.
                for (a in 0 until numAnchors) {
                    val sBottle = if (CLASS_BOTTLE < clsCount) attr(4 + CLASS_BOTTLE, a) else 0f
                    val sCup = if (CLASS_CUP < clsCount) attr(4 + CLASS_CUP, a) else 0f
                    val sLaptop = if (CLASS_LAPTOP < clsCount) attr(4 + CLASS_LAPTOP, a) else 0f

                    if (sBottle < 0.14f && sCup < GrounderFilters.CUP_AS_BOTTLE_MIN_SCORE && sLaptop < 0.18f) continue

                    val box = decodeBox(a) ?: continue
                    if (sLaptop >= 0.16f) near.add("laptop:${"%.2f".format(sLaptop)}")
                    if (sBottle >= 0.12f || sCup >= 0.25f) near.add("bottle:${"%.2f".format(maxOf(sBottle, sCup))}")

                    // Read full 80-class vector ONLY for active target anchors.
                    for (c in 0 until clsCount) {
                        anchorScores[c] = attr(4 + c, a)
                    }

                    val tallPx = (box.bottom - box.top) * fh >= (box.right - box.left) * fw * 1.20f
                    val bottleScore = if (tallPx && sBottle >= 0.14f && sCup >= 0.16f) {
                        (maxOf(sBottle, sCup) + 0.35f * minOf(sBottle, sCup)).coerceAtMost(0.88f)
                    } else {
                        sBottle
                    }

                    val bottleKept = bottleScore >= 0.15f &&
                        !GrounderFilters.isAnchorSuppressed(CLASS_BOTTLE, bottleScore, anchorScores)
                    if (bottleKept) {
                        rawTargets.add(RawDetection("bottle", box, bottleScore))
                    }
                    // Also evaluate tall "cup" independently so weak bottle logits never starve a strong tumbler/bottle cup logit.
                    if (sCup >= GrounderFilters.CUP_AS_BOTTLE_MIN_SCORE &&
                        (!bottleKept || sCup > bottleScore) &&
                        !GrounderFilters.isAnchorSuppressed(CLASS_CUP, sCup, anchorScores)
                    ) {
                        rawTargets.add(RawDetection("cup", box, sCup))
                    }
                    if (sLaptop >= 0.20f && !GrounderFilters.isAnchorSuppressed(CLASS_LAPTOP, sLaptop, anchorScores)) {
                        rawTargets.add(RawDetection("laptop", box, sLaptop))
                    }
                }

                // Pass 2: ONLY if any target candidate exists, collect overlapping rival boxes near target union bounds.
                if (rawTargets.isNotEmpty()) {
                    var minL = 1f; var minT = 1f; var maxR = 0f; var maxB = 0f
                    for (t in rawTargets) {
                        if (t.box.left < minL) minL = t.box.left
                        if (t.box.top < minT) minT = t.box.top
                        if (t.box.right > maxR) maxR = t.box.right
                        if (t.box.bottom > maxB) maxB = t.box.bottom
                    }
                    val uL = (minL * scaledW + padX) - 8f
                    val uT = (minT * scaledH + padY) - 8f
                    val uR = (maxR * scaledW + padX) + 8f
                    val uB = (maxB * scaledH + padY) + 8f

                    for (rId in RIVAL_CLASS_IDS) {
                        if (rId >= clsCount) continue
                        val rRow = 4 + rId
                        val rLabel = YOLO_COCO_LABELS[rId]
                        for (a in 0 until numAnchors) {
                            val rs = attr(rRow, a)
                            if (rs < GrounderFilters.RIVAL_MIN_SCORE) continue
                            val cxPx = attr(0, a)
                            val cyPx = attr(1, a)
                            val bwPx = attr(2, a)
                            val bhPx = attr(3, a)
                            if (bwPx <= 1f || bhPx <= 1f) continue
                            if (cxPx + bwPx * 0.5f < uL || cxPx - bwPx * 0.5f > uR ||
                                cyPx + bhPx * 0.5f < uT || cyPx - bhPx * 0.5f > uB
                            ) continue
                            val box = Box.clamped(
                                (cxPx - bwPx * 0.5f - padX) / scaledW,
                                (cyPx - bhPx * 0.5f - padY) / scaledH,
                                (cxPx + bwPx * 0.5f - padX) / scaledW,
                                (cyPx + bhPx * 0.5f - padY) / scaledH
                            )
                            if (box.isValid()) rawRivals.add(RawDetection(rLabel, box, rs))
                        }
                    }
                }
            }
        }

        val (filteredDets, rejected) = GrounderFilters.filterAndMap(
            rawTargets = rawTargets,
            rawRivals = rawRivals,
            allowed = allowed,
            frameWidth = fw,
            frameHeight = fh,
            minScoreOverride = minScoreOverride,
            hasActiveTrack = { label, box -> tracker.hasActiveTrackNear(label, box, timestampMs) }
        )

        val crops = HashMap<Det, Pair<Bitmap?, Appearance?>>()
        val verifiedDets = ArrayList<Det>(filteredDets.size)
        val finalRejected = ArrayList<String>(rejected)

        for (d in filteredDets) {
            val pix = safeCrop(frame, d.box).let { it to it?.let(::lookOf) }
            val look = pix.second
            val area = (d.box.right - d.box.left) * (d.box.bottom - d.box.top)
            // White/clear bottles can read bright with low internal contrast; only reject huge blank walls/tables.
            val flatBrightSurface = look != null && area > 0.25f && look.luma > 240f && look.contrast < 6f && d.score < 0.55f
            if (flatBrightSurface) {
                finalRejected.add("${d.label}:${"%.2f".format(d.score)}:flatbright")
                continue
            }
            val why = if (frameDark && d.score < 0.28f) rejectReason(d.score, d.box, KEEP_SCORE_FLOOR, look) else null
            if (why == null) {
                verifiedDets.add(d)
                crops[d] = pix
            } else {
                finalRejected.add("${d.label}:${"%.2f".format(d.score)}:$why")
            }
        }

        val ids = tracker.assign(verifiedDets, timestampMs)
        val cands = verifiedDets.indices.map { i ->
            val d = verifiedDets[i]
            val ev = tracker.evidence(ids[i])!!
            val (crop, look) = crops[d] ?: (null to null)
            val beverage = if (d.label in SupportedClasses.DRINK_CONTAINERS) {
                crop?.let { ColorClassifier.classify(it) } ?: "unknown"
            } else "unknown"
            val dark = frameDark || (luma < 55f && look?.darkFlat == true)
            val isConfirmed = ev.confirmed || (!dark && d.score >= INSTANT_CONFIRM_SCORE)
            val calibratedScore = if (!dark && ev.confirmed && ev.score >= 0.48f && ev.conflict == null) {
                maxOf(ev.score, Candidate.STRONG_SCORE)
            } else ev.score
            Candidate(
                id = ids[i],
                label = d.label,
                beverage = beverage,
                box = d.box,
                score = calibratedScore,
                crop = crop,
                confirmed = isConfirmed,
                conflict = ev.conflict,
                lowLight = dark,
                look = look,
                frameMs = timestampMs
            )
        }

        val now = SystemClock.uptimeMillis()
        if ((cands.isEmpty() && (near.isNotEmpty() || finalRejected.isNotEmpty())) && now - lastNearLogMs > 1800L) {
            lastNearLogMs = now
            Log.i("REACH", "yolo empty luma=%.0f near=%s rej=%s".format(luma, near.distinct().take(6), finalRejected.distinct().take(6)))
        }
        return cands
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Throwable) {
        }
    }

    private fun meanLuma(frame: Bitmap): Float {
        val w = frame.width; val h = frame.height
        val row = IntArray(w)
        var sum = 0L; var n = 0
        for (y in 0 until h step 16) {
            frame.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step 16) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += (299 * r + 587 * g + 114 * b) / 1000
                n++
            }
        }
        return if (n == 0) 255f else sum.toFloat() / n
    }

    private fun ensureModelFile(context: Context, assetName: String): File {
        val outFile = File(context.filesDir, assetName)
        val marker = File(context.filesDir, "$assetName.ver")
        val appUpdateMs = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        } catch (_: Exception) {
            "0"
        }
        val upToDate = outFile.exists() &&
            outFile.length() > 1_000_000L &&
            marker.exists() &&
            runCatching { marker.readText() }.getOrNull() == appUpdateMs
        if (!upToDate) {
            val tmp = File(context.filesDir, "$assetName.tmp")
            context.assets.open(assetName).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
            marker.writeText(appUpdateMs)
        }
        return outFile
    }

    companion object {
        const val MODEL_ASSET = "yolo11n.onnx"
        private const val INPUT_SIZE = 640
        private const val KEEP_SCORE_FLOOR = 0.15f
        private const val INSTANT_CONFIRM_SCORE = 0.46f
        private val LETTERBOX_BG = Color.rgb(114, 114, 114)

        private val RIVAL_CLASS_IDS = intArrayOf(
            13, 24, 26, 28, 40, 45, 56, 57, 59, 60, 62, 64, 65, 66, 67, 68, 69, 70, 72, 73, 74, 75
        )
    }
}
