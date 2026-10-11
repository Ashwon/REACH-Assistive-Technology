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
import java.util.Locale

/** V6 — TTS output. Guidance phrases are short by design. */
class Speaker(context: Context) {
    private var ready = false
    private var pending: String? = null
    private lateinit var tts: TextToSpeech
    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                ready = true
                pending?.let { say(it) }
            }
        }
    }

    fun say(text: String, interrupt: Boolean = true) {
        if (!ready) { pending = text; return }
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "reach")
    }

    fun sayGuidance(g: Guidance, target: String) {
        val text = when (g) {
            Guidance.LEFT -> "Move left."
            Guidance.RIGHT -> "Move right."
            Guidance.UP -> "Move up."
            Guidance.DOWN -> "Move down."
            Guidance.LINED_UP_2D -> "Lined up. Reach forward slowly."
            Guidance.CLOSER -> "Move closer."
            Guidance.STOP -> "Stop."
            Guidance.REACHED -> "${target.replaceFirstChar { it.uppercase() }} reached."
            Guidance.HAND_COVERS_TARGET -> "Your hand is covering the $target. Reach forward slowly."
            Guidance.HAND_LOST -> "I can't see your hand."
            Guidance.TARGET_LOST -> "I can't see the $target. Hold still."
            Guidance.NONE -> return
        }
        say(text)
    }

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
