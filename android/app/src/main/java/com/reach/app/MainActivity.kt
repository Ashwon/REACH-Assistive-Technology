package com.reach.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.transition.TransitionManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.reach.app.camera.CameraXSource
import com.reach.app.claude.ClaudeClient
import com.reach.app.databinding.ActivityMainBinding
import com.reach.app.databinding.SheetSettingsBinding
import com.reach.app.grounding.LocalDetectorGrounder
import com.reach.app.hand.HandTracker
import com.reach.app.speech.Speaker
import com.reach.app.speech.VoiceInput
import com.reach.app.ui.CameraState
import com.reach.app.ui.Line
import com.reach.app.ui.ReachStatus
import com.reach.app.ui.Tone

/**
 * The one screen. Every view is driven by [ReachSnapshot]s from the controller (real per-frame state);
 * a 10 Hz main-thread loop renders the latest one, so inference never waits on the UI and spoken
 * guidance (issued by the controller on the camera thread) never waits on rendering.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraXSource
    private lateinit var controller: ReachController
    private lateinit var speaker: Speaker
    private lateinit var voice: VoiceInput
    private val prefs by lazy { getSharedPreferences("reach", MODE_PRIVATE) }

    @Volatile private var snapshot: ReachSnapshot? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastSpoken: String? = null
    private var pipelineStartedAt = 0L
    private var blocker: Blocker? = null
    private var listening = false
    private var renderedMode: Mode? = null
    private var pulseAnim: ObjectAnimator? = null
    private val main = Handler(Looper.getMainLooper())
    private val debugFrames = if (BuildConfig.DEBUG) DebugFrames(this) else null

    /** Problems the camera card explains instead of showing a live view. */
    private enum class Blocker { CAMERA_PERMISSION, MODELS }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (granted(Manifest.permission.CAMERA)) startPipeline() else blocker = Blocker.CAMERA_PERMISSION
            updateMicHelper()
        }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            updateMicHelper()
            if (ok) listen()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Chest-mounted and used by voice: a screen timeout would stop the camera.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speaker = Speaker(this).apply {
            enabled = prefs.getBoolean(PREF_SPEECH, true)
            volume = prefs.getFloat(PREF_VOLUME, 1f)
            onSpoken = { lastSpoken = it } // any thread; rendered by the UI loop
        }
        voice = VoiceInput(this)

        binding.btnPrimary.setOnClickListener { onPrimary() }
        binding.btnStop.setOnClickListener { if (::controller.isInitialized) controller.reset() }
        binding.inputLayout.setEndIconOnClickListener { listen() }
        binding.inputText.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) { findObject(); true } else false
        }
        binding.btnHand.setOnClickListener {
            if (::controller.isInitialized) controller.beginHandSetup() else speaker.say(getString(R.string.spoken_no_camera))
        }
        binding.btnSpeech.setOnClickListener { setSpeech(!speaker.enabled, announce = true) }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnCameraAction.setOnClickListener { requestCamera() }
        renderSpeechButton()

        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    override fun onStart() {
        super.onStart()
        // The camera restarts with the lifecycle: show "Starting camera" until frames flow again.
        lastFrameAt = 0L; pipelineStartedAt = SystemClock.uptimeMillis()
        main.post(renderLoop)
    }

    override fun onResume() {
        super.onResume()
        // Back from system settings with the camera now allowed.
        if (blocker == Blocker.CAMERA_PERMISSION && granted(Manifest.permission.CAMERA)) startPipeline()
        updateMicHelper()
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(renderLoop)
        pulse(binding.statusDot, false)
    }

    private fun startPipeline() {
        if (::controller.isInitialized) return
        val grounder = try { LocalDetectorGrounder(this) } catch (e: Exception) { return modelsFailed(e) }
        val handTracker = try { HandTracker(this) } catch (e: Exception) { grounder.close(); return modelsFailed(e) }
        blocker = null

        controller = ReachController(
            grounder = grounder,
            handTracker = handTracker,
            claude = ClaudeClient(),
            speaker = speaker,
            scope = lifecycleScope
        ) { s ->
            snapshot = s
            binding.overlay.candidates = s.candidates
            binding.overlay.targetId = s.targetId
            binding.overlay.hand = s.hand
            binding.overlay.others = s.otherHands
            binding.overlay.guiding = s.mode == Mode.GUIDING
            binding.overlay.postInvalidate()
        }

        camera = CameraXSource(this, this, binding.preview) { bmp, _ ->
            // MediaPipe VIDEO mode wants monotonically increasing ms timestamps.
            val now = SystemClock.uptimeMillis()
            lastFrameAt = now
            debugFrames?.onFrame(bmp, now)
            controller.onFrame(bmp, now)
        }
        pipelineStartedAt = SystemClock.uptimeMillis()
        camera.start()
        // Step one, before any search: establish which hand is the user's.
        controller.beginHandSetup(getString(R.string.spoken_ready) + " ")
    }

    private fun modelsFailed(e: Exception) {
        Log.e("REACH", "model init failed", e)
        blocker = Blocker.MODELS
        speaker.say(getString(R.string.spoken_models_failed))
    }

    // ---- Actions ----

    private fun mode() = if (::controller.isInitialized) controller.mode else Mode.IDLE

    private fun onPrimary() {
        when (mode()) {
            Mode.TARGET_LOCKED, Mode.TRACKING_LOST -> submit("guide me")
            Mode.GUIDING -> controller.confirmReached() // "I've got it"
            Mode.IDLE, Mode.REACHED -> findObject()
            Mode.SEARCHING -> {}
        }
    }

    /** Typed request if there is one, otherwise ask by voice. */
    private fun findObject() {
        val text = binding.inputText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) listen() else submit(text)
    }

    private fun submit(text: String) {
        hideKeyboard()
        if (!::controller.isInitialized) { speaker.say(getString(R.string.spoken_no_camera)); return }
        controller.onUtterance(text)
    }

    private fun listen() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.inputLayout.helperText = getString(R.string.mic_unavailable_helper)
            speaker.say(getString(R.string.mic_unavailable_helper))
            return
        }
        if (listening) return
        listening = true
        updateMicHelper()
        hideKeyboard()
        speaker.say(getString(R.string.spoken_listening))
        binding.root.postDelayed({ // let the prompt finish before the mic opens
            voice.listen { text ->
                runOnUiThread {
                    listening = false
                    updateMicHelper()
                    if (text.isNullOrBlank()) speaker.say(getString(R.string.spoken_not_heard))
                    else {
                        if (!isCommand(text)) binding.inputText.setText(text)
                        submit(text)
                    }
                }
            }
        }, 600)
    }

    private fun requestCamera() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        } else { // denied for good (or never asked from here): only system settings can grant it now
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun setSpeech(on: Boolean, announce: Boolean) {
        if (!on && announce) speaker.say(getString(R.string.spoken_speech_off)) // said before muting
        speaker.enabled = on
        if (on && announce) speaker.say(getString(R.string.spoken_speech_on))
        prefs.edit().putBoolean(PREF_SPEECH, on).apply()
        renderSpeechButton()
    }

    private fun showSettings() {
        val sheet = BottomSheetDialog(this)
        val b = SheetSettingsBinding.inflate(layoutInflater)
        b.switchSpeech.isChecked = speaker.enabled
        b.switchSpeech.setOnCheckedChangeListener { _, on -> setSpeech(on, announce = true) }
        b.sliderVolume.value = speaker.volume.coerceIn(0.1f, 1f)
        b.sliderVolume.setLabelFormatter { "${(it * 100).toInt()}%" }
        b.sliderVolume.addOnChangeListener { _, v, _ ->
            speaker.volume = v
            prefs.edit().putFloat(PREF_VOLUME, v).apply()
        }
        b.btnTestVoice.setOnClickListener { speaker.say(getString(R.string.spoken_test)) }
        b.btnDepth.setOnClickListener { sheet.dismiss(); startActivity(Intent(this, DepthCheckActivity::class.java)) }
        sheet.setContentView(b.root)
        sheet.show()
    }

    // ---- Rendering (main thread, 10 Hz) ----

    private val renderLoop = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 100) }
    }

    private fun cameraState(): CameraState {
        val now = SystemClock.uptimeMillis()
        return when {
            blocker != null -> CameraState.UNAVAILABLE
            !::controller.isInitialized -> CameraState.STARTING
            lastFrameAt == 0L -> if (now - pipelineStartedAt > 4000) CameraState.UNAVAILABLE else CameraState.STARTING
            now - lastFrameAt > 3000 -> CameraState.UNAVAILABLE
            else -> CameraState.LIVE
        }
    }

    private fun render() {
        val cam = cameraState()
        val s = snapshot.takeIf { cam == CameraState.LIVE } // a stale snapshot never drives the screen

        val status = ReachStatus.status(s, cam)
        setIfChanged(binding.statusText, status.text)
        binding.statusPill.contentDescription = "Status: ${status.text}"
        binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color(status.tone))
        pulse(binding.statusDot, status.tone == Tone.ACTIVE)
        chip(binding.chipObject, ReachStatus.objects(s))
        chip(binding.chipHand, ReachStatus.hand(s))

        val g = ReachStatus.guidance(s, cam)
        if (setIfChanged(binding.guidanceText, g.headline)) {
            binding.guidanceText.alpha = 0.35f
            binding.guidanceText.animate().alpha(1f).setDuration(180).start()
        }
        setIfChanged(binding.guidanceDetail, g.detail)
        binding.guidanceMode.visibility = if (g.directionOnly) View.VISIBLE else View.GONE
        val spoken = lastSpoken
        val spokenLine = when {
            !speaker.enabled -> getString(R.string.speech_off_note)
            spoken != null -> getString(R.string.last_spoken, spoken)
            else -> null
        }
        binding.lastSpoken.visibility = if (spokenLine == null) View.GONE else View.VISIBLE
        spokenLine?.let { setIfChanged(binding.lastSpoken, it) }

        renderCameraMessage(cam)
        renderActions(mode())
    }

    private fun renderCameraMessage(cam: CameraState) {
        val (title, body) = when {
            blocker == Blocker.CAMERA_PERMISSION -> R.string.camera_perm_title to R.string.camera_perm_body
            blocker == Blocker.MODELS -> R.string.models_title to R.string.models_body
            cam == CameraState.UNAVAILABLE -> R.string.camera_busy_title to R.string.camera_busy_body
            else -> null to null
        }
        binding.cameraMessage.visibility = if (title == null) View.GONE else View.VISIBLE
        if (title != null && body != null) {
            setIfChanged(binding.cameraMessageTitle, getString(title))
            setIfChanged(binding.cameraMessageBody, getString(body))
        }
        binding.btnCameraAction.visibility = if (blocker == Blocker.CAMERA_PERMISSION) View.VISIBLE else View.GONE
    }

    /** Only the actions that apply now: find, start guidance, or cancel/stop. */
    private fun renderActions(mode: Mode) {
        if (mode == renderedMode) return
        renderedMode = mode
        TransitionManager.beginDelayedTransition(binding.actions)
        val p = binding.btnPrimary; val stop = binding.btnStop
        when (mode) {
            Mode.IDLE, Mode.REACHED -> {
                p.visibility = View.VISIBLE; p.isEnabled = true; p.text = getString(R.string.find_object)
                p.icon = ContextCompat.getDrawable(this, R.drawable.ic_search); stop.visibility = View.GONE
            }
            Mode.SEARCHING -> {
                p.visibility = View.VISIBLE; p.isEnabled = false; p.text = getString(R.string.searching); p.icon = null
                stop.visibility = View.VISIBLE; stop.text = getString(R.string.cancel)
            }
            Mode.TARGET_LOCKED, Mode.TRACKING_LOST -> { // TRACKING_LOST: Start Guidance is the deliberate retry
                p.visibility = View.VISIBLE; p.isEnabled = true; p.text = getString(R.string.start_guidance)
                p.icon = ContextCompat.getDrawable(this, R.drawable.ic_navigation)
                stop.visibility = View.VISIBLE; stop.text = getString(R.string.cancel)
            }
            Mode.GUIDING -> {
                p.visibility = View.VISIBLE; p.isEnabled = true; p.text = getString(R.string.got_it); p.icon = null
                stop.visibility = View.VISIBLE; stop.text = getString(R.string.stop_guidance)
            }
        }
    }

    private fun renderSpeechButton() {
        val on = speaker.enabled
        binding.btnSpeech.icon = ContextCompat.getDrawable(this, if (on) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
        binding.btnSpeech.contentDescription = getString(if (on) R.string.speech_on_desc else R.string.speech_off_desc)
        // With our own speech off, let a screen reader announce instruction changes instead.
        binding.guidanceText.accessibilityLiveRegion =
            if (on) View.ACCESSIBILITY_LIVE_REGION_NONE else View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun updateMicHelper() {
        // The guidance card already says what to type; the helper line is only for microphone states.
        binding.inputLayout.helperText = when {
            listening -> getString(R.string.listening)
            !granted(Manifest.permission.RECORD_AUDIO) -> getString(R.string.mic_off_helper)
            else -> null
        }
        binding.inputLayout.setEndIconTintList(android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (listening) R.color.reach_primary else R.color.reach_text_secondary)))
    }

    private fun chip(v: TextView, line: Line?) {
        v.visibility = if (line == null) View.GONE else View.VISIBLE
        if (line == null) return
        setIfChanged(v, line.text)
        v.setTextColor(if (line.tone == Tone.NEUTRAL) ContextCompat.getColor(this, R.color.reach_text) else color(line.tone))
    }

    /** Breathing status dot while the system is actively working (searching / guiding). */
    private fun pulse(v: View, on: Boolean) {
        if (on && pulseAnim == null) {
            pulseAnim = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.3f).apply {
                duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        } else if (!on && pulseAnim != null) {
            pulseAnim?.cancel(); pulseAnim = null; v.alpha = 1f
        }
    }

    private fun color(t: Tone) = ContextCompat.getColor(this, when (t) {
        Tone.GOOD -> R.color.reach_good
        Tone.WARN -> R.color.reach_warn
        Tone.ACTIVE -> R.color.reach_primary
        Tone.NEUTRAL -> R.color.reach_text_secondary
    })

    /** Avoids relayouts and repeated screen-reader announcements when nothing changed. */
    private fun setIfChanged(v: TextView, text: String): Boolean {
        if (v.text.toString() == text) return false
        v.text = text
        return true
    }

    private fun hideKeyboard() {
        binding.inputText.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.inputText.windowToken, 0)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // Debug builds only: adb shell am start -f 0x20000000 -n com.reach.app/.MainActivity --es utterance "find my coke"
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val text = intent.getStringExtra("utterance")
        if (BuildConfig.DEBUG && text != null && ::controller.isInitialized) {
            binding.inputText.setText(text)
            controller.onUtterance(text)
        }
        // Debug builds only: record frames / replay recorded frames through the models (see DebugFrames).
        intent.getIntExtra("capture", 0).takeIf { it > 0 }?.let { debugFrames?.startCapture(it) }
        intent.getStringExtra("replay")?.let {
            debugFrames?.replay(it, intent.getStringExtra("model") ?: "efficientdet_lite0.tflite", intent.getIntExtra("loops", 1))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(renderLoop)
        if (::camera.isInitialized) camera.stop() // drain the analyzer before closing the native models
        if (::controller.isInitialized) controller.close()
        debugFrames?.close()
        voice.destroy()
        speaker.shutdown()
    }

    private companion object {
        const val PREF_SPEECH = "speech_enabled"
        const val PREF_VOLUME = "speech_volume"
    }
}
