package com.reach.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * V0 — owns the rear camera via CameraX.
 * Emits upright RGBA bitmaps at analysis resolution to a single listener.
 * Keep it dumb: no ML in here.
 */
class CameraXSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrame: (Bitmap, Long) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(executor) { proxy ->
                try {
                    val bmp = proxy.toBitmap() // RGBA_8888 supported by CameraX
                    val rotated = rotate(bmp, proxy.imageInfo.rotationDegrees)
                    onFrame(rotated, proxy.imageInfo.timestamp)
                } finally {
                    proxy.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(context))
    }

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun stop() {
        executor.shutdown()
        executor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}
