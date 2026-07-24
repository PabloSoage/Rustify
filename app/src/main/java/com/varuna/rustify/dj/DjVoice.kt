package com.varuna.rustify.dj

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.varuna.rustify.dj.DjVoice.onSpeakDone
import com.varuna.rustify.dj.DjVoice.onSpeakStart
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
 * DJ voice. Hybrid model:
 *  - Default: Android's native `TextToSpeech` (offline, free, no key), with a cheerful tone and a
 *    voice language configurable independently of the app language ([DjSettings.voiceLanguage]).
 *  - Optional: an OpenAI-compatible cloud TTS endpoint (`/v1/audio/speech`) for a more natural voice
 *    ([DjSettings.voiceCloudUrl]); falls back to native TTS on failure.
 *
 * Singleton with a single reusable TTS engine. `speak` enqueues with QUEUE_FLUSH (each DJ utterance
 * replaces the previous one). [onSpeakStart]/[onSpeakDone] let the caller duck the music.
 */
object DjVoice {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var appCtx: android.app.Application? = null
    private val pending = ArrayList<String>()

    // Ducking: lowers the music volume while the DJ speaks and restores it when done.
    private fun duck() { appCtx?.let { runCatching { AudioPlayerService.getInstance(it).duckForVoice() } } }
    private fun unduck() { appCtx?.let { runCatching { AudioPlayerService.getInstance(it).unduckFromVoice() } } }

