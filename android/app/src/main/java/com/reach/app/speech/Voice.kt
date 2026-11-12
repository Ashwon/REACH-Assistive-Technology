package com.reach.app.speech

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.phrase
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** V6 — TTS output. Guidance phrases are short by design. Safe to call from any thread. */
class Speaker(context: Context) {
    private var ready = false
    private var pending: String? = null
    private lateinit var tts: TextToSpeech
    @Volatile private var lastText = ""
    @Volatile private var lastSpokenAt = 0L
    @Volatile private var speakingUntil = 0L

    /** Off = nothing is spoken; phrases still reach [onSpoken] so the screen can show them. */
    @Volatile var enabled = true
    /** 0..1, relative to the device's media volume. */
    @Volatile var volume = 1f
    /** Every phrase the app means to say, muted or not. */
    @Volatile var onSpoken: ((String) -> Unit)? = null

    val isSpeakingNow: Boolean
        get() = (ready && runCatching { tts.isSpeaking }.getOrDefault(false)) || SystemClock.uptimeMillis() < speakingUntil

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(1.15f)
                tts.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ready = true
                pending?.let { speak(it, true) }
            }
        }
    }

    @Synchronized fun say(text: String, interrupt: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        val urgent = text.endsWith("reached.") || text == "Stop." || text == "Reset." || text == "Listening."
        val navigationCue = text.startsWith("Move ") || text.startsWith("Slightly ") ||
            text.startsWith("Lined up") || text.startsWith("Reach forward") ||
            text.startsWith("Right in front") || text.startsWith("Almost there")
        // Honor GuidanceThrottle's repeat cadence; only guard against same-frame duplicates (< 1.6s).
        if (!urgent && text == lastText && now - lastSpokenAt < 1600L) return
        // Only shield routine status chatter (e.g., hand-lost) from interrupting an active utterance within 900ms;
        // never swallow live navigation cues that GuidanceThrottle has already approved.
        if (!urgent && !navigationCue && interrupt && now - lastSpokenAt < 900L && isSpeakingNow) return
        lastText = text
        lastSpokenAt = now
        // Add a 350ms acoustic tail buffer so the microphone doesn't pick up room echo from the speaker.
        speakingUntil = now + (48L * text.length).coerceIn(850L, 4800L) + 350L
        Log.i("REACH", "say \"$text\"") // every phrase, so a device log shows exactly what was spoken and when
        onSpoken?.invoke(text)
        if (!enabled) return
        if (!ready) { pending = text; return }
        speak(text, interrupt)
    }

    private fun speak(text: String, interrupt: Boolean) {
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "reach")
    }

    fun sayGuidance(g: Guidance, target: String) { say(g.phrase(target) ?: return) }

    fun shutdown() = tts.shutdown()
}

/**
 * V6 — Silent, offline continuous speech recognition using Vosk + AudioRecord.
 * Never calls Android's SpeechRecognizer.startListening() in a loop, so Android never plays
 * system voice-notification chimes turning on and off.
 *
 * Supports:
 * 1. Hands-free "Reach" wake word ("reach", "hey reach", or "reach find my bottle").
 * 2. Direct voice confirmation during guidance ("got it", "reached", "reached it", "I have it", "found it", "yes", "stop")
 *    without even needing to say "reach" first.
 * 3. Manual one-shot listening when the user taps the Talk / Find button.
 */
