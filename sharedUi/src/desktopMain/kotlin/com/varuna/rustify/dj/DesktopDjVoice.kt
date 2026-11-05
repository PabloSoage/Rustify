// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/dj/DesktopDjVoice.kt
package com.varuna.rustify.dj

import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.platform.IO
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.SystemSpeech
import com.varuna.rustify.ui.AppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/**
 * [DjVoiceOutput] on the desktop: supports both the system's own voice ([SystemSpeech], the "native"
 * engine) and online voices (Edge, Google Translate, Pollinations, OpenAI).
 *
 * For online engines:
 *  - MP3 audio is fetched/streamed.
 *  - Decoded to 16-bit PCM WAV in milliseconds via [NativeEngine.decodeToWav].
 *  - Played via [AudioSystem.getClip] with audio ducking ([duck]).
 *  - If online generation fails, it falls back to native speech.
 */
class DesktopDjVoice(
    private val platform: AppPlatform,
    private val duck: (Boolean) -> Unit = {},
    private val speech: SystemSpeech? = SystemSpeech.find(),
) : DjVoiceOutput {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Any()

    /** Bumped by every phrase and every stop: an operation that is not the latest one is cancelled. */
    private var generation = 0L
    private var process: Process? = null
    private var activeClip: Clip? = null
    private var ducked = false

    @Volatile
    private var previewOnDone: (() -> Unit)? = null

    /** Whether this computer has a speech command at all. */
    val isAvailable: Boolean get() = speech != null

    init {
        Log.i(TAG, "system voice: ${speech?.let { "${it.kind} (${it.executable})" } ?: "none on PATH"}")
    }

    override val engines: List<String> = listOf("native", "edge", "pollinations", "gtranslate", "openai")

    override fun init() = Unit

    override fun refreshConfig() = Unit

    override fun speak(text: String, force: Boolean) {
        if ((!DjSettings.voiceEnabled(platform) && !force) || text.isBlank()) return
        val mine = synchronized(lock) {
            generation++
            stopCurrentLocked()
            generation
        }

        val engine = DjSettings.ttsEngine(platform)
        io.launch {
            when (engine) {
                "edge" -> {
                    val audio = runCatching { fetchEdgeAudio(text, mine) }.getOrNull()
                    if (audio != null && audio.size > 512) {
                        playMp3Bytes(audio, text, mine)
                    } else {
                        speakNative(text, mine)
                    }
                }
                "pollinations", "gtranslate", "openai" -> {
                    val audio = runCatching { OnlineDjTts.fetchAudio(platform, engine, text) }.getOrNull()
                    if (audio != null && audio.size >= 512) {
                        playMp3Bytes(audio, text, mine)
                    } else {
                        speakNative(text, mine)
                    }
                }
                else -> speakNative(text, mine)
            }
        }
    }

    private suspend fun playMp3Bytes(bytes: ByteArray, fallbackText: String, mine: Long) {
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val mp3 = File(tmpDir, "dj_voice_${mine}.mp3")
        val wav = File(tmpDir, "dj_voice_${mine}.wav")
        try {
            mp3.writeBytes(bytes)
            val ok = NativeEngine.decodeToWav(mp3.absolutePath, wav.absolutePath)
            if (!ok || !wav.exists() || wav.length() < 44) {
                speakNative(fallbackText, mine)
                return
            }
            playWavFile(wav, mine)
        } catch (e: Throwable) {
            Log.w(TAG, "failed playing decoded audio", e)
            speakNative(fallbackText, mine)
        } finally {
            mp3.delete()
        }
    }

    private fun playWavFile(wav: File, mine: Long) {
        try {
            val audioIn = AudioSystem.getAudioInputStream(wav)
            val clip = AudioSystem.getClip()
            synchronized(lock) {
                if (mine != generation) {
                    audioIn.close()
                    clip.close()
                    wav.delete()
                    return
                }
                activeClip?.stop()
                activeClip?.close()
                activeClip = clip
            }

            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    clip.close()
                    wav.delete()
                    val last = synchronized(lock) {
                        if (mine == generation) {
                            activeClip = null
                            true
                        } else false
                    }
                    if (last) {
                        setDucked(false)
                        finishPreview()
                    }
                }
            }

            clip.open(audioIn)
            setDucked(true)
            clip.start()
        } catch (e: Throwable) {
            Log.w(TAG, "AudioSystem failed to play wav", e)
            wav.delete()
            setDucked(false)
            finishPreview()
        }
    }

    private fun speakNative(text: String, mine: Long) {
        val engine = speech
        if (engine == null) {
            finishPreview()
            return
        }
        val language = DjSettings.voiceLanguage(platform).ifBlank { Locale.getDefault().language }
        val voice = DjSettings.voiceNativeName(platform)
        val p = runCatching { engine.start(text, language, voice) }
            .onFailure { Log.w(TAG, "the system voice did not start", it) }
            .getOrNull()
        val current = synchronized(lock) {
            if (p != null && mine == generation) {
                process = p
                true
            } else false
        }
        if (p == null || !current) {
            p?.destroy()
            if (p == null) finishPreview()
            return
        }
        setDucked(true)
        runCatching { p.waitFor() }
        val last = synchronized(lock) {
            if (mine == generation) {
                process = null
                true
            } else false
        }
        if (last) {
            setDucked(false)
            finishPreview()
        }
    }

    override fun preview(onDone: (() -> Unit)?) {
        previewOnDone = onDone
        speak(DjPhrases.previewPhrase(DjSettings.voiceLanguage(platform)), force = true)
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            stopCurrentLocked()
        }
        setDucked(false)
    }

    private fun stopCurrentLocked() {
        process?.destroy()
        process = null
        try {
            activeClip?.stop()
            activeClip?.close()
        } catch (_: Throwable) {}
        activeClip = null
    }

    override fun queryVoices(langCode: String, onResult: (List<Pair<String, String>>) -> Unit) {
        val engine = speech
        io.launch {
            val voices = engine?.voices(langCode).orEmpty()
            withContext(Dispatchers.Main) { onResult(voices) }
        }
    }

    // ── Edge TTS implementation ─────────────────────────────────────────────
    private val edgeWsClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun fetchEdgeAudio(text: String, mine: Long): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val voice = DjSettings.voiceCloudVoice(platform).takeIf { it.contains("Neural") } ?: DjSettings.EDGE_DEFAULT_VOICE
            val locale = voice.split("-").let { if (it.size >= 2) "${it[0]}-${it[1]}" else "en-US" }
            val audio = ByteArrayOutputStream()
            val done = AtomicBoolean(false)
            var wsRef: WebSocket? = null

            fun finish(ok: Boolean) {
                if (!done.compareAndSet(false, true)) return
                runCatching { wsRef?.close(1000, null) }
                if (ok && audio.size() > 512 && synchronized(lock) { mine == generation }) {
                    cont.resumeWith(Result.success(audio.toByteArray()))
                } else {
                    cont.resumeWith(Result.success(null))
                }
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("X-Timestamp:${edgeDateStr()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}")
                    val reqid = UUID.randomUUID().toString().replace("-", "")
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$locale'>" +
                        "<voice name='$voice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>${xmlEscape(text)}</prosody></voice></speak>"
                    webSocket.send("X-RequestId:$reqid\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${edgeDateStr()}Z\r\nPath:ssml\r\n\r\n$ssml")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) finish(true)
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val b = bytes.toByteArray()
                    if (b.size < 2) return
                    val hlen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                    val start = 2 + hlen
                    if (start > b.size) return
                    val header = String(b, 2, hlen, Charsets.UTF_8)
                    if (header.contains("Path:audio")) audio.write(b, start, b.size - start)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { finish(false) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finish(audio.size() > 512) }
            }
            wsRef = edgeWsClient.newWebSocket(edgeRequest(), listener)
            cont.invokeOnCancellation { runCatching { wsRef.cancel() } }
        }

    override suspend fun probeEdge(): Long? = suspendCancellableCoroutine { cont ->
        val start = System.nanoTime()
        val done = AtomicBoolean(false)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (done.compareAndSet(false, true)) {
                    runCatching { webSocket.close(1000, null) }
                    cont.resumeWith(Result.success((System.nanoTime() - start) / 1_000_000))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (done.compareAndSet(false, true)) cont.resumeWith(Result.success(null))
            }
        }
        val ws = edgeWsClient.newWebSocket(edgeRequest(), listener)
        cont.invokeOnCancellation { runCatching { ws.cancel() } }
    }

    override suspend fun probeGoogleTranslate(): Long? = OnlineDjTts.probeGoogleTranslate(platform)

    override suspend fun probeVoice(voice: String): Long? = OnlineDjTts.probeVoice(voice)

    private fun setDucked(on: Boolean) {
        val changed = synchronized(lock) {
            if (ducked == on) false else {
                ducked = on
                true
            }
        }
        if (changed) main.launch { duck(on) }
    }

    private fun finishPreview() {
        val cb = previewOnDone ?: return
        previewOnDone = null
        main.launch { cb() }
    }

    private fun edgeSecToken(): String {
        var ticks = System.currentTimeMillis() / 1000.0
        ticks += 11644473600.0            // WIN_EPOCH
        ticks -= ticks % 300.0            // round to 5 min
        ticks *= 1e9 / 100.0              // 100 ns intervals
        val str = String.format(Locale.US, "%.0f", ticks) + EDGE_TRUSTED
        return MessageDigest.getInstance("SHA-256")
            .digest(str.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02X".format(it) }
    }

    private fun edgeDateStr(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;")

    private fun edgeUrl(): String =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
        "?TrustedClientToken=$EDGE_TRUSTED&Sec-MS-GEC=${edgeSecToken()}&Sec-MS-GEC-Version=$EDGE_VERSION"

    private fun edgeRequest(): Request = Request.Builder().url(edgeUrl())
        .header("Origin", EDGE_ORIGIN).header("User-Agent", EDGE_UA)
        .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
        .header("Accept-Language", "en-US,en;q=0.9").build()

    private companion object {
        const val TAG = "DjVoice"
        const val EDGE_TRUSTED = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val EDGE_VERSION = "1-143.0.3650.75"
        const val EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        const val EDGE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    }
}
