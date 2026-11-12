package com.reach.app.grounding

/**
 * Raw unmapped COCO detection used by [GrounderFilters] before rival suppression
 * and target-class mapping.
 */
data class RawDetection(val label: String, val box: Box, val score: Float)

/**
 * Pure-Kotlin false-positive suppression, geometry validation, and class mapping
 * shared by [Yolo11Grounder] and [LocalDetectorGrounder].
 *
 * Key invariant: only true COCO "laptop" detections may become "laptop" (never
 * "tv", "keyboard", "book", or "cell phone"), and rectangular rival classes suppress
 * ambiguous laptop detections at both anchor level and post-NMS box level.
 */
object GrounderFilters {
    const val BOTTLE_MIN_SCORE = 0.28f
    const val BOTTLE_KEEP_SCORE = 0.22f
    const val LAPTOP_MIN_SCORE = 0.32f
    const val LAPTOP_KEEP_SCORE = 0.24f
    const val CUP_AS_BOTTLE_MIN_SCORE = 0.52f
    const val CUP_MIN_ASPECT = 1.45f
    const val RIVAL_MIN_SCORE = 0.22f
    const val NMS_IOU = 0.45f

    const val CLASS_BOTTLE = 39
    const val CLASS_WINE_GLASS = 40
    const val CLASS_CUP = 41
    const val CLASS_BOWL = 45
    const val CLASS_LAPTOP = 63
    const val CLASS_KEYBOARD = 66
    const val CLASS_VASE = 75

    /** Standard Ultralytics COCO 80-class ordering verified from assets/yolo11n.onnx. */
    val YOLO_COCO_LABELS: Array<String> = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
        "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
        "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
        "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    private val SURFACE_RIVALS = setOf("dining table", "bed", "couch", "chair", "bench")
    private val RECT_OBJECT_RIVALS = setOf(
        "tv", "book", "cell phone", "suitcase", "handbag", "backpack",
        "microwave", "oven", "toaster", "refrigerator", "remote", "mouse", "clock"
    )
    private val BOTTLE_RIVALS = setOf("vase", "wine glass", "bowl", "remote", "cell phone")

    private val STRONG_RECT_RIVAL_IDS = intArrayOf(
        13, // bench
        24, // backpack
        26, // handbag
        28, // suitcase
        56, // chair
        57, // couch
        59, // bed
        60, // dining table
        62, // tv
        64, // mouse
        65, // remote
        67, // cell phone
        68, // microwave
        69, // oven
        70, // toaster
        72, // refrigerator
        73, // book
        74  // clock
    )

    /**
     * Anchor-level suppression using all 80 YOLO logits at the same grid cell/box.
     * Prevents weak/ambiguous "laptop" or "bottle" logits from winning when another
     * rectangular/cylindrical COCO class scores strongly on the exact same anchor.
     */
    fun isAnchorSuppressed(classId: Int, score: Float, classScores: FloatArray): Boolean {
        if (classScores.size < YOLO_COCO_LABELS.size) return false
        return when (classId) {
            CLASS_LAPTOP -> {
                val kb = classScores[CLASS_KEYBOARD]
                if (kb >= score && score < 0.65f) return true

                var maxRect = 0f
                for (id in STRONG_RECT_RIVAL_IDS) {
                    val v = classScores[id]
                    if (v > maxRect) maxRect = v
                }
                if (maxRect >= score * 0.92f && score < 0.72f) return true
                if (maxRect >= score * 0.74f && score < 0.52f) return true

                var maxAnyOther = 0f
                for (i in 0 until YOLO_COCO_LABELS.size) {
                    if (i == CLASS_LAPTOP || i == CLASS_BOTTLE || i == CLASS_CUP || i == CLASS_KEYBOARD) continue
                    val v = classScores[i]
                    if (v > maxAnyOther) maxAnyOther = v
                }
                maxAnyOther >= score && score < 0.62f
            }
            CLASS_BOTTLE -> {
                val rival = maxOf(
                    classScores[CLASS_VASE],
                    classScores[CLASS_WINE_GLASS],
                    classScores[CLASS_BOWL],
                    classScores[65], // remote
                    classScores[67]  // cell phone
                )
                rival >= score * 0.95f && score < 0.64f
            }
            CLASS_CUP -> {
                if (score < CUP_AS_BOTTLE_MIN_SCORE) return true
                val rival = maxOf(
                    classScores[CLASS_VASE],
                    classScores[CLASS_WINE_GLASS],
                    classScores[CLASS_BOWL]
                )
                rival >= score * 0.78f
            }
            else -> false
        }
    }

