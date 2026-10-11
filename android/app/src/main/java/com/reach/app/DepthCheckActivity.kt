package com.reach.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException

/**
 * V3 — Isolated ARCore Depth verification for the A15 5G.
 *
 * Deliberately NOT integrated with the main pipeline (CameraX owns the camera
 * there; ARCore owns it here). This proves the "ARCore depth is tested"
 * success criterion without risking the demo. Integration = post-deadline.
 *
 * Shows: ARCore availability, Depth API support, and the depth (mm) at the
 * center of the depth image, refreshed continuously.
 */
class DepthCheckActivity : AppCompatActivity() {

    private var session: Session? = null
    private lateinit var txt: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        txt = TextView(this).apply { textSize = 20f; setPadding(40, 120, 40, 40) }
        setContentView(txt)
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
            handler.post(poll)
        } catch (e: Exception) {
            txt.text = "ARCore init failed: ${e.message}\nFallback: Level 1 (2D guidance)."
            Log.w("REACH", "depthcheck init failed", e)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val s = session ?: return
            try {
                val frame = s.update()
                // ARCore's own CPU image and intrinsics: the frame its depth map is aligned to (not CameraX's).
                val intr = frame.camera.imageIntrinsics
                val cam = "ARCore image ${intr.imageDimensions.joinToString("x")} fx=%.0f fy=%.0f".format(intr.focalLength[0], intr.focalLength[1])
                try {
                    frame.acquireDepthImage16Bits().use { depth ->
                        val w = depth.width; val h = depth.height
                        val buf = depth.planes[0].buffer.asShortBuffer()
                        val centerMm = buf.get((h / 2) * w + (w / 2)).toInt() and 0xFFFF
                        txt.text = "$cam\nDepth image ${w}x$h\nCenter depth: $centerMm mm" +
                            "\n(0 = no data; depth needs texture + slight motion)" +
                            "\n\nPASS if you see plausible mm values on nearby objects."
                        Log.i("REACH", "depthcheck $cam depth=${w}x$h centerMm=$centerMm")
                    }
                } catch (e: NotYetAvailableException) {
                    Log.i("REACH", "depthcheck $cam depth=not-yet") // normal for the first seconds
                }
            } catch (e: Exception) {
                txt.text = "Frame error: ${e.message}"
                Log.w("REACH", "depthcheck frame error", e)
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
    }
}
