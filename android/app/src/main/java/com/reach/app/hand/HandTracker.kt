package com.reach.app.hand

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/**
 * V1 — MediaPipe Hand Landmarker in VIDEO mode (synchronous per-frame,
 * simpler to reason about than LIVE_STREAM callbacks under a deadline).
 *
 * Requires app/src/main/assets/hand_landmarker.task (see scripts/download_models.sh).
 */
class HandTracker(context: Context) {

    private val landmarker: HandLandmarker = HandLandmarker.createFromOptions(
        context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build()
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
    )

    fun detect(frame: Bitmap, timestampMs: Long): HandResult {
        val result = landmarker.detectForVideo(BitmapImageBuilder(frame).build(), timestampMs)
        val lm = result.landmarks().firstOrNull() ?: return HandResult.NONE
        // Landmarks are normalized to the bitmap we passed: the upright frame the detector also sees.
        return HandResult.fromLandmarks(
            lm.map { Lm(it.x(), it.y(), it.z(), it.visibility().orElse(null), it.presence().orElse(null)) },
            score = result.handednesses().firstOrNull()?.firstOrNull()?.score() ?: 0f
        )
    }

    fun close() = landmarker.close()
}
