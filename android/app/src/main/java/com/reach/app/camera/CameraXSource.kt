package com.reach.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
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
    private var convNs = 0L
    private var convFrames = 0

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3) // same field of view as the (default 4:3) preview
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(executor) { proxy ->
                try {
                    val t0 = System.nanoTime()
                    val bmp = proxy.toBitmap() // RGBA_8888 supported by CameraX
                    val rotated = rotate(bmp, proxy.imageInfo.rotationDegrees)
                    convNs += System.nanoTime() - t0
                    if (++convFrames == 20) { Log.i("REACH", "convMs=${convNs / convFrames / 1_000_000} ${rotated.width}x${rotated.height}"); convNs = 0; convFrames = 0 }
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