    /** Visual pixel geometry checks invariant to bitmap dimensions. */
    fun passesGeometry(
        targetLabel: String,
        rawLabel: String,
        box: Box,
        frameWidth: Int,
        frameHeight: Int,
        hasActiveTrack: Boolean
    ): Boolean {
        val bw = box.right - box.left
        val bh = box.bottom - box.top
        if (bw < Box.MIN_SIDE || bh < Box.MIN_SIDE) return false
        if (bw * bh > Box.MAX_AREA) return false

        val pw = bw * frameWidth.coerceAtLeast(1)
        val ph = bh * frameHeight.coerceAtLeast(1)

        return when (targetLabel) {
            "laptop" -> {
                val aspect = pw / ph.coerceAtLeast(1e-3f)
                val validAspect = if (hasActiveTrack) aspect in 0.42f..4.0f else aspect in 0.55f..3.6f
                if (!validAspect) return false
                hasActiveTrack || (bw >= 0.045f && bh >= 0.035f)
            }
            "bottle" -> {
                val tallness = ph / pw.coerceAtLeast(1e-3f)
                if (rawLabel == "cup") tallness >= CUP_MIN_ASPECT else tallness >= 0.45f
            }
            else -> true
        }
    }

    /**
     * Checks post-NMS rival boxes for rectangular/cylindrical objects occupying the same region.
     * Distinguishes a laptop keyboard inside the lower half of a real laptop (kept!) from a
     * standalone keyboard, monitor, book, phone, or box (suppressed).
     */
    fun suppressingRival(
        targetLabel: String,
        candBox: Box,
        candScore: Float,
        rivals: List<RawDetection>
    ): String? {
        val candArea = boxArea(candBox).coerceAtLeast(1e-6f)
        val candH = candBox.bottom - candBox.top
        for (rival in rivals) {
            val inter = intersectionArea(candBox, rival.box)
            if (inter <= 0f) continue
            val rArea = boxArea(rival.box).coerceAtLeast(1e-6f)
            val iou = candBox.iou(rival.box)
            val containCand = inter / candArea
            val containRival = inter / rArea

            when (targetLabel) {
                "laptop" -> {
                    when (rival.label) {
                        "keyboard" -> {
                            val rCenterY = (rival.box.top + rival.box.bottom) * 0.5f
                            val internalKeyboard = rArea <= candArea * 0.68f &&
                                containRival >= 0.60f &&
                                rCenterY >= candBox.top + 0.28f * candH
                            if (!internalKeyboard &&
                                (iou >= 0.38f || (containCand >= 0.55f && containRival >= 0.55f)) &&
                                rival.score >= candScore * 0.78f &&
                                candScore < 0.64f
                            ) {
                                return "keyboard"
                            }
                        }
                        in SURFACE_RIVALS -> {
                            // Real laptops sit on desks/tables/beds; only suppress when box size matches the surface itself.
                            if (candArea >= rArea * 0.55f &&
                                iou >= 0.35f &&
                                rival.score >= candScore * 0.85f &&
                                candScore < 0.60f
                            ) {
                                return rival.label
                            }
                        }
                        in RECT_OBJECT_RIVALS -> {
                            // Do not suppress a full laptop just because a tiny phone sits beside/on it.
                            val comparableScale = rArea >= candArea * 0.35f && candArea >= rArea * 0.25f
                            val sameRegion = iou >= 0.25f || (containCand >= 0.55f && containRival >= 0.45f)
                            if (comparableScale && sameRegion &&
                                ((rival.score >= candScore * 0.78f && candScore < 0.70f) ||
                                    (rival.score >= candScore * 1.05f && candScore < 0.82f))
                            ) {
                                return rival.label
                            }
                        }
                    }
                }
                "bottle" -> {
                    if (rival.label in BOTTLE_RIVALS) {
                        val sameRegion = iou >= 0.28f ||
                            (inter / minOf(candArea, rArea) >= 0.60f && rArea >= candArea * 0.45f)
                        if (sameRegion && rival.score >= candScore * 0.88f && candScore < 0.68f) {
                            return rival.label
                        }
                    }
                }
            }
        }
        return null
    }

    /** Per-class greedy Non-Maximum Suppression. */
    fun nmsPerClass(dets: List<RawDetection>, iouThresh: Float = NMS_IOU): List<RawDetection> {
        if (dets.size <= 1) return dets
        val out = ArrayList<RawDetection>(dets.size)
        for ((_, group) in dets.groupBy { it.label }) {
            val sorted = group.sortedByDescending { it.score }
            val kept = ArrayList<RawDetection>(sorted.size)
            for (d in sorted) {
                if (kept.none { it.box.iou(d.box) >= iouThresh }) kept.add(d)
            }
            out.addAll(kept)
        }
        return out
    }

