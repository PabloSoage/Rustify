package com.varuna.rustify.dj

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Voice recognition to control the DJ ("play something more upbeat", "change the mood"…).
 * Wraps the native [SpeechRecognizer] (offline where the device supports it, free, no keys).
 *
 * Requires the `RECORD_AUDIO` permission (declared in the Manifest and granted at runtime by the
 * caller). Must be created and used on the main thread.
 */
class DjSpeech(context: Context) {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    var isListening = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Starts listening for a phrase. [languageTag] e.g. "es-ES"/"en-US"; blank ⇒ system language.
     */
    fun start(
        languageTag: String,
        onResult: (String) -> Unit,
        onError: (Int) -> Unit = {},
        onReady: () -> Unit = {}
    ) {
        if (!isAvailable()) { onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        stop()
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { isListening = true; onReady() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { isListening = false }
                override fun onError(error: Int) { isListening = false; onError(error) }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) onResult(text) else onError(SpeechRecognizer.ERROR_NO_MATCH)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val locale = if (languageTag.isNotBlank()) languageTag else Locale.getDefault().toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { recognizer?.startListening(intent) }.onFailure { onError(SpeechRecognizer.ERROR_CLIENT) }
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        isListening = false
    }
}