    var onSpeakStart: (() -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null

    /**
     * One-shot callback fired when the preview phrase finishes (native or cloud). Cleared
     * automatically after it runs; does not affect the global [onSpeakDone].
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
        // Specific native voice chosen in Settings (if still installed); otherwise the language default.
        val voiceName = DjSettings.voiceNativeName(context)
        if (voiceName.isNotBlank()) {
            runCatching { t.voices?.firstOrNull { it.name == voiceName }?.let { t.voice = it } }
        }
        t.setPitch(1.15f)        // slightly higher pitch = more cheerful
        t.setSpeechRate(1.03f)
    }

    /** Reapplies language/voice/tone after they change in Settings. */
    fun refreshConfig(context: Context) { if (ready) applyVoiceConfig(context) }

    /**
     * Lists the installed native voices for [langCode] (empty = all languages) as `(id, label)`
     * pairs. Reuses the engine if it is already ready; otherwise creates a temporary one just to
     * query and shuts it down. The callback always arrives on the Main thread.
     */
    fun queryVoices(context: Context, langCode: String, onResult: (List<Pair<String, String>>) -> Unit) {
        fun collect(engine: TextToSpeech): List<Pair<String, String>> {
            val target = if (langCode.isBlank()) null else Locale.forLanguageTag(langCode).language.lowercase()
            val voices = runCatching { engine.voices }.getOrNull().orEmpty()
                .filter { v -> target == null || v.locale?.language?.lowercase() == target }
                // Drop voices that require a download (they would not play) to keep the list clean.
                .filter { v -> v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name })
            // Number the voices within each locale so they are distinguishable, e.g.
            // "Español (España) · Voz 1 · HD", "· Voz 2", … instead of all sharing one name.
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

    /** Readable, distinguishable label for a native voice. Android exposes neither a name nor a gender,
     *  so we synthesize one: language (country) + a stable index within the language + quality + network need. */
    private fun friendlyVoiceLabel(v: android.speech.tts.Voice, index: Int): String {
        val base = (v.locale?.getDisplayName(Locale.getDefault()) ?: v.name).replaceFirstChar { it.uppercase() }
        val q = if (v.quality >= 400) " · HD" else ""
        val net = if (v.isNetworkConnectionRequired) " · red" else ""
        return "$base · Voz $index$q$net"
    }

    /** Speaks [text] with the selected engine (native / pollinations / openai); falls back to native on failure. */
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
     * Previews the currently selected voice with a test phrase, regardless of whether the DJ is
     * enabled (so the user can hear it while picking a voice in Settings even with the DJ off). Uses
     * the engine/voice/language selected at that moment. [onDone] is invoked when speaking finishes
     * (or if it fails); it may be null.
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
     * Pollinations TTS — OpenAI voices, free and token-less:
     * `GET https://text.pollinations.ai/{text}?model=openai-audio&voice={voice}` → MP3.
     */
    private suspend fun speakPollinations(context: Context, text: String): Boolean = withContext(Dispatchers.IO) {
        val voice = DjSettings.voiceCloudVoice(context).ifBlank { "alloy" }
        val tmp = File(context.cacheDir, "dj_voice.mp3")
        // readTimeout capped at 12s: a short TTS phrase is generated within a few seconds, so if no
        // valid audio has arrived by 12s a hung voice falls back quickly to the native TTS instead of
        // leaving the user waiting.
        if (!requestPollinationsToFile(context, text, voice, readTimeoutMs = 12000, dst = tmp)) return@withContext false
        withContext(Dispatchers.Main) { playMp3(tmp) }
        true
    }

    /**
     * GET to Pollinations TTS (`/{text}?model=openai-audio&voice=…`) → saves the audio to [dst].
     * `referrer` identifies the app; a browser-like UA/Referer avoids blocks that returned error text.
     * Returns true only if the response is 2xx and the content looks like audio (validated by bytes,
     * not by content-type, which Pollinations does not always set correctly).
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
     * Health "ping" for a Pollinations voice: requests a single word and measures the real round-trip.
     * Returns ms if it responds with valid audio, or null on failure/timeout. Feeds the Settings health
     * indicator (same scheme as [DjProviders.measureLatency]/[DjProviders.classify] for the model).
     */
    suspend fun probeVoice(context: Context, voice: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val start = System.nanoTime()
            val ok = requestPollinationsToFile(context, "Hi", voice, readTimeoutMs = 12000,
                dst = File(context.cacheDir, "dj_voice_probe_$voice.mp3"))
            if (ok) (System.nanoTime() - start) / 1_000_000 else null
        }.getOrNull()
    }

    // ── Google Translate TTS — free and token-less ───────────────────────────────────────
    // `GET translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl={lang}&q={text}` → MP3.
    // Voice is per language (no voice selection). Limit of ~200 chars per request, so the text is
    // chunked and the MP3s concatenated (MP3 frames are self-contained: they play back seamlessly).

    /** `tl` code (base language) for Google Translate: the voice language, or the system one. */
    private fun googleTtsLang(context: Context): String {
        val lang = DjSettings.voiceLanguage(context)
        val base = lang.ifBlank { Locale.getDefault().language }
        return base.substringBefore('-').lowercase().ifBlank { "en" }
    }

    /** Splits [text] into pieces of ≤[maxLen] chars at word boundaries (breaking oversized words). */
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

    /** Health ping for Google Translate TTS in the current language (same scheme as the rest). */
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

    // ── Microsoft Edge (Read Aloud) TTS — neural voices, free and token-less ───────────────────────
    // WebSocket protocol validated end-to-end against speech.platform.bing.com. Requires a
    // Sec-MS-GEC = SHA-256(ticks_100ns_rounded_to_5min + TRUSTED_CLIENT_TOKEN) token, the Edge Read
    // Aloud extension Origin and a recent Chromium version (constants taken from the edge-tts project).
    // Audio arrives in binary frames [header_len(2B BE)][header][mp3].
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

    /** Sec-MS-GEC token. Mirrors the edge-tts algorithm (identical float64: same accepted hash). */
    private fun edgeSecToken(): String {
        var ticks = System.currentTimeMillis() / 1000.0
        ticks += 11644473600.0            // WIN_EPOCH (seconds between 1601 and 1970)
        ticks -= ticks % 300.0            // round to 5 min
        ticks *= 1e9 / 100.0              // to 100 ns intervals
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
                    Handler(Looper.getMainLooper()).post { runCatching { playMp3(f) } }
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

    /** Health ping for Edge TTS: opens the WebSocket (token accepted = 101). ms or null. Voice-independent. */
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

    /** Heuristic for "this is audio and not a text/JSON/HTML error response". */
    private fun looksLikeAudio(f: File): Boolean = runCatching {
        val b = ByteArray(4)
        f.inputStream().use { it.read(b) }
        val s = String(b, Charsets.ISO_8859_1)
        when {
            s.startsWith("ID3") -> true                                        // MP3 with tag
            b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> true    // MP3 frame sync
            s.startsWith("OggS") -> true                                       // OGG
            s.startsWith("RIFF") -> true                                       // WAV
            s.startsWith("{") || s.startsWith("<") || s.startsWith("Error", true) -> false // error text
            else -> true                                                       // unknown binary: attempt playback
        }
    }.getOrDefault(false)

    /** Plays an MP3 (cloud voice) with ducking; replaces the previous player. */
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
