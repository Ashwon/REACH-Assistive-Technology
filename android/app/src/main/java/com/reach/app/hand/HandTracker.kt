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
data class HandResult(
    val present: Boolean,
    val indexTipX: Float = 0f,   // normalized 0..1 in frame coords
    val indexTipY: Float = 0f,
    val handSize: Float = 0f     // normalized wrist->middle_mcp distance, crude proximity proxy
)

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
        val mpImage = BitmapImageBuilder(frame).build()
        val result = landmarker.detectForVideo(mpImage, timestampMs)
        if (result.landmarks().isEmpty()) return HandResult(present = false)

        val lm = result.landmarks()[0]
        val indexTip = lm[8]            // INDEX_FINGER_TIP
        val wrist = lm[0]
        val middleMcp = lm[9]
        val dx = wrist.x() - middleMcp.x()
        val dy = wrist.y() - middleMcp.y()
        val size = kotlin.math.sqrt(dx * dx + dy * dy)

        return HandResult(
            present = true,
            indexTipX = indexTip.x(),
            indexTipY = indexTip.y(),
            handSize = size
        )
    }

    fun close() = landmarker.close()
}
