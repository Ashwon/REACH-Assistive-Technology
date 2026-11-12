package com.reach.app.hand

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/**
 * V1 — MediaPipe Hand Landmarker in VIDEO mode (synchronous per-frame,
 * simpler to reason about than LIVE_STREAM callbacks under a deadline).
 *
 * Uses [TRACK_HANDS] = 1 so MediaPipe's VIDEO-mode crop tracker skips the expensive
 * full-frame palm detector whenever a hand is already tracked in view, and attempts
 * [Delegate.GPU] with automatic fallback to [Delegate.CPU].
 *
 * Requires app/src/main/assets/hand_landmarker.task (see scripts/download_models.sh).
 */
class HandTracker(private val context: Context, private val numHands: Int = MAX_HANDS) {

    private var usingGpu = true
    private var landmarker2: HandLandmarker = createLandmarker(useGpu = true, hands = numHands)
    private var landmarker1: HandLandmarker? = if (!usingGpu && numHands > 1) createLandmarker(useGpu = false, hands = 1) else null
    private var lastHandCount = 0
    private var frameCount = 0

    private fun createLandmarker(useGpu: Boolean, hands: Int): HandLandmarker {
        val buildOpts = { gpu: Boolean ->
            val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task")
            if (gpu) base.setDelegate(Delegate.GPU)
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base.build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(hands)
                .setMinHandDetectionConfidence(0.35f)
                .setMinHandPresenceConfidence(0.30f)
                .setMinTrackingConfidence(0.30f)
                .build()
        }
        if (useGpu) {
            try {
                return HandLandmarker.createFromOptions(context, buildOpts(true)).also {
                    Log.i("REACH", "HandTracker initialized with GPU delegate (numHands=$hands)")
                }
            } catch (e: Throwable) {
                Log.w("REACH", "HandTracker GPU delegate unavailable, falling back to CPU: ${e.message}")
            }
        }
        usingGpu = false
        return HandLandmarker.createFromOptions(context, buildOpts(false)).also {
            Log.i("REACH", "HandTracker initialized with CPU delegate (numHands=$hands)")
        }
    }

    fun detectAll(frame: Bitmap, timestampMs: Long): List<HandResult> {
        frameCount++
        val mpImg = BitmapImageBuilder(frame).build()
        val fastSingle = !usingGpu && landmarker1 != null && lastHandCount == 1 && (frameCount % 3 != 0)
        val active = if (fastSingle) landmarker1!! else landmarker2
        val result = try {
            active.detectForVideo(mpImg, timestampMs)
        } catch (e: Throwable) {
            if (usingGpu) {
                Log.w("REACH", "HandTracker GPU detectForVideo failed, switching to CPU: ${e.message}")
                try { landmarker2.close() } catch (_: Throwable) {}
                landmarker2 = createLandmarker(useGpu = false, hands = numHands)
                if (numHands > 1) landmarker1 = createLandmarker(useGpu = false, hands = 1)
                landmarker2.detectForVideo(mpImg, timestampMs)
            } else throw e
        }
        // Landmarks are normalized to the bitmap we passed: the upright frame the detector also sees.
        val hands = result.landmarks().mapIndexed { i, lm ->
            val side = result.handednesses().getOrNull(i)?.firstOrNull()
            HandResult.fromLandmarks(
                lm.map { Lm(it.x(), it.y(), it.z(), it.visibility().orElse(null), it.presence().orElse(null)) },
                score = side?.score() ?: 0f,
                handedness = side?.categoryName(),
                world = result.worldLandmarks().getOrNull(i)?.map { Lm(it.x(), it.y(), it.z()) } ?: emptyList()
            )
        }.filter { it.present }
        lastHandCount = hands.size
        return hands
    }

    fun close() {
        try { landmarker2.close() } catch (_: Throwable) {}
        try { landmarker1?.close() } catch (_: Throwable) {}
    }

    companion object {
        const val MAX_HANDS = 2
        const val TRACK_HANDS = 2
    }
}
