package com.reach.app.camera

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import com.reach.app.spatial.DepthFrame
import com.reach.app.spatial.Intrinsics
import com.reach.app.spatial.affineOrNull
import com.reach.app.spatial.intrinsicsMap
import java.nio.ByteOrder
import kotlin.math.abs
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Depth mode: an ARCore session owns the (main) rear camera instead of CameraX. ARCore picks its own camera and
 * cannot use the ultrawide. Separate from CameraXSource; MainActivity runs one or the other, never both.
 *
 * Each new ARCore frame becomes one upright bitmap — what the preview shows and what the models see, so preview,
 * detection, hands and depth share one image — plus a [DepthFrame] registered to it:
 *  - CPU image (YUV, sensor orientation) -> RGBA, rotated clockwise by the camera's sensor orientation (portrait).
 *  - Depth map (uint16 mm) copied with its row stride. It follows the GPU texture's crop, not the CPU image's, so
 *    the CPU-image -> depth mapping comes from frame.transformCoordinates2d(IMAGE_NORMALIZED -> TEXTURE_NORMALIZED)
 *    (the documented way), checked every frame to be axis-aligned scale + offset; otherwise depth is withheld.
 *  - Intrinsics of the CPU image (imageIntrinsics), scaled if ARCore reports them for another size.
 * The GL thread is only there because Session.update() needs a GL context and a camera texture.
 *
 * Any failure (no ARCore, no depth support, camera busy, repeated conversion errors) calls [onFailure] once, on
 * the main thread, so the caller can return to CameraX.
 */