class VoiceInput(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var wakeEnabled = false
    @Volatile private var isSpeakerBusy: () -> Boolean = { false }
    @Volatile private var isGuiding: () -> Boolean = { false }
    @Volatile private var onWake: () -> Unit = {}
    @Volatile private var onWakeCommand: (String) -> Unit = {}
    @Volatile private var manualCallback: ((String?) -> Unit)? = null
    @Volatile private var manualDeadlineMs = 0L
    @Volatile private var resetRecognizerFlag = false
    @Volatile private var lastTriggerMs = 0L
    private var worker: Thread? = null
    @Volatile private var voskModel: Model? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val ASSET_MODEL_DIR = "model-en-us"

        /**
         * Focused grammar for fast, high-accuracy offline recognition of wake words, target objects,
         * guidance confirmations ("got it", "reached"), and commands, with "[unk]" absorbing background noise.
         */
        private val GRAMMAR_JSON = """
            [
              "reach", "hey reach", "okay reach",
              "find", "where", "where is", "where was it", "look for", "locate", "get", "help", "help me",
              "what do you see", "what can you see", "scan",
              "light on", "light off", "turn on light", "turn off light", "flashlight", "torch",
              "my", "the", "a", "an", "this", "that", "it", "one", "other", "on", "next to", "near",
              "bottle", "water bottle", "flask",
              "laptop", "computer",
              "pen", "pencil", "marker",
              "coke", "cola", "pepsi", "sprite",
              "left", "right", "middle", "center",
              "blue", "red", "green", "black", "white", "silver", "yellow", "orange", "pink", "purple", "brown", "grey", "gray",
              "guide", "guide me", "continue", "keep going", "try again", "start",
              "hand", "hands", "set up", "use", "switch",
              "got it", "i got it", "have it", "i have it", "holding it", "touching it", "touched it",
              "found it", "reached", "reached it", "i reached", "i reached it", "grabbed it", "picked it up", "done", "yes", "yeah",
              "stop", "cancel", "reset", "never mind", "forget it", "no",
              "[unk]"
            ]
        """.trimIndent()

        private val DIRECT_GUIDING_PHRASES = setOf(
            "got it", "i got it", "have it", "i have it", "holding it", "touching it", "touched it",
            "found it", "reached", "reached it", "i reached", "i reached it", "grabbed it", "picked it up",
            "done", "yes", "yeah", "stop", "cancel", "reset", "where is it", "where was it"
        )
    }

    fun startWakeWord(
        speakerBusy: () -> Boolean,
        guidingActive: () -> Boolean = { false },
        onActivated: () -> Unit,
        onCommand: (String) -> Unit
    ) {
        isSpeakerBusy = speakerBusy
        isGuiding = guidingActive
        onWake = onActivated
        onWakeCommand = onCommand
        if (wakeEnabled && worker?.isAlive == true) return
        wakeEnabled = true
        startWorkerIfNeeded()
    }

    fun stopWakeWord() {
        wakeEnabled = false
        manualCallback = null
        worker?.interrupt()
        worker = null
    }

    /** Triggered when the user taps the Talk button or says "Reach" alone. Uses the silent Vosk stream (no Android beep). */
    fun listen(onResult: (String?) -> Unit) {
        manualCallback = onResult
        manualDeadlineMs = SystemClock.uptimeMillis() + 6500L
        resetRecognizerFlag = true
        if (!wakeEnabled || worker?.isAlive != true) {
            wakeEnabled = true
            startWorkerIfNeeded()
        }
    }

    @Synchronized
    private fun startWorkerIfNeeded() {
        if (worker?.isAlive == true) return
        worker = Thread({ runAudioLoop() }, "ReachSilentVoice").apply {
            isDaemon = true
            start()
        }
    }

    private fun ensureModel(): Model? {
        voskModel?.let { return it }
        return try {
            val dstDir = File(context.filesDir, ASSET_MODEL_DIR)
            val marker = File(dstDir, "conf/model.conf")
            if (!marker.exists()) {
                Log.i("REACH", "Extracting offline speech model to ${dstDir.absolutePath}...")
                copyAssetDir(context.assets, ASSET_MODEL_DIR, dstDir)
            }
            Model(dstDir.absolutePath).also {
                voskModel = it
                Log.i("REACH", "Offline silent speech model ready")
            }
        } catch (e: Exception) {
            Log.e("REACH", "Failed to load offline speech model", e)
            null
        }
    }

    private fun copyAssetDir(assets: AssetManager, assetPath: String, dstDir: File) {
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            dstDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(dstDir).use { output -> input.copyTo(output) }
            }
        } else {
            dstDir.mkdirs()
            for (child in children) {
                copyAssetDir(assets, "$assetPath/$child", File(dstDir, child))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runAudioLoop() {
        val model = ensureModel() ?: return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE / 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: Exception) {
            Log.e("REACH", "AudioRecord init failed", e)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("REACH", "AudioRecord not initialized")
            record.release()
            return
        }

        val recognizer = try {
            Recognizer(model, SAMPLE_RATE.toFloat(), GRAMMAR_JSON)
        } catch (e: Exception) {
            Log.e("REACH", "Recognizer init failed", e)
            record.release()
            return
        }

        val buf = ShortArray(2048) // ~128ms per read at 16kHz
        try {
            record.startRecording()
            Log.i("REACH", "Silent continuous voice listener started (zero system notification sounds)")
            while (wakeEnabled && !Thread.currentThread().isInterrupted) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue

                val now = SystemClock.uptimeMillis()

                if (resetRecognizerFlag) {
                    resetRecognizerFlag = false
                    recognizer.reset()
                }

                // Never listen to our own TTS output
                if (isSpeakerBusy()) {
                    recognizer.reset()
                    continue
                }

                // Check if an active manual/wake listen window timed out
                val activeCb = manualCallback
                if (activeCb != null && now > manualDeadlineMs) {
                    val finalTxt = cleanText(parseJsonField(recognizer.finalResult, "text"))
                    manualCallback = null
                    recognizer.reset()
                    val cleaned = stripLeadingWake(finalTxt)
                    main.post { activeCb(cleaned.ifEmpty { null }) }
                    continue
                }

                val isFinal = recognizer.acceptWaveForm(buf, n)
                if (activeCb != null) {
                    if (isFinal) {
                        val txt = cleanText(parseJsonField(recognizer.result, "text"))
                        val cleaned = stripLeadingWake(txt)
                        if (cleaned.isNotEmpty()) {
                            Log.i("REACH", "heard (active): \"$cleaned\"")
                            manualCallback = null
                            recognizer.reset()
                            lastTriggerMs = now
                            main.post { activeCb(cleaned) }
                        }
                    } else {
                        // Also allow immediate fast confirmation during active listen if user says "got it" / "reached"
                        val partial = stripLeadingWake(cleanText(parseJsonField(recognizer.partialResult, "partial")))
                        if (isGuiding() && partial in DIRECT_GUIDING_PHRASES && now - lastTriggerMs > 1200L) {
                            Log.i("REACH", "heard immediate confirmation (active): \"$partial\"")
                            manualCallback = null
                            recognizer.reset()
                            lastTriggerMs = now
                            main.post { activeCb(partial) }
                        }
                    }
                } else {
                    // Background wake-word + guidance confirmation mode
                    if (isFinal) {
                        val txt = cleanText(parseJsonField(recognizer.result, "text"))
                        if (txt.isNotEmpty()) {
                            handleBackgroundSpeech(txt, isPartial = false, recognizer = recognizer)
                        }
                    } else {
                        val partial = cleanText(parseJsonField(recognizer.partialResult, "partial"))
                        if (partial.isNotEmpty()) {
                            // Fast trigger if user says solely "reach" / "hey reach", or says "got it" / "reached" while guiding
                            if (partial == "reach" || partial == "hey reach" || partial == "okay reach" ||
                                (isGuiding() && partial in DIRECT_GUIDING_PHRASES)
                            ) {
                                handleBackgroundSpeech(partial, isPartial = true, recognizer = recognizer)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("REACH", "Silent voice loop ended", e)
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { recognizer.close() }
        }
    }

    private fun handleBackgroundSpeech(text: String, isPartial: Boolean, recognizer: Recognizer) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerMs < 1300L) return
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return

        // 1. Direct confirmation or stop command during guidance (no "reach" wake word required!)
        if (isGuiding()) {
            val directMatch = DIRECT_GUIDING_PHRASES.firstOrNull { phrase ->
                text == phrase || text.endsWith(" $phrase")
            }
            if (directMatch != null) {
                lastTriggerMs = now
                recognizer.reset()
                Log.i("REACH", "voice confirmation during guidance: \"$directMatch\"")
                main.post { onWakeCommand(directMatch) }
                return
            }
        }

        // 2. Wake word "reach" (alone or followed by a command like "reach find my bottle")
        val reachIdx = words.indexOfFirst { it == "reach" }
        if (reachIdx >= 0) {
            val after = words.drop(reachIdx + 1).joinToString(" ").trim()
            if (isPartial && after.isEmpty()) {
                // Wait briefly (~350ms) on partial "reach" only if user isn't mid-sentence; actually final result comes right after pause
                return
            }
            lastTriggerMs = now
            recognizer.reset()
            if (after.isEmpty()) {
                Log.i("REACH", "wake word 'reach' detected -> activating listener")
                main.post { onWake() }
            } else {
                Log.i("REACH", "wake word 'reach' + command: \"$after\"")
                main.post { onWakeCommand(after) }
            }
        }
    }

    private fun parseJsonField(json: String?, field: String): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString(field, "") }.getOrDefault("")
    }

    private fun cleanText(raw: String): String =
        raw.lowercase(Locale.US)
            .replace("[unk]", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun stripLeadingWake(text: String): String =
        text.replace(Regex("^(hey\\s+|ok\\s+|okay\\s+)?reach\\s+"), "").trim()

    fun destroy() {
        stopWakeWord()
        runCatching { voskModel?.close() }
        voskModel = null
    }
}
