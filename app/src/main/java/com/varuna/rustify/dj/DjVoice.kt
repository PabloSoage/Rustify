package com.varuna.rustify.dj

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
import java.net.URLEncoder
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

    /**
     * Callback de un solo uso que se dispara al terminar la frase de preview (ya sea nativa o nube).
     * Se limpia automáticamente tras invocarse; no afecta el [onSpeakDone] global.
     */
    @Volatile
    private var previewOnDone: (() -> Unit)? = null

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
                    override fun onDone(utteranceId: String?) { unduck(); onSpeakDone?.invoke(); finishPreview() }
                    @Deprecated("deprecated in API 21") override fun onError(utteranceId: String?) { unduck(); onSpeakDone?.invoke(); finishPreview() }
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
        // Voz nativa concreta elegida en Ajustes (si sigue instalada); si no, la del idioma.
        val voiceName = DjSettings.voiceNativeName(context)
        if (voiceName.isNotBlank()) {
            runCatching { t.voices?.firstOrNull { it.name == voiceName }?.let { t.voice = it } }
        }
        t.setPitch(1.15f)        // ligeramente agudo = más "cheerful"
        t.setSpeechRate(1.03f)
    }

    /** Reaplica el idioma/voz/tono tras cambiarlo en Ajustes. */
    fun refreshConfig(context: Context) { if (ready) applyVoiceConfig(context) }

    /**
     * Enumera las voces nativas instaladas para [langCode] (vacío = todos los idiomas) como pares
     * `(id, etiqueta)`. Reutiliza el motor si ya está listo; si no, crea uno temporal solo para
     * consultar y lo apaga. El callback llega siempre en el hilo Main.
     */
    fun queryVoices(context: Context, langCode: String, onResult: (List<Pair<String, String>>) -> Unit) {
        fun collect(engine: TextToSpeech): List<Pair<String, String>> {
            val target = if (langCode.isBlank()) null else Locale.forLanguageTag(langCode).language.lowercase()
            return runCatching { engine.voices }.getOrNull().orEmpty()
                .filter { v -> target == null || v.locale?.language?.lowercase() == target }
                .sortedByDescending { it.quality }
                .map { it.name to voiceLabel(it) }
        }
        val existing = tts
        if (ready && existing != null) { onResult(collect(existing)); return }
        var tmp: TextToSpeech? = null
        tmp = TextToSpeech(context.applicationContext) { status ->
            val list = if (status == TextToSpeech.SUCCESS) collect(tmp!!) else emptyList()
            Handler(Looper.getMainLooper()).post { onResult(list) }
            runCatching { tmp?.shutdown() }
        }
    }

    private fun voiceLabel(v: android.speech.tts.Voice): String = buildString {
        append(v.locale?.displayName ?: v.name)
        val q = when {
            v.quality >= 500 -> "HQ"; v.quality >= 400 -> "High"
            v.quality >= 300 -> "Normal"; else -> "Low"
        }
        append(" · "); append(q)
        val tail = v.name.substringAfterLast('-', "")
        if (tail.isNotEmpty() && tail != v.name) { append(" · "); append(tail) }
        if (v.isNetworkConnectionRequired) append(" · net")
    }

    /** Habla [text] con el motor elegido (native / pollinations / openai); cae a nativo si falla. */
    fun speak(context: Context, text: String, force: Boolean = false) {
        if ((!DjSettings.voiceEnabled(context) && !force) || text.isBlank()) return
        appCtx = context.applicationContext as android.app.Application
        when (DjSettings.ttsEngine(context)) {
            "pollinations" -> CoroutineScope(Dispatchers.IO).launch {
                val ok = runCatching { speakPollinations(context, text) }.getOrDefault(false)
                if (!ok) withContext(Dispatchers.Main) { ensureAndSpeak(context, text) }
            }
            "openai" -> {
                val cloud = DjSettings.voiceCloudUrl(context)
                if (cloud.isNotBlank()) CoroutineScope(Dispatchers.IO).launch {
                    val ok = runCatching { speakCloud(context, cloud, text) }.getOrDefault(false)
                    if (!ok) withContext(Dispatchers.Main) { ensureAndSpeak(context, text) }
                } else ensureAndSpeak(context, text)
            }
            else -> ensureAndSpeak(context, text)
        }
    }

    /**
     * Previsualiza la voz actualmente seleccionada con una frase de prueba, IGNORANDO si el DJ está
     * activado o no (para que el usuario la oiga al elegir voz desde Ajustes aunque tenga el DJ off).
     * Usa el motor/voz/idioma que haya elegido en este momento. [onDone] se invoca al terminar de
     * hablar (o si falla); puede ser null.
     */
    fun preview(context: Context, onDone: (() -> Unit)? = null) {
        val phrase = DjPhrases.previewPhrase(DjSettings.voiceLanguage(context))
        previewOnDone = onDone
        speak(context, phrase, force = true)
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
        if (tmp.length() < 512) return@withContext false
        withContext(Dispatchers.Main) { playMp3(tmp) }
        true
    }

    /**
     * Pollinations TTS — voces OpenAI **gratis y sin token**:
     * `GET https://text.pollinations.ai/{texto}?model=openai-audio&voice={voz}` → MP3.
     */
    private suspend fun speakPollinations(context: Context, text: String): Boolean = withContext(Dispatchers.IO) {
        val voice = DjSettings.voiceCloudVoice(context).ifBlank { "alloy" }
        // El texto va en el path: codifica y usa %20 (no '+') para los espacios.
        val enc = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        val url = "${DjSettings.POLLINATIONS_TTS_BASE}/$enc?model=openai-audio&voice=$voice"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000; readTimeout = 20000
            setRequestProperty("Accept", "audio/mpeg")
        }
        if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext false }
        // Si el servicio devolviera un error de texto en vez de audio, no lo reproduzcas.
        val ctype = conn.contentType ?: ""
        val tmp = File(context.cacheDir, "dj_voice.mp3")
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        if (!ctype.contains("audio", true) || tmp.length() < 512) return@withContext false
        withContext(Dispatchers.Main) { playMp3(tmp) }
        true
    }

    /** Reproduce un MP3 (voz nube) con ducking; reemplaza al reproductor anterior. */
    private fun playMp3(file: File) {
        releaseCloud()
        duck(); onSpeakStart?.invoke()
        cloudPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { unduck(); onSpeakDone?.invoke(); finishPreview() }
            setOnErrorListener { _, _, _ -> unduck(); onSpeakDone?.invoke(); finishPreview(); true }
            prepare()
            start()
        }
    }

    private fun finishPreview() {
        val cb = previewOnDone
        if (cb != null) { previewOnDone = null; cb() }
    }
}
