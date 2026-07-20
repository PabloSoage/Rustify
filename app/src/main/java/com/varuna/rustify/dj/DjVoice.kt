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
            val voices = runCatching { engine.voices }.getOrNull().orEmpty()
                .filter { v -> target == null || v.locale?.language?.lowercase() == target }
                // fuera las voces que requieren descarga (no reproducirían) para no ensuciar la lista.
                .filter { v -> v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name })
            // Numera las voces dentro de cada locale para que sean DISTINGUIBLES: antes todas se
            // llamaban igual ("español"); ahora "Español (España) · Voz 1 · HD", "· Voz 2", …
            val perLocale = HashMap<String, Int>()
            return voices.map { v ->
                val key = v.locale?.toString() ?: ""
                val n = (perLocale[key] ?: 0) + 1; perLocale[key] = n
                v.name to friendlyVoiceLabel(v, n)
            }
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

    /** Etiqueta legible y distinguible para una voz nativa. Android no expone nombre ni género, así
     *  que sintetizamos: idioma (país) + un índice estable dentro del idioma + calidad + si necesita red. */
    private fun friendlyVoiceLabel(v: android.speech.tts.Voice, index: Int): String {
        val base = (v.locale?.getDisplayName(Locale.getDefault()) ?: v.name).replaceFirstChar { it.uppercase() }
        val q = if (v.quality >= 400) " · HD" else ""
        val net = if (v.isNetworkConnectionRequired) " · red" else ""
        return "$base · Voz $index$q$net"
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
            "gtranslate" -> CoroutineScope(Dispatchers.IO).launch {
                val ok = runCatching { speakGoogleTranslate(context, text) }.getOrDefault(false)
                if (!ok) withContext(Dispatchers.Main) { ensureAndSpeak(context, text) }
            }
            "edge" -> CoroutineScope(Dispatchers.IO).launch {
                val ok = runCatching { speakEdge(context, text) }.getOrDefault(false)
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
        val tmp = File(context.cacheDir, "dj_voice.mp3")
        // readTimeout acotado a 12 s: antes eran 25 s, así una voz que se colgaba tardaba ~25 s en caer
        // al TTS nativo (era el "fable tarda un buen rato en hacer fallback"). Una frase corta de TTS se
        // genera en pocos segundos; si a los 12 s no hay audio válido, caemos rápido a la voz nativa.
        if (!requestPollinationsToFile(context, text, voice, readTimeoutMs = 12000, dst = tmp)) return@withContext false
        withContext(Dispatchers.Main) { playMp3(tmp) }
        true
    }

    /**
     * GET a Pollinations TTS (`/{texto}?model=openai-audio&voice=…`) → guarda el audio en [dst].
     * `referrer` identifica la app; UA/Referer de navegador para evitar bloqueos que devolvían texto de
     * error. Devuelve true solo si la respuesta es 2xx y el contenido parece audio (validación por bytes,
     * no por content-type, que Pollinations no siempre pone bien).
     */
    private fun requestPollinationsToFile(context: Context, text: String, voice: String, readTimeoutMs: Int, dst: File): Boolean {
        val enc = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        val url = "${DjSettings.POLLINATIONS_TTS_BASE}/$enc?model=openai-audio&voice=$voice&referrer=rustify"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000; readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) Rustify")
            setRequestProperty("Accept", "audio/mpeg, audio/*;q=0.9, */*;q=0.5")
            setRequestProperty("Referer", "https://rustify.app/")
        }
        if (conn.responseCode !in 200..299) { conn.disconnect(); return false }
        conn.inputStream.use { input -> dst.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        return dst.length() >= 1024 && looksLikeAudio(dst)
    }

    /**
     * "Ping" de salud de una voz de Pollinations: pide una palabra y mide el round-trip real. Devuelve
     * ms si responde audio válido, o null si falla/timeout. Alimenta el indicador de salud de Ajustes
     * (mismo esquema que [DjProviders.measureLatency]/[DjProviders.classify] del modelo).
     */
    suspend fun probeVoice(context: Context, voice: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val start = System.nanoTime()
            val ok = requestPollinationsToFile(context, "Hi", voice, readTimeoutMs = 12000,
                dst = File(context.cacheDir, "dj_voice_probe_$voice.mp3"))
            if (ok) (System.nanoTime() - start) / 1_000_000 else null
        }.getOrNull()
    }

    // ── Google Translate TTS — gratis y SIN token (confirmado funcionando) ────────────────
    // `GET translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl={idioma}&q={texto}` → MP3.
    // Voz por IDIOMA (no hay selección de voz). Límite ~200 chars por petición, así que troceamos el
    // texto y concatenamos los MP3 (los frames MP3 son autocontenidos: concatenados suenan seguidos).

    /** Código `tl` (base de idioma) para Google Translate: el de la voz, o el del sistema. */
    private fun googleTtsLang(context: Context): String {
        val lang = DjSettings.voiceLanguage(context)
        val base = if (lang.isNotBlank()) lang else Locale.getDefault().language
        return base.substringBefore('-').lowercase().ifBlank { "en" }
    }

    /** Trocea [text] en piezas de ≤[maxLen] chars por límites de palabra (partiendo palabras enormes). */
    private fun chunkForTts(text: String, maxLen: Int): List<String> {
        val t = text.trim()
        if (t.isBlank()) return emptyList()
        if (t.length <= maxLen) return listOf(t)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (word in t.split(" ")) {
            if (cur.isNotEmpty() && cur.length + 1 + word.length > maxLen) { out.add(cur.toString()); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(word)
            while (cur.length > maxLen) { out.add(cur.substring(0, maxLen)); cur = StringBuilder(cur.substring(maxLen)) }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun googleTtsUrl(tl: String, chunk: String): String {
        val enc = URLEncoder.encode(chunk, "UTF-8").replace("+", "%20")
        return "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$tl&q=$enc"
    }

    private suspend fun speakGoogleTranslate(context: Context, text: String): Boolean = withContext(Dispatchers.IO) {
        val tl = googleTtsLang(context)
        val chunks = chunkForTts(text, 190)
        if (chunks.isEmpty()) return@withContext false
        val tmp = File(context.cacheDir, "dj_voice.mp3")
        tmp.outputStream().use { out ->
            for (c in chunks) {
                val conn = (URL(googleTtsUrl(tl, c)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000; readTimeout = 12000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) Rustify")
                    setRequestProperty("Referer", "https://translate.google.com/")
                }
                if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext false }
                conn.inputStream.use { it.copyTo(out) }
                conn.disconnect()
            }
        }
        if (tmp.length() < 512 || !looksLikeAudio(tmp)) return@withContext false
        withContext(Dispatchers.Main) { playMp3(tmp) }
        true
    }

    /** Ping de salud de Google Translate TTS para el idioma actual (mismo esquema ● que el resto). */
    suspend fun probeGoogleTranslate(context: Context): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val start = System.nanoTime()
            val conn = (URL(googleTtsUrl(googleTtsLang(context), "Hi")).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 5000; readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) Rustify")
                setRequestProperty("Referer", "https://translate.google.com/")
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            if (ok) (System.nanoTime() - start) / 1_000_000 else null
        }.getOrNull()
    }

    // ── Microsoft Edge (Read Aloud) TTS — voces NEURALES, gratis y SIN token ──────────────────────
    // Protocolo WebSocket validado end-to-end contra speech.platform.bing.com. Requiere un token
    // Sec-MS-GEC = SHA-256(ticks_100ns_redondeados_a_5min + TRUSTED_CLIENT_TOKEN), la Origin de la
    // extensión de Edge Read Aloud y una versión de Chromium reciente (constantes tomadas del proyecto
    // edge-tts). El audio llega en frames binarios [len_cabecera(2B BE)][cabecera][mp3].
    private const val EDGE_TRUSTED = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val EDGE_VERSION = "1-143.0.3650.75"
    private const val EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    private const val EDGE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    private val edgeWsClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .pingInterval(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** Token Sec-MS-GEC. Mirror del algoritmo de edge-tts (float64 idéntico: mismo hash aceptado). */
    private fun edgeSecToken(): String {
        var ticks = System.currentTimeMillis() / 1000.0
        ticks += 11644473600.0            // WIN_EPOCH (segundos entre 1601 y 1970)
        ticks -= ticks % 300.0            // redondeo a 5 min
        ticks *= 1e9 / 100.0              // a intervalos de 100 ns
        val str = String.format(Locale.US, "%.0f", ticks) + EDGE_TRUSTED
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(str.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02X".format(it) }
    }

    private fun edgeDateStr(): String {
        val sdf = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;")

    private fun edgeUrl(): String =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
        "?TrustedClientToken=$EDGE_TRUSTED&Sec-MS-GEC=${edgeSecToken()}&Sec-MS-GEC-Version=$EDGE_VERSION"

    private fun edgeRequest(): okhttp3.Request = okhttp3.Request.Builder().url(edgeUrl())
        .header("Origin", EDGE_ORIGIN).header("User-Agent", EDGE_UA)
        .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
        .header("Accept-Language", "en-US,en;q=0.9").build()

    private suspend fun speakEdge(context: Context, text: String): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val voice = DjSettings.voiceCloudVoice(context).takeIf { it.contains("Neural") } ?: DjSettings.EDGE_DEFAULT_VOICE
            val locale = voice.split("-").let { if (it.size >= 2) "${it[0]}-${it[1]}" else "en-US" }
            val audio = java.io.ByteArrayOutputStream()
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            var wsRef: okhttp3.WebSocket? = null
            fun finish(ok: Boolean) {
                if (!done.compareAndSet(false, true)) return
                runCatching { wsRef?.close(1000, null) }
                if (ok && audio.size() > 512) {
                    val f = File(context.cacheDir, "dj_voice.mp3")
                    runCatching { f.writeBytes(audio.toByteArray()) }
                    android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { playMp3(f) } }
                    cont.resumeWith(Result.success(true))
                } else cont.resumeWith(Result.success(false))
            }
            val listener = object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.send("X-Timestamp:${edgeDateStr()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}")
                    val reqid = java.util.UUID.randomUUID().toString().replace("-", "")
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$locale'>" +
                        "<voice name='$voice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>${xmlEscape(text)}</prosody></voice></speak>"
                    webSocket.send("X-RequestId:$reqid\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${edgeDateStr()}Z\r\nPath:ssml\r\n\r\n$ssml")
                }
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) finish(true)
                }
                override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                    val b = bytes.toByteArray()
                    if (b.size < 2) return
                    val hlen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                    val start = 2 + hlen
                    if (start > b.size) return
                    val header = String(b, 2, hlen, Charsets.UTF_8)
                    if (header.contains("Path:audio")) audio.write(b, start, b.size - start)
                }
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) { finish(false) }
                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { finish(audio.size() > 512) }
            }
            wsRef = edgeWsClient.newWebSocket(edgeRequest(), listener)
            cont.invokeOnCancellation { runCatching { wsRef?.cancel() } }
        }

    /** Ping de salud de Edge TTS: abre el WebSocket (token aceptado = 101). ms o null. Voz-independiente. */
    suspend fun probeEdge(): Long? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val start = System.nanoTime()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                if (done.compareAndSet(false, true)) {
                    runCatching { webSocket.close(1000, null) }
                    cont.resumeWith(Result.success((System.nanoTime() - start) / 1_000_000))
                }
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                if (done.compareAndSet(false, true)) cont.resumeWith(Result.success(null))
            }
        }
        val ws = edgeWsClient.newWebSocket(edgeRequest(), listener)
        cont.invokeOnCancellation { runCatching { ws.cancel() } }
    }

    /** Heurística "esto es audio y no un error de texto/JSON/HTML". */
    private fun looksLikeAudio(f: File): Boolean = runCatching {
        val b = ByteArray(4)
        f.inputStream().use { it.read(b) }
        val s = String(b, Charsets.ISO_8859_1)
        when {
            s.startsWith("ID3") -> true                                        // MP3 con tag
            b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> true    // MP3 frame sync
            s.startsWith("OggS") -> true                                       // OGG
            s.startsWith("RIFF") -> true                                       // WAV
            s.startsWith("{") || s.startsWith("<") || s.startsWith("Error", true) -> false // texto de error
            else -> true                                                       // binario desconocido: intenta
        }
    }.getOrDefault(false)

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
