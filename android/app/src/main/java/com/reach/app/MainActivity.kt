package com.reach.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.graphics.Bitmap
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.reach.app.camera.ArCoreSource
import com.reach.app.camera.CameraXSource
import com.reach.app.claude.ClaudeClient
import com.reach.app.databinding.ActivityMainBinding
import com.reach.app.databinding.SheetSettingsBinding
import com.reach.app.grounding.CropEmbedder
import com.reach.app.grounding.ObjectStore
import com.reach.app.grounding.createObjectGrounder
import com.reach.app.grounding.TrackStatus
import com.reach.app.haptic.BandSetup
import com.reach.app.haptic.BleBand
import com.reach.app.haptic.IntroState
import com.reach.app.haptic.SetupStep
import com.reach.app.hand.HandTracker
import com.reach.app.spatial.DepthFrame
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.HeadingSensor
import com.reach.app.talk.Tutorial
import com.reach.app.speech.Speaker
import com.reach.app.speech.VoiceInput
import com.reach.app.ui.CameraState
import com.reach.app.ui.Line
import com.reach.app.ui.ReachStatus
import com.reach.app.ui.Tone
import java.io.File

/**
 * The one screen, camera first. Every view is driven by [ReachSnapshot]s from the controller (real per-frame
 * state); a 10 Hz main-thread loop renders the latest one, so inference never waits on the UI and spoken
 * guidance (issued by the controller on the camera thread) never waits on rendering.
 *
 * Two camera modes feed the same controller: CameraX on a chosen lens (widest by default; 2D guidance), or an
 * ARCore session on the main camera (adds a depth map). Only one owns the camera at a time; ARCore failing for
 * any reason returns to CameraX. Switching keeps the models and controller running and resets target + hand.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraXSource
    private lateinit var controller: ReachController
    private lateinit var speaker: Speaker
    private lateinit var voice: VoiceInput
    private lateinit var heading: HeadingSensor
    private var lastCueKey: String = ""
    private val band by lazy { BleBand.get(this) }
    private val prefs by lazy { getSharedPreferences("reach", MODE_PRIVATE) }
    private val bandPrefs by lazy { BandSetupActivity.prefs(this) }
    private val bandListener = { onBandChanged() }
    private var bandWasLive = false
    private var warnedUntested: String? = null
    private var bandStatusView: TextView? = null // the settings sheet's band line while it is open

    @Volatile private var snapshot: ReachSnapshot? = null
    private var arcore: ArCoreSource? = null
    private var depthFailed: String? = null // why depth mode could not run this session
    private var diagView: TextView? = null  // the settings sheet's diagnostics while it is open
    private val arFrame = java.util.concurrent.atomic.AtomicReference<Bitmap?>()
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
            if (granted(Manifest.permission.RECORD_AUDIO)) startWakeWordListening()
            if (BleBand.hasPermission(this)) band.connect()
        }

    /** The introduction / band setup (every launch, or settings): back here, the camera and microphone are asked for. */
    private val setupLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (!::controller.isInitialized && !isFinishing) requestStartupPermissions()
        }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            updateMicHelper()
            if (ok) {
                startWakeWordListening()
                listen()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Edge to edge: the camera runs behind the system bars; only the overlays are inset from them.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val density = resources.displayMetrics.density
        val vGap = (10 * density).toInt()
        val hPad = (14 * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.topBar.updatePadding(top = bars.top + vGap, left = bars.left + hPad, right = bars.right + hPad)
            binding.bottomBar.updatePadding(bottom = maxOf(bars.bottom, ime.bottom) + vGap, left = bars.left + hPad, right = bars.right + hPad)
            insets
        }
        // Chest-mounted and used by voice: a screen timeout would stop the camera.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speaker = Speaker(this).apply {
            enabled = prefs.getBoolean(PREF_SPEECH, true)
            volume = prefs.getFloat(PREF_VOLUME, 1f)
            onSpoken = { lastSpoken = it } // any thread; rendered by the UI loop
        }
        voice = VoiceInput(this)
        heading = HeadingSensor(this) { deg, ts -> if (::controller.isInitialized) controller.onHeading(deg, ts) }

        binding.btnPrimary.setOnClickListener { onPrimary() }
        binding.btnStop.setOnClickListener { if (::controller.isInitialized) controller.reset() }
        binding.btnQuickBottle.setOnClickListener { quickFind("bottle") }
        binding.btnQuickLaptop.setOnClickListener { quickFind("laptop") }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.inputLayout.setEndIconOnClickListener { listen() }
        binding.inputText.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) { findObject(); true } else false
        }
        binding.btnHand.setOnClickListener {
            if (::controller.isInitialized) controller.beginHandSetup() else speaker.say(getString(R.string.spoken_no_camera))
        }
        binding.btnSpeech.setOnClickListener { setSpeech(!speaker.enabled, announce = true) }
        binding.btnHelp.setOnClickListener { help() }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnCameraAction.setOnClickListener { requestCamera() }
        renderSpeechButton()

        // Every launch starts at the introduction, then the band setup, then the camera. Only a screen recreated within a
        // live process (introduction done or still open) skips it; a stored preference never does.
        if (BandSetup.showIntro(BandSetupActivity.intro, restored = savedInstanceState != null))
            setupLauncher.launch(BandSetupActivity.intent(this, SetupStep.WELCOME, startup = true))
        else if (BandSetupActivity.intro != IntroState.OPEN) requestStartupPermissions()
    }

    private fun requestStartupPermissions() {
        val bandPerms = BleBand.permissions.toList()
        permLauncher.launch((listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) + bandPerms).toTypedArray())
    }

    private fun startWakeWordListening() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) return
        voice.startWakeWord(
            speakerBusy = { speaker.isSpeakingNow },
            guidingActive = { ::controller.isInitialized && (controller.mode == Mode.GUIDING || controller.mode == Mode.TRACKING_LOST) },
            onActivated = { runOnUiThread { if (!listening) listen() } },
            onCommand = { cmd ->
                runOnUiThread {
                    if (!isCommand(cmd)) binding.inputText.setText(cmd)
                    submit(cmd)
                }
            }
        )
    }

    /** Main thread, on every band state change: live haptics enabled whenever the REACH BLE band is connected. */
    private fun onBandChanged() {
        val addr = band.address.takeIf { band.ready }
        if (addr != null && !BandSetup.liveAllowed(bandPrefs, addr)) {
            // Ensure BLE vibration motors work immediately when an ESP32 REACH band connects, even if setup was skipped earlier
            bandPrefs.put(BandSetup.KEY_STATE, "done")
            bandPrefs.put(BandSetup.KEY_ADDRESS, addr)
            bandPrefs.put(BandSetup.KEY_LANGUAGE, com.reach.app.haptic.HAPTIC_LANGUAGE_VERSION)
        }
        band.liveEnabled = addr != null && BandSetup.liveAllowed(bandPrefs, addr)
        val live = band.connected
        if (live != bandWasLive) speaker.say(getString(if (live) R.string.spoken_band_on else R.string.spoken_band_lost), interrupt = false)
        bandWasLive = live
        bandStatusView?.let { setIfChanged(it, bandLine()) }
    }

    private fun bandOff() = BandSetup.offReason(bandPrefs, band.state, band.address)

    private fun bandLine() = "Band: ${band.state.words}. Haptic guidance: ${bandOff()?.let { "off ($it)" } ?: "on"}."

    override fun onStart() {
        super.onStart()
        // The camera restarts with the lifecycle: show "Starting camera" until frames flow again.
        lastFrameAt = 0L; pipelineStartedAt = SystemClock.uptimeMillis()
        main.post(renderLoop)
        band.onChange = bandListener
        onBandChanged()
    }

    override fun onResume() {
        super.onResume()
        // Back from system settings with the camera now allowed.
        if (blocker == Blocker.CAMERA_PERMISSION && granted(Manifest.permission.CAMERA)) startPipeline()
        arcore?.resume()
        heading.start()
        updateMicHelper()
        if (granted(Manifest.permission.RECORD_AUDIO)) startWakeWordListening()
    }

    override fun onPause() {
        super.onPause()
        voice.stopWakeWord()
        arcore?.pause() // CameraX follows the lifecycle by itself; ARCore must release the camera explicitly
        heading.stop()
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(renderLoop)
        pulse(binding.statusDot, false)
        if (band.onChange === bandListener) band.onChange = null
        band.stop() // no frames while stopped: nothing may keep vibrating from a state that is no longer checked
    }

    private fun startPipeline() {
        if (::controller.isInitialized) return
        val grounder = try { createObjectGrounder(this) } catch (e: Exception) { return modelsFailed(e) }
        val handTracker = try { HandTracker(this) } catch (e: Exception) { grounder.close(); return modelsFailed(e) }
        blocker = null

        controller = ReachController(
            grounder = grounder,
            handTracker = handTracker,
            claude = ClaudeClient(),
            speaker = speaker,
            haptics = band,
            scope = lifecycleScope,
            embedder = CropEmbedder.create(this),
            store = ObjectStore(File(filesDir, "objects"))
        ) { s ->
            snapshot = s
            binding.overlay.candidates = s.candidates
            binding.overlay.targetId = s.targetId
            binding.overlay.hiddenTarget = s.targetBox.takeIf { s.targetId >= 0 && s.track != TrackStatus.TRACKING }
            binding.overlay.hands = listOf(s.left, s.right)
            binding.overlay.guided = s.guided
            binding.overlay.others = s.otherHands
            binding.overlay.guiding = s.mode == Mode.GUIDING
            binding.overlay.distance = ReachStatus.gapLabel(s)
            if (s.frameW > 0) { binding.overlay.frameW = s.frameW; binding.overlay.frameH = s.frameH }
            binding.overlay.postInvalidate()
        }

        controller.chestMounted = prefs.getBoolean(PREF_CHEST, true)
        camera = CameraXSource(this, this, binding.preview) { bmp, _ -> deliver(bmp, null) }
        pipelineStartedAt = SystemClock.uptimeMillis()
        val saved = prefs.getString(PREF_CAMERA, null)
        camera.start(saved.takeIf { it != CAMERA_DEPTH }) {
            updateFov()
            if (saved == CAMERA_DEPTH) selectCamera(CAMERA_DEPTH, announce = false)
        }
        // Hands auto-enrol on first sight during guidance; announce ready immediately.
        speaker.say(getString(R.string.spoken_ready))
    }

    /** The help button / settings item: replays the introduction, describing the real current state. */
    private fun help() {
        if (::controller.isInitialized) controller.help()
        else speaker.say(Tutorial.text(band.connected, depthMode = false, bothHandsEnrolled = false))
    }

    /** The bound CameraX lens's measured field of view, for the target's bearing (ARCore frames carry their own). */
    private fun updateFov() {
        camera.cameras.firstOrNull { it.id == camera.boundId }?.let { controller.fovDeg = it.fovDeg }
    }

    /** Any camera thread. MediaPipe VIDEO mode wants monotonically increasing ms timestamps. */
    private fun deliver(bmp: Bitmap, depth: DepthFrame?) {
        val now = SystemClock.uptimeMillis()
        lastFrameAt = now
        debugFrames?.onFrame(bmp, now)
        controller.onFrame(bmp, now, depth)
    }

    /** Main thread. [choice]: a CameraX camera id, or [CAMERA_DEPTH] for ARCore. */
    private fun selectCamera(choice: String, announce: Boolean = true) {
        if (choice == CAMERA_DEPTH) {
            if (arcore != null) return
            camera.release() // ARCore needs the camera itself
            binding.preview.visibility = View.GONE
            binding.arPreview.visibility = View.VISIBLE
            val src = ArCoreSource(this, binding.glHost,
                onPreview = { bmp -> if (arFrame.getAndSet(bmp) == null) main.post(showArFrame) },
                onFrame = { bmp, depth -> deliver(bmp, depth) },
                onFailure = { reason -> depthModeFailed(reason) })
            arcore = src
            if (!src.start()) return // onFailure already switched back
        } else {
            stopDepthMode()
            camera.switchTo(choice)
            updateFov()
        }
        prefs.edit().putString(PREF_CAMERA, choice).apply()
        lastFrameAt = 0L; pipelineStartedAt = SystemClock.uptimeMillis()
        controller.depthMode = choice == CAMERA_DEPTH
        controller.onCameraChanged(if (announce) getString(R.string.spoken_camera_changed) else "")
    }

    private val showArFrame = Runnable { arFrame.getAndSet(null)?.let { binding.arPreview.setImageBitmap(it) } }

    private fun stopDepthMode() {
        arcore?.stop(); arcore = null
        main.removeCallbacks(showArFrame); arFrame.set(null)
        binding.arPreview.setImageDrawable(null)
        binding.arPreview.visibility = View.GONE
        binding.preview.visibility = View.VISIBLE
    }

    /** Main thread, from ArCoreSource: back to the widest CameraX lens, and say why. */
    private fun depthModeFailed(reason: String) {
        depthFailed = reason
        stopDepthMode()
        val widest = camera.cameras.firstOrNull()?.id
        if (widest != null) camera.switchTo(widest)
        updateFov()
        prefs.edit().remove(PREF_CAMERA).apply()
        lastFrameAt = 0L; pipelineStartedAt = SystemClock.uptimeMillis()
        controller.depthMode = false
        controller.onCameraChanged(getString(R.string.spoken_depth_failed, reason))
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

    private fun quickFind(target: String) {
        val cmd = "find my $target"
        binding.inputText.setText(cmd)
        submit(cmd)
    }

    private fun toggleTorch(forceState: Boolean? = null) {
        if (!::camera.isInitialized) {
            speaker.say(getString(R.string.spoken_no_camera))
            return
        }
        val targetState = forceState ?: !camera.torchOn
        if (camera.setTorch(targetState)) {
            val on = camera.torchOn
            binding.btnTorch.text = getString(if (on) R.string.quick_torch_on else R.string.quick_torch)
            binding.btnTorch.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.reach_warn_container else R.color.reach_surface_glass)
            )
            binding.btnTorch.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.reach_warn else R.color.reach_outline)
            )
            binding.btnTorch.iconTint = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.reach_warn else R.color.reach_primary)
            )
            binding.btnTorch.setTextColor(
                ContextCompat.getColor(this, if (on) R.color.reach_warn else R.color.reach_text)
            )
            speaker.say(if (on) "Flashlight on." else "Flashlight off.")
        } else {
            speaker.say("Flashlight isn't available on this camera lens.")
        }
    }

    private fun submit(text: String) {
        hideKeyboard()
        val norm = text.lowercase().trim()
        if (norm in setOf("light on", "turn on light", "torch on", "flashlight on")) {
            toggleTorch(true); return
        }
        if (norm in setOf("light off", "turn off light", "torch off", "flashlight off")) {
            toggleTorch(false); return
        }
        if (norm in setOf("torch", "flashlight")) {
            toggleTorch(null); return
        }
        if (!::controller.isInitialized) { speaker.say(getString(R.string.spoken_no_camera)); return }
        controller.onUtterance(text)
    }

    private fun listen() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
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
        b.volumeValue.text = "${(b.sliderVolume.value * 100).toInt()}%"
        b.sliderVolume.setLabelFormatter { "${(it * 100).toInt()}%" }
        b.sliderVolume.addOnChangeListener { _, v, _ ->
            speaker.volume = v
            b.volumeValue.text = "${(v * 100).toInt()}%"
            prefs.edit().putFloat(PREF_VOLUME, v).apply()
        }
        b.btnTestVoice.setOnClickListener { speaker.say(getString(R.string.spoken_test)) }
        b.btnTutorial.setOnClickListener { sheet.dismiss(); help() }
        b.btnHands.setOnClickListener {
            sheet.dismiss()
            if (::controller.isInitialized) controller.beginHandSetup() else speaker.say(getString(R.string.spoken_no_camera))
        }
        b.switchChest.isChecked = prefs.getBoolean(PREF_CHEST, true)
        b.switchChest.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(PREF_CHEST, on).apply()
            if (::controller.isInitialized) controller.chestMounted = on
        }
        b.btnDepth.setOnClickListener { sheet.dismiss(); startActivity(Intent(this, DepthCheckActivity::class.java)) }
        b.bandStatus.text = bandLine()
        b.btnBandConnect.setOnClickListener {
            if (BleBand.hasPermission(this)) band.connect(force = true) else permLauncher.launch(BleBand.permissions)
        }
        b.btnBandTest.setOnClickListener { sheet.dismiss(); setupLauncher.launch(BandSetupActivity.intent(this, SetupStep.LEFT)) }
        b.btnBandReview.setOnClickListener { sheet.dismiss(); setupLauncher.launch(BandSetupActivity.intent(this, SetupStep.LEARN)) }
        b.btnBandStop.setOnClickListener { band.stop() }
        bandStatusView = b.bandStatus
        fillCameraChoices(b.cameraGroup)
        diagView = b.diagnostics
        sheet.setOnDismissListener { diagView = null; bandStatusView = null }
        sheet.setContentView(b.root)
        sheet.show()
    }

    /** Real cameras only: what CameraX reports for this phone, plus depth mode unless it already failed here. */
    private fun fillCameraChoices(group: android.widget.RadioGroup) {
        if (!::camera.isInitialized) return
        val current = if (arcore != null) CAMERA_DEPTH else camera.boundId
        val choices = camera.cameras.mapIndexed { i, c ->
            c.id to getString(R.string.camera_option, c.id, c.fovDeg.toInt(), if (i == 0 && camera.cameras.size > 1) getString(R.string.camera_widest) else "")
        } + (CAMERA_DEPTH to (depthFailed?.let { getString(R.string.camera_depth_failed, it) } ?: getString(R.string.camera_depth)))
        for ((id, label) in choices) {
            group.addView(RadioButton(this).apply {
                text = label; textSize = 17f; minHeight = (48 * resources.displayMetrics.density).toInt()
                isChecked = id == current
                isEnabled = !(id == CAMERA_DEPTH && depthFailed != null)
                setOnClickListener { if (id != current) selectCamera(id) }
            })
        }
    }

    private fun diagnostics(): String {
        val s = snapshot
        val cam = when {
            arcore != null -> "ARCore (main camera) ${arcore?.status}"
            else -> camera.cameras.firstOrNull { it.id == camera.boundId }?.let { "CameraX id=${it.id} f=${it.focalMm}mm ${it.fovDeg.toInt()}° wide" }
                ?: "CameraX (not bound)"
        }
        fun hv(v: HandView?) = v?.let { "${it.state} track#${it.trackId} conf=%.2f label=${it.label ?: "?"}".format(it.score) +
            (if (it.missingMs > 0) " missing=${it.missingMs}ms" else "") + (if (it.reason.isNotEmpty()) " (${it.reason})" else "") } ?: "-"
        return "camera: $cam\n" +
            "frame: ${s?.frameW}x${s?.frameH}  ${controller.stats}\n" +
            "mode: ${s?.mode}  target: ${s?.target}#${s?.targetId} ${s?.track}" +
            (s?.identity?.let { " · saved object $it ${if (s.identityVerified) "photo-matched" else "checking"}" } ?: "") + "\n" +
            "saved objects: " + controller.memory.objects.joinToString { "${it.name} (${it.label}, ${it.refs.size} photo${if (it.refs.size > 1) "s" else ""})" }
                .ifEmpty { "none" } + (if (controller.photoMatching) "" else " · photo matching unavailable (model missing)") + "\n" +
            "USER_LEFT_HAND: ${hv(s?.left)}\nUSER_RIGHT_HAND: ${hv(s?.right)}\n" +
            "guided: ${s?.guided}  enrolling: ${s?.enrolling} ${s?.enrollStatus}  other hands: ${s?.otherHands?.size}\n" +
            "heading: ${heading.description}  turn=${s?.turn} toTarget=${s?.turnDeg?.let { "%+.0f°".format(it) }} " +
            "chest=${controller.chestMounted}\n" +
            "band cue: ${s?.band} · ${bandOff()?.let { "live off ($it)" } ?: "live on"} · ${band.status}\n" +
            "closer hand: ${s?.haptic}  dL=${s?.leftM} dR=${s?.rightM}\n" +
            "depth: " + (s?.depth?.let { "${it.reason} targetZ=${it.targetM} tipZ(est)=${it.tipM} arTipZ=${it.arTipM}" }
                ?: if (s?.depthSource == true) "on (measured only while guiding)" else "off (2D)") +
            (depthFailed?.let { "\ndepth mode failed: $it" } ?: "")
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

    private var statusPendingText = ""
    private var statusPendingSince = 0L
    private var lastPhoneCue: com.reach.app.haptic.HapticCue? = null
    private var lastPhonePulseAt = 0L

    private fun render() {
        val cam = cameraState()
        val s = snapshot.takeIf { cam == CameraState.LIVE } // a stale snapshot never drives the screen
        val now = SystemClock.uptimeMillis()

        val rawStatus = ReachStatus.status(s, cam)
        val status = if (rawStatus.text == binding.statusText.text.toString() || rawStatus.text == "Reached" || rawStatus.text == "Searching") {
            statusPendingText = rawStatus.text; statusPendingSince = now
            rawStatus
        } else {
            if (rawStatus.text != statusPendingText) { statusPendingText = rawStatus.text; statusPendingSince = now }
            if (now - statusPendingSince >= 450L) rawStatus else Line(binding.statusText.text.toString().ifEmpty { rawStatus.text }, rawStatus.tone)
        }
        setIfChanged(binding.statusText, status.text)
        binding.statusPill.contentDescription = "Status: ${status.text}"
        binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color(status.tone))
        pulse(binding.statusDot, status.tone == Tone.ACTIVE)
        chip(binding.chipObject, ReachStatus.objects(s))
        chip(binding.chipHand, ReachStatus.hand(s))
        chip(binding.chipDistance, ReachStatus.distance(s))
        chip(binding.chipTurn, ReachStatus.turn(s))
        chip(binding.chipHaptic, ReachStatus.haptics(s, bandOff()).takeIf { s != null })
        if (!band.connected) pulsePhoneFallback(s?.band, now)
        if (::controller.isInitialized) diagView?.let { setIfChanged(it, diagnostics()) }

        val g = ReachStatus.guidance(s, cam)
        if (setIfChanged(binding.guidanceText, g.headline)) {
            binding.guidanceText.alpha = 0.35f
            binding.guidanceText.animate().alpha(1f).setDuration(180).start()
        }
        setIfChanged(binding.guidanceDetail, g.detail)
        val guiding = s?.mode == Mode.GUIDING
        binding.guidanceMode.visibility = if (guiding) View.VISIBLE else View.GONE
        setIfChanged(binding.guidanceMode, getString(if (g.directionOnly) R.string.direction_only else R.string.direction_distance))
        binding.voiceWakePill.visibility = if (guiding) View.GONE else View.VISIBLE
        binding.alignBar.visibility = if (guiding) View.VISIBLE else View.GONE
        if (guiding) {
            val pct = binding.overlay.alignmentPercent
            binding.alignBar.setProgressCompat(pct, true)
            binding.alignBar.setIndicatorColor(
                ContextCompat.getColor(this, if (pct >= 75) R.color.reach_good else R.color.reach_primary)
            )
        }
        updateCueBadge(s?.mode, s?.cue)
        val activeTarget = s?.target?.lowercase().orEmpty()
        val targetActive = s != null && s.mode != Mode.IDLE && s.mode != Mode.REACHED
        binding.btnQuickBottle.strokeColor = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (targetActive && activeTarget == "bottle") R.color.reach_primary else R.color.reach_outline)
        )
        binding.btnQuickLaptop.strokeColor = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (targetActive && activeTarget == "laptop") R.color.reach_primary else R.color.reach_outline)
        )
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
        TransitionManager.beginDelayedTransition(binding.bottomBar)
        binding.inputLayout.visibility = if (mode == Mode.GUIDING || mode == Mode.SEARCHING) View.GONE else View.VISIBLE
        val p = binding.btnPrimary; val stop = binding.btnStop
        fun stylePrimary(bgRes: Int, fgRes: Int) {
            p.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgRes))
            val fg = ContextCompat.getColor(this, fgRes)
            p.setTextColor(fg)
            p.iconTint = ColorStateList.valueOf(fg)
        }
        when (mode) {
            Mode.IDLE, Mode.REACHED -> {
                stylePrimary(R.color.reach_primary, R.color.reach_on_primary)
                p.visibility = View.VISIBLE; p.isEnabled = true; p.text = getString(R.string.find_object)
                p.icon = ContextCompat.getDrawable(this, R.drawable.ic_search); stop.visibility = View.GONE
            }
            Mode.SEARCHING -> {
                stylePrimary(R.color.reach_primary, R.color.reach_on_primary)
                p.visibility = View.VISIBLE; p.isEnabled = false; p.text = getString(R.string.searching); p.icon = null
                stop.visibility = View.VISIBLE; stop.text = getString(R.string.cancel)
            }
            Mode.TARGET_LOCKED, Mode.TRACKING_LOST -> { // TRACKING_LOST: Start Guidance is the deliberate retry
                stylePrimary(R.color.reach_primary, R.color.reach_on_primary)
                p.visibility = View.VISIBLE; p.isEnabled = true; p.text = getString(R.string.start_guidance)
                p.icon = ContextCompat.getDrawable(this, R.drawable.ic_navigation)
                stop.visibility = View.VISIBLE; stop.text = getString(R.string.cancel)
            }
            Mode.GUIDING -> {
                stylePrimary(R.color.reach_good, R.color.reach_on_good)
                p.visibility = View.VISIBLE; p.isEnabled = true; p.text = getString(R.string.got_it)
                p.icon = ContextCompat.getDrawable(this, R.drawable.ic_check)
                stop.visibility = View.VISIBLE; stop.text = getString(R.string.stop_guidance)
            }
        }
        val cardBg = binding.guidanceCard.background.mutate() as? android.graphics.drawable.GradientDrawable
        val cardStrokeColor = when (mode) {
            Mode.GUIDING, Mode.REACHED -> R.color.reach_good
            Mode.TRACKING_LOST -> R.color.reach_warn
            else -> R.color.reach_outline
        }
        cardBg?.setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), ContextCompat.getColor(this, cardStrokeColor))
    }

    private fun updateCueBadge(mode: Mode?, cue: Guidance?) {
        val key = "${mode?.name}_${cue?.name}"
        if (key == lastCueKey) return
        lastCueKey = key
        val (iconRes, rot, tintRes, badgeRes) = when (cue) {
            Guidance.RIGHT -> arrayOf(R.drawable.ic_arrow_forward, 0f, R.color.reach_primary, R.color.reach_primary_container)
            Guidance.LEFT -> arrayOf(R.drawable.ic_arrow_forward, 180f, R.color.reach_primary, R.color.reach_primary_container)
            Guidance.UP -> arrayOf(R.drawable.ic_arrow_forward, -90f, R.color.reach_primary, R.color.reach_primary_container)
            Guidance.DOWN -> arrayOf(R.drawable.ic_arrow_forward, 90f, R.color.reach_primary, R.color.reach_primary_container)
            Guidance.CLOSER, Guidance.BACK -> arrayOf(R.drawable.ic_center_target, 0f, R.color.reach_primary, R.color.reach_primary_container)
            Guidance.NEAR, Guidance.LINED_UP_2D, Guidance.AT_TARGET, Guidance.HAND_COVERS_TARGET ->
                arrayOf(R.drawable.ic_center_target, 0f, R.color.reach_good, R.color.reach_good_container)
            Guidance.REACHED -> arrayOf(R.drawable.ic_check, 0f, R.color.reach_good, R.color.reach_good_container)
            Guidance.STOP, Guidance.TURNED_AWAY, Guidance.TARGET_LOST, Guidance.HAND_LOST,
            Guidance.HAND_UNCERTAIN, Guidance.IDENTITY_CHECK ->
                arrayOf(R.drawable.ic_help, 0f, R.color.reach_warn, R.color.reach_warn_container)
            Guidance.HAND_SHOW, Guidance.HAND_FIST, Guidance.HAND_TOO_FAR,
            Guidance.HAND_AMBIGUOUS, Guidance.HAND_SAME ->
                arrayOf(R.drawable.ic_hand, 0f, R.color.reach_warn, R.color.reach_warn_container)
            else -> when (mode) {
                Mode.SEARCHING -> arrayOf(R.drawable.ic_search, 0f, R.color.reach_primary, R.color.reach_primary_container)
                Mode.TARGET_LOCKED -> arrayOf(R.drawable.ic_center_target, 0f, R.color.reach_good, R.color.reach_good_container)
                Mode.REACHED -> arrayOf(R.drawable.ic_check, 0f, R.color.reach_good, R.color.reach_good_container)
                Mode.TRACKING_LOST -> arrayOf(R.drawable.ic_help, 0f, R.color.reach_warn, R.color.reach_warn_container)
                else -> arrayOf(R.drawable.ic_spark, 0f, R.color.reach_primary, R.color.reach_primary_container)
            }
        }
        val tintColor = ContextCompat.getColor(this, tintRes as Int)
        val badgeColor = ContextCompat.getColor(this, badgeRes as Int)
        binding.cueIcon.setImageResource(iconRes as Int)
        binding.cueIcon.imageTintList = ColorStateList.valueOf(tintColor)
        binding.cueIcon.animate().rotation(rot as Float).setDuration(140).start()
        val bg = binding.cueBadge.background.mutate() as? android.graphics.drawable.GradientDrawable
        bg?.setColor(badgeColor)
        bg?.setStroke((1.5f * resources.displayMetrics.density).toInt(), tintColor)
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

    private fun pulsePhoneFallback(cue: com.reach.app.haptic.HapticCue?, now: Long) {
        if (cue == null) { lastPhoneCue = null; return }
        val changed = cue != lastPhoneCue
        if (!changed && (cue.oneShot || now - lastPhonePulseAt < cue.repeatMs.coerceAtLeast(900L))) return
        lastPhoneCue = cue; lastPhonePulseAt = now
        runCatching {
            @Suppress("DEPRECATION")
            val vib = getSystemService(android.os.Vibrator::class.java) ?: return
            if (!vib.hasVibrator()) return
            val ms = when (cue) {
                com.reach.app.haptic.HapticCue.REACHED -> 220L
                com.reach.app.haptic.HapticCue.NEAR_CLOSE, com.reach.app.haptic.HapticCue.NEAR_VERY_CLOSE -> 45L
                else -> 65L
            }
            vib.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
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
            debugFrames?.replay(it, intent.getStringExtra("model") ?: "efficientdet_lite2_int8.tflite", intent.getIntExtra("loops", 1))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(renderLoop)
        arcore?.stop()
        if (::camera.isInitialized) camera.stop() // drain the analyzer before closing the native models
        if (::controller.isInitialized) controller.close()
        if (isFinishing) band.disconnect() // leaving REACH: stop the motors and free the band
        debugFrames?.close()
        voice.destroy()
        speaker.shutdown()
    }

    private companion object {
        const val PREF_SPEECH = "speech_enabled"
        const val PREF_VOLUME = "speech_volume"
        const val PREF_CAMERA = "camera"
        const val CAMERA_DEPTH = "arcore"
        const val PREF_CHEST = "chest_mounted"
    }
}
