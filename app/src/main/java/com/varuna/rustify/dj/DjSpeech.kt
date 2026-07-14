package com.varuna.rustify.dj

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * E90 — Reconocimiento de voz para controlar el DJ ("pon algo más movido", "cambia de mood"…).
 * Envuelve [SpeechRecognizer] nativo (offline si el dispositivo lo soporta, gratis, sin claves).
 *
 * ⚠️ Requiere el permiso `RECORD_AUDIO` (declarado en el Manifest + concedido en runtime por el
 * llamador). Debe crearse y usarse en el **hilo principal**.
 */
class DjSpeech(context: Context) {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    var isListening = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Empieza a escuchar una frase. [languageTag] p.ej. "es-ES"/"en-US"; en blanco ⇒ idioma del sistema.
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