    /**
     * Applies NMS, target mapping, score floors (with active-track hysteresis),
     * geometry validation, rival suppression, and same-class merging.
     *
     * Returns `(keptDetections, diagnosticTags)` where diagnosticTags summarize rejected items.
     */
    fun filterAndMap(
        rawTargets: List<RawDetection>,
        rawRivals: List<RawDetection>,
        allowed: Set<String>,
        frameWidth: Int,
        frameHeight: Int,
        minScoreOverride: Float? = null,
        hasActiveTrack: (label: String, box: Box) -> Boolean = { _, _ -> false }
    ): Pair<List<Det>, List<String>> {
        val nmsTargets = nmsPerClass(rawTargets)
        val nmsRivals = nmsPerClass(rawRivals.filter { it.score >= RIVAL_MIN_SCORE })
        val accepted = ArrayList<Det>()
        val rejected = ArrayList<String>()

        for (d in nmsTargets) {
            val rawName = d.label.lowercase()
            val mappedLabel = when (rawName) {
                "bottle" -> "bottle"
                "cup" -> if (d.score >= CUP_AS_BOTTLE_MIN_SCORE) "bottle" else null
                "laptop" -> "laptop"
                else -> null
            } ?: continue
            if (mappedLabel !in allowed) continue

            val mappedScore = if (rawName == "cup") d.score * 0.90f else d.score
            val tracked = hasActiveTrack(mappedLabel, d.box)
            val floor = when {
                minScoreOverride != null -> minScoreOverride
                mappedLabel == "bottle" -> if (tracked) BOTTLE_KEEP_SCORE else BOTTLE_MIN_SCORE
                mappedLabel == "laptop" -> if (tracked) LAPTOP_KEEP_SCORE else LAPTOP_MIN_SCORE
                else -> MIN_SCORE
            }

            if (mappedScore < floor) {
                if (mappedScore >= 0.16f) rejected.add("$mappedLabel:${"%.2f".format(mappedScore)}:low")
                continue
            }
            if (!passesGeometry(mappedLabel, rawName, d.box, frameWidth, frameHeight, tracked)) {
                rejected.add("$mappedLabel:${"%.2f".format(mappedScore)}:geom")
                continue
            }
            val rivalWhy = suppressingRival(mappedLabel, d.box, mappedScore, nmsRivals)
            if (rivalWhy != null) {
                rejected.add("$mappedLabel:${"%.2f".format(mappedScore)}:rival=$rivalWhy")
                continue
            }
            val why = rejectReason(mappedScore, d.box, floor, null)
            if (why != null) {
                rejected.add("$mappedLabel:${"%.2f".format(mappedScore)}:$why")
                continue
            }
            accepted.add(Det(mappedLabel, mappedScore, d.box))
        }

        return mergeSameClassDetections(accepted) to rejected
    }

    /** Merges overlapping or nested boxes of the same target class. */
    fun mergeSameClassDetections(raw: List<Det>): List<Det> {
        if (raw.size <= 1) return raw
        val sorted = raw.sortedByDescending { it.score }.toMutableList()
        val out = ArrayList<Det>(sorted.size)
        while (sorted.isNotEmpty()) {
            var cur = sorted.removeAt(0)
            val it = sorted.iterator()
            while (it.hasNext()) {
                val other = it.next()
                if (other.label != cur.label) continue
                val overlapX = minOf(cur.box.right, other.box.right) - maxOf(cur.box.left, other.box.left)
                val minW = minOf(cur.box.right - cur.box.left, other.box.right - other.box.left).coerceAtLeast(1e-4f)
                val sameObject = cur.box.iou(other.box) > 0.18f ||
                    (overlapX > 0.45f * minW &&
                        other.box.top <= cur.box.bottom + 0.08f &&
                        cur.box.top <= other.box.bottom + 0.08f)
                if (sameObject) {
                    val mergedBox = Box.clamped(
                        minOf(cur.box.left, other.box.left),
                        minOf(cur.box.top, other.box.top),
                        maxOf(cur.box.right, other.box.right),
                        maxOf(cur.box.bottom, other.box.bottom)
                    )
                    val boostedScore = (maxOf(cur.score, other.score) + 0.04f).coerceAtMost(0.96f)
                    cur = Det(cur.label, boostedScore, mergedBox)
                    it.remove()
                }
            }
            out.add(cur)
        }
        return out
    }

    private fun boxArea(b: Box): Float =
        (b.right - b.left).coerceAtLeast(0f) * (b.bottom - b.top).coerceAtLeast(0f)

    private fun intersectionArea(a: Box, b: Box): Float {
        val w = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val h = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        return w * h
    }
}
