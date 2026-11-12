package com.reach.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.atan

/** A rear camera the phone exposes to apps. fovDeg: horizontal field of view in portrait (the sensor's short side). */
data class CamOption(val id: String, val fovDeg: Float, val focalMm: Float)

/**
 * V0 — owns the rear camera via CameraX.
 * Emits upright RGBA bitmaps at analysis resolution to a single listener. Keep it dumb: no ML in here.
 *
 * Lens choice is real, never simulated: every back camera Camera2 exposes is listed with its field of view from
 * its own focal length and sensor size, and the widest is bound by default (on the A15 5G: camera "2", the 1.4 mm
 * ultrawide; its zoom-ratio range starts at 1.0, so the ultrawide is reachable only by camera id). Preview and
 * analysis are both 4:3 from the same camera with no ViewPort, so the analysis frame keeps the full sensor view;
 * the preview only scales it (fill) and the overlay applies the same transform.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraXSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrame: (Bitmap, Long) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var convNs = 0L
    private var convFrames = 0
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null

    /** Back cameras, widest first. Empty until [start] has run. Main thread. */
    var cameras: List<CamOption> = emptyList(); private set
    @Volatile var boundId: String? = null; private set

    /** Binds [cameraId], or the widest back camera when null / not available. [onReady] runs on the main thread. */
    fun start(cameraId: String? = null, onReady: () -> Unit = {}) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            cameras = p.availableCameraInfos.mapNotNull { option(it) }.sortedByDescending { it.fovDeg }
            Log.i("REACH", "cameras " + cameras.joinToString { "id=${it.id} f=${it.focalMm}mm fov=%.0f°".format(it.fovDeg) })

            preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3) // same field of view as the 4:3 preview
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { it.setAnalyzer(executor, ::analyze) }
            bind(cameraId)
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Main thread. Rebinds the same use cases to another lens: the analyzer, models and controller keep running. */
    fun switchTo(cameraId: String) = bind(cameraId)

    /** Main thread. Frees the camera (for ARCore). [switchTo] binds it again. */
    fun release() { provider?.unbindAll(); boundId = null }

    private fun bind(cameraId: String?) {
        val p = provider ?: return
        val id = cameras.firstOrNull { it.id == cameraId }?.id ?: cameras.firstOrNull()?.id
        val selector = if (id == null) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }.build()
        // A missing or busy camera must not crash the app; the screen reports it when no frames arrive.
        try {
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, selector, preview!!, analysis!!)
            boundId = id
            Log.i("REACH", "camera bound id=$id")
        } catch (e: Exception) {
            boundId = null
            Log.e("REACH", "camera bind failed id=$id", e)
        }
    }

    private fun option(info: CameraInfo): CamOption? {
        val c2 = Camera2CameraInfo.from(info)
        if (c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return null
        val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val shortSide = minOf(size.width, size.height)
        return CamOption(c2.cameraId, Math.toDegrees(2.0 * atan(shortSide / (2.0 * f))).toFloat(), f)
    }

    private fun analyze(proxy: ImageProxy) {
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
