package com.reach.app

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * V3 — Isolated ARCore Depth verification for the A15 5G.
 *
 * Deliberately NOT integrated with the main pipeline (CameraX owns the camera
 * there; ARCore owns it here). This proves the "ARCore depth is tested"
 * success criterion without risking the demo. Integration = post-deadline.
 *
 * Shows: ARCore availability, Depth API support, and the depth (mm) at the
 * center of the depth image, refreshed continuously.
 *
 * Session.update() needs a current GL context and a camera texture (without them every call throws
 * MissingGlContextException), so a 1x1 GLSurfaceView drives it from its render thread.
 */
class DepthCheckActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    @Volatile private var session: Session? = null
    private lateinit var txt: TextView
    private lateinit var glView: GLSurfaceView
    private var cameraTex = 0
    private var lastUiMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_depth_check)
        txt = findViewById(R.id.depthStatusText)
        findViewById<android.view.View>(R.id.btnDepthBack).setOnClickListener { finish() }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(this@DepthCheckActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        findViewById<FrameLayout>(R.id.glHost).addView(glView, FrameLayout.LayoutParams(1, 1))
    }

    override fun onResume() {
        super.onResume()
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (!availability.isSupported) {
            txt.text = "ARCore NOT supported: $availability\nFallback: Level 1 (2D guidance)."
            Log.i("REACH", "depthcheck arcore=$availability")
            return
        }
        try {
            if (ArCoreApk.getInstance().requestInstall(this, true) ==
                ArCoreApk.InstallStatus.INSTALL_REQUESTED
            ) return

            val s = Session(this)
            val config = Config(s)
            val depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (depthSupported) config.depthMode = Config.DepthMode.AUTOMATIC
            config.focusMode = Config.FocusMode.AUTO
            s.configure(config)
            s.resume()
            session = s

            txt.text = "ARCore OK.\nDepth API supported: $depthSupported\nMove the phone slowly..."
            Log.i("REACH", "depthcheck arcore=ok depthSupported=$depthSupported")
            glView.onResume()
        } catch (e: Exception) {
            txt.text = "ARCore init failed: ${e.message}\nFallback: Level 1 (2D guidance)."
            Log.w("REACH", "depthcheck init failed", e)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        cameraTex = tex[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {}

    /** GL thread: update every frame (ARCore tracks from these), report at most 4x per second. */
    override fun onDrawFrame(gl: GL10?) {
        val s = session ?: return
        fun show(t: String) = runOnUiThread { txt.text = t }
        try {
            s.setCameraTextureName(cameraTex)
            val frame = s.update()
            val now = SystemClock.uptimeMillis()
            if (now - lastUiMs < 250) return
            lastUiMs = now
            // ARCore's own CPU image and intrinsics: the frame its depth map is aligned to (not CameraX's).
            val intr = frame.camera.imageIntrinsics
            val cam = "ARCore image ${intr.imageDimensions.joinToString("x")} fx=%.0f fy=%.0f tracking=${frame.camera.trackingState}"
                .format(intr.focalLength[0], intr.focalLength[1])
            try {
                frame.acquireDepthImage16Bits().use { depth ->
                    val w = depth.width; val h = depth.height
                    val plane = depth.planes[0] // little-endian uint16 millimetres; rows may be padded
                    val buf = plane.buffer.order(java.nio.ByteOrder.nativeOrder())
                    val centerMm = buf.getShort((h / 2) * plane.rowStride + (w / 2) * plane.pixelStride).toInt() and 0xFFFF
                    show("$cam\nDepth image ${w}x$h\nCenter depth: $centerMm mm" +
                        "\n(0 = no data; depth needs texture + slight motion)" +
                        "\n\nPASS if you see plausible mm values on nearby objects.")
                    Log.i("REACH", "depthcheck $cam depth=${w}x$h centerMm=$centerMm")
                }
            } catch (e: NotYetAvailableException) {
                show("$cam\nDepth not available yet: move the phone slowly.")
                Log.i("REACH", "depthcheck $cam depth=not-yet") // normal for the first seconds
            }
        } catch (e: Exception) {
            show("Frame error: $e")
            Log.w("REACH", "depthcheck frame error", e)
        }
    }

    override fun onPause() {
        super.onPause()
        glView.onPause() // stop the render thread before pausing the session it calls into
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
    }
}
