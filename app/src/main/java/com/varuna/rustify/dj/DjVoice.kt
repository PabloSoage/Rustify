package com.varuna.rustify.dj

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.varuna.rustify.player.AudioPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * E90 — Voz del DJ (estilo "DJ Livi"). Modelo **híbrido**:
 *  - Por defecto: `TextToSpeech` nativo de Android (offline, gratis, sin clave), con tono alegre y
 *    con el **idioma de la voz configurable aparte** del idioma de la app ([DjSettings.voiceLanguage]).
 *  - Opcional: un endpoint TTS en la nube OpenAI-compatible (`/v1/audio/speech`) para voz más natural
 *    ([DjSettings.voiceCloudUrl]); si falla, cae al TTS nativo.
 *
 * Singleton con un único motor TTS reutilizable. `speak` encola con QUEUE_FLUSH (cada intervención
 * del DJ reemplaza la anterior). El [onSpeakStart]/[onSpeakDone] permite al llamador atenuar la música.
 */
object DjVoice {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var appCtx: android.app.Application? = null
    private val pending = ArrayList<String>()

    // Ducking: baja el volumen de la música mientras el DJ habla y lo restaura al terminar.
    private fun duck() { appCtx?.let { runCatching { AudioPlayerService.getInstance(it).duckForVoice() } } }
    private fun unduck() { appCtx?.let { runCatching { AudioPlayerService.getInstance(it).unduckFromVoice() } } }

    var onSpeakStart: (() -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null

    fun init(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        appCtx = app as android.app.Application
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                applyVoiceConfig(app)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { duck(); onSpeakStart?.invoke() }
                    override fun onDone(utteranceId: String?) { unduck(); onSpeakDone?.invoke() }
                    @Deprecated("deprecated in API 21") override fun onError(utteranceId: String?) { unduck(); onSpeakDone?.invoke() }
                })
                synchronized(pending) { pending.forEach { speakNative(it) }; pending.clear() }
            }
        }
    }

    private fun applyVoiceConfig(context: Context) {
        val t = tts ?: return
        val langCode = DjSettings.voiceLanguage(context)
        val locale = if (langCode.isNotBlank()) Locale.forLanguageTag(langCode) else Locale.getDefault()
        runCatching { t.language = locale }
        t.setPitch(1.15f)        // ligeramente agudo = más "cheerful"
        t.setSpeechRate(1.03f)
    }

    /** Reaplica el idioma/tono de voz tras cambiarlo en Ajustes. */
    fun refreshConfig(context: Context) { if (ready) applyVoiceConfig(context) }

    /** Habla [text]. Cloud si está configurado; si no (o si falla), TTS nativo. */
    fun speak(context: Context, text: String) {
        if (!DjSettings.voiceEnabled(context) || text.isBlank()) return
        appCtx = context.applicationContext as android.app.Application
        val cloud = DjSettings.voiceCloudUrl(context)
        if (cloud.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                val ok = runCatching { speakCloud(context, cloud, text) }.getOrDefault(false)
                if (!ok) withContext(Dispatchers.Main) { ensureAndSpeak(context, text) }
            }
        } else {
            ensureAndSpeak(context, text)
        }
    }

    private fun ensureAndSpeak(context: Context, text: String) {
        if (tts == null) init(context)
        if (ready) speakNative(text) else synchronized(pending) { pending.add(text) }
    }

    private fun speakNative(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dj-" + text.hashCode())
    }

    fun stop() {
        runCatching { tts?.stop() }
        runCatching { cloudPlayer?.stop() }
        unduck()
        onSpeakDone?.invoke()
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null; ready = false
        releaseCloud()
    }

    // ── Cloud TTS (OpenAI-compatible /audio/speech) — best-effort ────────────────────────
    private var cloudPlayer: MediaPlayer? = null

    private fun releaseCloud() {
        runCatching { cloudPlayer?.release() }
        cloudPlayer = null
    }

    private suspend fun speakCloud(context: Context, endpoint: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val key = DjSettings.voiceCloudKey(context)
        val voice = DjSettings.voiceCloudVoice(context)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000; readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
        }
        val body = JSONObject()
            .put("model", "tts-1")
            .put("input", text)
            .put("voice", voice)
            .put("response_format", "mp3")
            .toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext false }
        val tmp = File(context.cacheDir, "dj_voice.mp3")
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        withContext(Dispatchers.Main) {
            releaseCloud()
            duck(); onSpeakStart?.invoke()
            cloudPlayer = MediaPlayer().apply {
                setDataSource(tmp.absolutePath)
                setOnCompletionListener { unduck(); onSpeakDone?.invoke() }
                setOnErrorListener { _, _, _ -> unduck(); onSpeakDone?.invoke(); true }
                prepare()
                start()
            }
        }
        true
    }
}
