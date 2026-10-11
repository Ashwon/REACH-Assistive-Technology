package com.reach.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.reach.app.camera.CameraXSource
import com.reach.app.claude.ClaudeClient
import com.reach.app.databinding.ActivityMainBinding
import com.reach.app.grounding.LocalDetectorGrounder
import com.reach.app.hand.HandTracker
import com.reach.app.speech.Speaker
import com.reach.app.speech.VoiceInput

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraXSource
    private lateinit var controller: ReachController
    private lateinit var speaker: Speaker
    private lateinit var voice: VoiceInput

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) startPipeline()
            else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speaker = Speaker(this)
        voice = VoiceInput(this)

        binding.btnListen.setOnClickListener {
            if (!::controller.isInitialized) return@setOnClickListener // permissions still pending
            speaker.say("Listening.", interrupt = true)
            binding.btnListen.postDelayed({
                voice.listen { text ->
                    runOnUiThread {
                        if (text.isNullOrBlank()) speaker.say("I didn't hear that.")
                        else {
                            binding.txtHeard.text = text
                            controller.onUtterance(text)
                        }
                    }
                }
            }, 600) // let TTS finish before mic opens
        }
        binding.btnReset.setOnClickListener { if (::controller.isInitialized) controller.reset() }
        binding.btnDepth.setOnClickListener {
            startActivity(Intent(this, DepthCheckActivity::class.java))
        }

        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun startPipeline() {
        val grounder = LocalDetectorGrounder(this)
        val handTracker = HandTracker(this)

        controller = ReachController(
            grounder = grounder,
            handTracker = handTracker,
            claude = ClaudeClient(),
            speaker = speaker,
            scope = lifecycleScope
        ) { candidates, targetId, hand, status ->
            binding.overlay.candidates = candidates
            binding.overlay.targetId = targetId
            binding.overlay.hand = hand
            binding.overlay.statusText = status
            binding.overlay.postInvalidate()
        }

        camera = CameraXSource(this, this, binding.preview) { bmp, _ ->
            // MediaPipe VIDEO mode wants monotonically increasing ms timestamps.
            controller.onFrame(bmp, android.os.SystemClock.uptimeMillis())
        }
        camera.start()
        speaker.say("REACH ready. Press the bottom of the screen and ask for a drink.")
    }

    // Debug builds only: adb shell am start -f 0x20000000 -n com.reach.app/.MainActivity --es utterance "find my coke"
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val text = intent.getStringExtra("utterance")
        if (BuildConfig.DEBUG && text != null && ::controller.isInitialized) {
            binding.txtHeard.text = text
            controller.onUtterance(text)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::camera.isInitialized) camera.stop() // drain the analyzer before closing the native models
        if (::controller.isInitialized) controller.close()
        voice.destroy()
        speaker.shutdown()
    }
}