class ArCoreSource(
    private val activity: Activity,
    private val host: ViewGroup,
    private val onPreview: (Bitmap) -> Unit,            // GL thread; every converted frame
    private val onFrame: (Bitmap, DepthFrame?) -> Unit, // analysis thread; dropped while the previous one runs
    private val onFailure: (String) -> Unit
) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private var glView: GLSurfaceView? = null
    private var cameraTex = 0
    private var rotation = 90
    private var lastFrameNs = 0L
    private var errors = 0
    private var loggedSetup = false
    private var mapRejects = 0
    /** Tracking state and whether depth is arriving, for the diagnostics panel; logged when it changes. */
    @Volatile var status = "starting"; private set
    private var pixels = IntArray(0)
    private val failed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val analysisExec = Executors.newSingleThreadExecutor()

    /** Main thread. False (after calling [onFailure]) when depth mode cannot run on this device. */
    fun start(): Boolean {
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(activity)
            if (!availability.isSupported) return fail("ARCore not supported ($availability)")
            if (ArCoreApk.getInstance().requestInstall(activity, false) != ArCoreApk.InstallStatus.INSTALLED)
                return fail("ARCore needs installing or updating")
            val s = Session(activity)
            if (!s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) { s.close(); return fail("depth not supported on this phone") }
            s.configure(Config(s).apply { depthMode = Config.DepthMode.AUTOMATIC; focusMode = Config.FocusMode.AUTO })
            val camId = s.cameraConfig.cameraId
            rotation = activity.getSystemService(CameraManager::class.java).getCameraCharacteristics(camId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            Log.i("REACH", "arcore camera=$camId sensorOrientation=$rotation cpuImage=${s.cameraConfig.imageSize} " +
                "texture=${s.cameraConfig.textureSize}")
            session = s
            glView = GLSurfaceView(activity).apply {
                setEGLContextClientVersion(2)
                setRenderer(this@ArCoreSource)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            host.addView(glView, FrameLayout.LayoutParams(1, 1))
            return resume()
        } catch (e: Exception) {
            return fail("ARCore start failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** Main thread (activity onResume). */
    fun resume(): Boolean {
        val s = session ?: return false
        return try { s.resume(); glView?.onResume(); true }
        catch (e: Exception) { fail("ARCore camera unavailable: ${e.javaClass.simpleName}") }
    }

    /** Main thread (activity onPause). The GL thread stops before the session it calls into is paused. */
    fun pause() { glView?.onPause(); session?.pause() }

    /** Main thread. Releases the camera so CameraX can bind it again. */
    fun stop() {
        pause()
        analysisExec.shutdown()
        analysisExec.awaitTermination(1, TimeUnit.SECONDS) // never hand a frame to a pipeline that is switching
        session?.close(); session = null
        glView?.let { host.removeView(it) }; glView = null
    }

    private fun fail(reason: String): Boolean {
        Log.w("REACH", "arcore fallback: $reason")
        if (failed.compareAndSet(false, true)) activity.runOnUiThread { onFailure(reason) }
        return false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        cameraTex = tex[0]
    }

    // Without display geometry, transformCoordinates2d returned NaN for every point on the A15.
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { session?.setDisplayGeometry(Surface.ROTATION_0, width, height) }

    /** GL thread. */
    override fun onDrawFrame(gl: GL10?) {
        val s = session ?: return
        if (failed.get()) return
        try {
            s.setCameraTextureName(cameraTex)
            val frame = s.update()
            if (frame.timestamp == lastFrameNs) return // no new camera image
            lastFrameNs = frame.timestamp
            val bmp = frame.acquireCameraImage().use { toUprightBitmap(it) }
            onPreview(bmp)
            val depth = if (busy.get()) null else readDepth(frame).also { d ->
                val st = "tracking=${frame.camera.trackingState} depth=${if (d != null) "yes" else "no"}" +
                    (frame.camera.trackingFailureReason.takeIf { it != com.google.ar.core.TrackingFailureReason.NONE }?.let { " ($it)" } ?: "")
                if (st != status) { status = st; Log.i("REACH", "arcore $st") }
            }
            if (busy.compareAndSet(false, true)) {
                analysisExec.execute { try { onFrame(bmp, depth) } finally { busy.set(false) } }
            }
            errors = 0
        } catch (e: NotYetAvailableException) {
            // camera image not ready yet: normal for the first frames
        } catch (e: Exception) {
            Log.w("REACH", "arcore frame error", e)
            if (++errors >= 30) fail("ARCore frames keep failing: ${e.javaClass.simpleName}")
        }
    }

    /** Depth registered to this frame's CPU image, or null (not ready yet, or the mapping is not what we can use). */
    private fun readDepth(frame: Frame): DepthFrame? {
        val image = try { frame.acquireDepthImage16Bits() } catch (e: NotYetAvailableException) { return null }
        image.use { d ->
            val ii = frame.camera.imageIntrinsics
            val dims = ii.imageDimensions; val f = ii.focalLength; val c = ii.principalPoint
            val cpu = session!!.cameraConfig.imageSize
            val sx = cpu.width.toFloat() / dims[0]; val sy = cpu.height.toFloat() / dims[1]
            val intr = Intrinsics(f[0] * sx, f[1] * sy, c[0] * sx, c[1] * sy, cpu.width, cpu.height)

            // CPU image (normalized) -> texture (normalized) = depth map (normalized), at 5 probe points: ARCore's own
            // transform, cross-checked against the mapping implied by the two images' intrinsics (same lens, so a
            // pixel's ray is the same in both). Depth is used only when both exist and agree within 1%.
            val probe = floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0.5f, 0.5f)
            val out = FloatArray(probe.size)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, probe, Coordinates2d.TEXTURE_NORMALIZED, out)
            val ti = frame.camera.textureIntrinsics
            val tex = Intrinsics(ti.focalLength[0], ti.focalLength[1], ti.principalPoint[0], ti.principalPoint[1],
                ti.imageDimensions[0], ti.imageDimensions[1])
            val fromIntr = intrinsicsMap(intr, tex)
            val fromArcore = affineOrNull(probe, out)
            val agree = fromArcore != null && (0 until 4).all { abs(fromArcore[it] - fromIntr[it]) <= 0.01f }
            val map = if (agree) fromArcore else null

            val w = d.width; val h = d.height
            val plane = d.planes[0]
            val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN) // D_16: little-endian uint16 millimetres
            val mm = ShortArray(w * h)
            for (y in 0 until h) for (x in 0 until w) mm[y * w + x] = buf.getShort(y * plane.rowStride + x * plane.pixelStride)

            if (!loggedSetup || (map == null && ++mapRejects % 30 == 1)) {
                loggedSetup = true
                Log.i("REACH", "arcore setup cpu=${cpu.width}x${cpu.height} imageIntr=${dims.joinToString("x")} " +
                    "f=%.1f,%.1f c=%.1f,%.1f tex=${tex.w}x${tex.h} texF=%.1f,%.1f texC=%.1f,%.1f depth=${w}x$h ".format(
                        f[0], f[1], c[0], c[1], tex.fx, tex.fy, tex.cx, tex.cy) +
                    "rotation=$rotation arcoreProbe=${out.joinToString(",") { "%.3f".format(it) }} " +
                    "arcoreMap=${fromArcore?.joinToString(",") { "%.4f".format(it) }} intrMap=${fromIntr.joinToString(",") { "%.4f".format(it) }} " +
                    "agree=$agree skewMs=${(frame.timestamp - d.timestamp) / 1_000_000}")
            }
            if (map == null) return null
            return DepthFrame(w, h, mm, intr, rotation, map, frame.timestamp, d.timestamp)
        }
    }

    private fun toUprightBitmap(img: Image): Bitmap {
        val w = img.width; val h = img.height
        if (pixels.size != w * h) pixels = IntArray(w * h)
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val yRow = yP.rowStride; val uvRow = uP.rowStride; val uvPix = uP.pixelStride
        for (j in 0 until h) {
            val yOff = j * yRow; val uvOff = (j shr 1) * uvRow
            for (i in 0 until w) {
                val yy = yb.get(yOff + i).toInt() and 0xFF
                val k = uvOff + (i shr 1) * uvPix
                val u = (ub.get(k).toInt() and 0xFF) - 128
                val v = (vb.get(k).toInt() and 0xFF) - 128
                val r = (yy + ((1436 * v) shr 10)).coerceIn(0, 255)
                val g = (yy - ((352 * u + 731 * v) shr 10)).coerceIn(0, 255)
                val b = (yy + ((1815 * u) shr 10)).coerceIn(0, 255)
                pixels[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val raw = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return raw
        return Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }
}
