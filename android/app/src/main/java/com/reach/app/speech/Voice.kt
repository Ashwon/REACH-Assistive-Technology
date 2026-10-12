package com.reach.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.reach.app.spatial.Guidance
import com.reach.app.spatial.phrase
import java.util.Locale

/** V6 — TTS output. Guidance phrases are short by design. Safe to call from any thread. */
class Speaker(context: Context) {
    private var ready = false
    private var pending: String? = null
    private lateinit var tts: TextToSpeech

    /** Off = nothing is spoken; phrases still reach [onSpoken] so the screen can show them. */
    @Volatile var enabled = true
    /** 0..1, relative to the device's media volume. */
    @Volatile var volume = 1f
    /** Every phrase the app means to say, muted or not. */
    @Volatile var onSpoken: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                ready = true
                pending?.let { speak(it, true) }
            }
        }
    }

    fun say(text: String, interrupt: Boolean = true) {
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

/** V6 — one-shot speech recognition. Call listen(), get the top transcript. */
class VoiceInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun listen(onResult: (String?) -> Unit) {
        recognizer?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { Log.w("REACH", "speech recognition unavailable"); onResult(null); return }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.i("REACH", "heard: $list")
                    onResult(list?.firstOrNull())
                }
                override fun onError(error: Int) { Log.w("REACH", "speech error=$error"); onResult(null) }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    fun destroy() { recognizer?.destroy(); recognizer = null }
}
