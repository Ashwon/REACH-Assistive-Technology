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
 * Reports every hand it finds, in MediaPipe's order, which is NOT an identity: [HandSelector] decides
 * which one is the wearer's. With numHands = 1 MediaPipe keeps tracking whichever hand it found first and
 * stops looking for others, so a bystander's hand could hide the wearer's entirely.
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
            .setNumHands(MAX_HANDS)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
    )

    fun detectAll(frame: Bitmap, timestampMs: Long): List<HandResult> {
        val result = landmarker.detectForVideo(BitmapImageBuilder(frame).build(), timestampMs)
        // Landmarks are normalized to the bitmap we passed: the upright frame the detector also sees.
        return result.landmarks().mapIndexed { i, lm ->
            val side = result.handednesses().getOrNull(i)?.firstOrNull()
            HandResult.fromLandmarks(
                lm.map { Lm(it.x(), it.y(), it.z(), it.visibility().orElse(null), it.presence().orElse(null)) },
                score = side?.score() ?: 0f,
                handedness = side?.categoryName(),
                world = result.worldLandmarks().getOrNull(i)?.map { Lm(it.x(), it.y(), it.z()) } ?: emptyList()
            )
        }.filter { it.present }
    }

    fun close() = landmarker.close()

    companion object { const val MAX_HANDS = 2 }
}
