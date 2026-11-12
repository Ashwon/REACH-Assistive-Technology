package com.reach.app

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.reach.app.databinding.ActivityBandSetupBinding
import com.reach.app.haptic.BandSetup
import com.reach.app.haptic.BandState
import com.reach.app.haptic.BleBand
import com.reach.app.haptic.Felt
import com.reach.app.haptic.HapticCue
import com.reach.app.haptic.IntroState
import com.reach.app.haptic.Pulse
import com.reach.app.haptic.SetupStep
import com.reach.app.grounding.SupportedClasses
import com.reach.app.speech.Speaker

/**
 * The startup introduction and vibration band setup: REACH welcome, the band and its language, connect (or voice only),
 * test each motor, practise the haptic language, confirm. Every launch of REACH opens it at WELCOME ([startup] mode);
 * Settings opens single parts of it (motor self-test, review).
 * It never starts the camera or the tracking engine: live guidance is switched off for the band while this screen is
 * open, and every vibration here is a practice pulse sent straight to the band. Every step is spoken and on screen.
 */
class BandSetupActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_STEP = "step"
        private const val EXTRA_STARTUP = "startup"
        private const val STATE = "setup"
        /** The startup introduction in this process. In memory only: a new process starts at NOT_STARTED. */
        @Volatile var intro = IntroState.NOT_STARTED
        fun intent(c: Context, step: SetupStep, startup: Boolean = false) =
            Intent(c, BandSetupActivity::class.java).putExtra(EXTRA_STEP, step.name).putExtra(EXTRA_STARTUP, startup)

        /** BandSetup's persistence: the app's existing "reach" SharedPreferences. */
        fun prefs(c: Context): BandSetup.Prefs {
            val sp = c.getSharedPreferences("reach", MODE_PRIVATE)
            return object : BandSetup.Prefs {
                override fun get(key: String) = sp.getString(key, null)
                override fun put(key: String, value: String?) {
                    sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
                }
            }
        }
    }

    private lateinit var b: ActivityBandSetupBinding
    private lateinit var setup: BandSetup
    private lateinit var band: BleBand
    private lateinit var speaker: Speaker
    private var review = false // opened from settings only to review the patterns of an already set-up band
    private var startup = false // the every-launch introduction (not a settings shortcut)
    private lateinit var start: SetupStep
    private var spoken: SetupStep? = null
    private var heardState: BandState? = null // the link state last spoken, so each change is said once
    private val main = Handler(Looper.getMainLooper())
    private val listener = { render() }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { band.connect(force = true) }
    private val enableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { band.connect(force = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBandSetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        val prefs = prefs(this)
        start = intent.getStringExtra(EXTRA_STEP)?.let { SetupStep.valueOf(it) } ?: SetupStep.WELCOME
        startup = intent.getBooleanExtra(EXTRA_STARTUP, false)
        if (startup) intro = IntroState.OPEN
        review = start == SetupStep.LEARN && BandSetup.startStep(prefs) == null
        setup = BandSetup(prefs, start)
        savedInstanceState?.getString(STATE)?.let(setup::restore) // recreated: same step, same answers
        // Back goes one step back; on the first step it leaves REACH (startup) or returns to the camera (settings).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = when {
                setup.step != start -> go { setup.back() }
                startup -> { intro = IntroState.NOT_STARTED; finishAffinity() }
                else -> finish()
            }
        })
        band = BleBand.get(this)
        band.liveEnabled = false // live guidance never drives the band while it is being set up
        val sp = getSharedPreferences("reach", MODE_PRIVATE)
        speaker = Speaker(this).apply { enabled = sp.getBoolean("speech_enabled", true); volume = sp.getFloat("speech_volume", 1f) }
        b.btnStopVibration.setOnClickListener { band.stop(); say("Vibration stopped.") }
        if (start.ordinal > SetupStep.CONNECT.ordinal && !band.ready) band.connect()
        render()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString(STATE, setup.save()) }

    /** The user chose how to continue (haptics on, or voice only): the startup introduction is done for this process. */
    private fun done() {
        if (startup) intro = IntroState.DONE
        setResult(RESULT_OK)
        finish()
    }

    override fun onStart() { super.onStart(); band.onChange = listener; render() }

    override fun onStop() {
        super.onStop()
        if (band.onChange === listener) band.onChange = null
        main.removeCallbacksAndMessages(null)
        band.stop()
    }

    override fun onDestroy() { super.onDestroy(); speaker.shutdown() }

    private fun say(line: String, interrupt: Boolean = true) = speaker.say(line, interrupt)

    /** Runs a step action; a pulse still pending for the step being left is dropped, never played on the next one. */
    private fun go(action: () -> Unit) {
        val before = setup.step
        action()
        if (setup.step != before) main.removeCallbacksAndMessages(null)
        render()
    }

    private fun render() {
        val step = setup.step
        val ready = band.ready
        b.stepLabel.text = getString(R.string.band_step, step.ordinal + 1, SetupStep.values().size)
        b.progress.setProgressCompat((step.ordinal + 1) * 100 / SetupStep.values().size, true)
        b.title.text = title(step)
        b.body.text = body(step, ready)
        b.bandStatus.text = getString(R.string.band_status, band.state.words)
        b.bandStatus.setTextColor(ContextCompat.getColor(this, when (band.state) {
            BandState.READY -> R.color.reach_good
            BandState.FAILED, BandState.BLUETOOTH_OFF, BandState.NEEDS_PERMISSION, BandState.NO_BLUETOOTH -> R.color.reach_warn
            else -> R.color.reach_text
        }))
        b.practice.visibility = if (step == SetupStep.LEFT || step == SetupStep.RIGHT || step == SetupStep.LEARN) View.VISIBLE else View.GONE
        renderPatterns(step == SetupStep.LEARN, ready)
        val buttons = listOf(b.btn1, b.btn2, b.btn3, b.btn4, b.btn5)
        buttons.forEach { it.visibility = View.GONE }
        var i = 0
        fun button(text: String, enabled: Boolean = true, action: () -> Unit) {
            val v = buttons[i++]
            v.visibility = View.VISIBLE; v.text = text; v.isEnabled = enabled
            v.setOnClickListener { action() }
        }
        fun connect() = connectButton()?.let { (text, action) -> button(text, action != null) { action?.invoke() } }
        when (step) {
            SetupStep.WELCOME -> button(getString(R.string.band_welcome_next)) { go { setup.next(ready) } }
            SetupStep.EXPLAIN -> {
                button(getString(R.string.band_setup_start)) { go { setup.next(ready) } }
                button(getString(R.string.band_skip)) { skip() }
            }
            SetupStep.CONNECT -> {
                if (ready) button(getString(R.string.band_continue_test)) { go { setup.next(ready) } } else connect()
                button(getString(R.string.band_skip)) { skip() }
            }
            SetupStep.LEFT, SetupStep.RIGHT -> if (!ready) { connect(); button(getString(R.string.band_skip)) { skip() } } else {
                val m = setup.motor!!
                val side = if (m == 'L') "left" else "right"; val other = if (m == 'L') "right" else "left"
                val answer = setup.felt[m]
                button(getString(R.string.band_felt, side)) { go { setup.answer(m, Felt.FELT); setup.next(band.ready) } }
                button(getString(R.string.band_play_again)) { testPulse(m) }
                button(getString(R.string.band_not_felt)) { go { setup.answer(m, Felt.NOT_FELT) }; say(body(setup.step, true)) }
                button(getString(R.string.band_other_side, other)) { go { setup.answer(m, Felt.OTHER_SIDE) }; say(body(setup.step, true)) }
                if (answer != null && answer != Felt.FELT) button(getString(R.string.band_continue_anyway)) { go { setup.next(band.ready) } }
                if (spoken != step) { // "This is the left motor.", the pulse, then the question
                    spoken = step
                    say("${title(step)}. This is the $side motor.")
                    main.postDelayed({ testPulse(m); say("Did you feel it on your $side side?", interrupt = false) }, 2500)
                }
            }
            SetupStep.LEARN -> {
                if (!ready) connect()
                if (review) button(getString(R.string.band_done_review)) { done() }
                else button(getString(R.string.band_learned)) { go { setup.next(ready) } }
            }
            SetupStep.CONFIRM -> {
                if (!ready) connect()
                button(getString(R.string.band_continue_reach), setup.canContinue(ready)) {
                    if (setup.complete(band.address)) { say("Haptic guidance is on."); done() }
                }
                button(getString(R.string.band_test_again)) { go { setup.testAgain() } }
                button(getString(R.string.band_skip)) { skip() }
            }
        }
        if (spoken != step) { spoken = step; say(title(step) + ". " + body(step, ready)) }
        else if (heardState != null && heardState != band.state) say(getString(R.string.band_status, band.state.words) + ". " + body(step, ready))
        heardState = band.state
    }

    /** The connect button for the link's state: its label, and its action (null: wait). */
    private fun connectButton(): Pair<String, (() -> Unit)?>? = when (band.state) {
        BandState.SCANNING, BandState.FOUND, BandState.CONNECTING -> getString(R.string.band_connecting) to null
        BandState.NEEDS_PERMISSION -> getString(R.string.band_allow) to { permLauncher.launch(BleBand.permissions) }
        BandState.BLUETOOTH_OFF -> getString(R.string.band_turn_on) to {
            enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        BandState.NO_BLUETOOTH -> getString(R.string.band_unsupported) to null
        BandState.FAILED -> getString(R.string.band_retry) to { band.connect(force = true) }
        BandState.DISCONNECTED -> getString(R.string.band_connect) to {
            if (BleBand.hasPermission(this)) band.connect(force = true) else permLauncher.launch(BleBand.permissions)
        }
        BandState.READY -> null
    }

    private fun testPulse(m: Char) {
        setup.played(m)
        band.practice(listOf(Pulse.test(m)))
    }

    private fun skip() {
        setup.skip()
        band.disconnect()
        say("Voice only. REACH will guide you by voice. You can set up the band later in settings.")
        done()
    }

    private var patternsShown = false

    /** One entry per taught pattern: what it means, and a practice button. Built once. */
    private fun renderPatterns(show: Boolean, ready: Boolean) {
        b.patterns.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !patternsShown) {
            patternsShown = true
            HapticCue.taught.forEachIndexed { n, cue ->
                b.patterns.addView(TextView(this).apply {
                    text = getString(R.string.band_pattern_text, n + 1, cue.label, cue.meaning)
                    setTextColor(ContextCompat.getColor(context, R.color.reach_text)); textSize = 17f
                    setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
                })
                b.patterns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.band_play_pattern, cue.label)
                    contentDescription = getString(R.string.band_play_pattern_desc, cue.label)
                    minHeight = (56 * resources.displayMetrics.density).toInt(); textSize = 17f
                    setOnClickListener { band.practice(cue.demo()) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        for (i in 0 until b.patterns.childCount) (b.patterns.getChildAt(i) as? MaterialButton)?.isEnabled = ready
    }

    private fun title(step: SetupStep) = when (step) {
        SetupStep.WELCOME -> "Welcome to REACH"
        SetupStep.EXPLAIN -> "Your vibration band"
        SetupStep.CONNECT -> "Connect the band"
        SetupStep.LEFT -> "Test the left motor"
        SetupStep.RIGHT -> "Test the right motor"
        SetupStep.LEARN -> "Learn the patterns"
        SetupStep.CONFIRM -> "Ready to use haptics?"
    }

    private fun body(step: SetupStep, ready: Boolean): String = when (step) {
        SetupStep.WELCOME -> "REACH helps you find everyday objects and reach them with your hand. ${SupportedClasses.SUPPORTED_LINE} " +
            "Ask for an object by voice or by typing. The phone's camera finds it and follows your hand, and I tell you " +
            "which way to move, left, right, up or down, until you are lined up. In depth mode I also estimate the distance. " +
            "If tracking becomes uncertain I pause rather than guess, and you tell me when you have the object. " +
            "A vibration band can give the directions by touch as well. Next, I'll introduce the band."
        SetupStep.EXPLAIN -> "The band has a left and a right motor. Different vibration patterns tell you which way to move " +
            "your hand, when you are close, when you have reached the object, and when I have lost track of it. " +
            "Keep listening to my spoken instructions and stay aware of your surroundings: the band adds to them, it never " +
            "replaces them. The band is optional. REACH works by voice without it, but haptic guidance needs a connected, " +
            "working band. It has nine patterns: " + HapticCue.taught.joinToString(", ") { it.label.lowercase() } +
            ". After connecting, you'll test each motor and practise every pattern. These patterns are a prototype and " +
            "are still being tested."
        SetupStep.CONNECT -> if (ready) "The band is connected and ready. Next, we'll test each motor."
            else "Switch the band on and keep it close to the phone, then tap Connect."
        SetupStep.LEFT, SetupStep.RIGHT -> {
            val m = setup.motor!!
            val side = if (m == 'L') "left" else "right"
            when {
                !ready -> "The band is not connected. Reconnect it to test the motors."
                setup.felt[m] == Felt.NOT_FELT -> "You didn't feel the $side motor. Check the band is on and snug, then play it " +
                    "again. You can continue, but haptic guidance stays off until both motors pass."
                setup.felt[m] == Felt.OTHER_SIDE -> "You felt it on the other side, so the band may be worn the other way round " +
                    "or its motors swapped. REACH will not swap them for you. Turn the band around and play it again, or continue " +
                    "and report the issue; haptic guidance stays off until both motors pass."
                else -> "This is the $side motor. Did you feel it on your $side side?"
            }
        }
        SetupStep.LEARN -> "Practice only: these buttons play each pattern on the band. Nothing here is live guidance, and " +
            "no task is started or completed." + if (ready) "" else " Connect the band to play them."
        SetupStep.CONFIRM -> setup.summary() + if (setup.bothFelt) " Do you understand the patterns and want to use haptic guidance?" else ""
    }
}
