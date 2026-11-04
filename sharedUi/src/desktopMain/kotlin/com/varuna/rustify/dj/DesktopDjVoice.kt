// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/dj/DesktopDjVoice.kt
package com.varuna.rustify.dj

import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.SystemSpeech
import com.varuna.rustify.ui.AppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * [DjVoiceOutput] on the desktop: the system's own voice ([SystemSpeech]), the desktop's "native"
 * engine, as Android's TextToSpeech is the phone's. It reads the same settings (voice on or off, its
 * language, a chosen voice) and, like the phone, lowers the music while it speaks: [duck] is told
 * true when a phrase starts and false when the last one ends.
 *
 * A new phrase replaces the one being spoken (the phone's QUEUE_FLUSH). The online voices the phone
 * also has (Edge, Google Translate, Pollinations, an OpenAI endpoint) play an MP3, which the desktop
 * has no player for outside the engine yet, so they are not offered.
 */
class DesktopDjVoice(
    private val platform: AppPlatform,
    private val duck: (Boolean) -> Unit = {},
    private val speech: SystemSpeech? = SystemSpeech.find(),
) : DjVoiceOutput {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Any()

    /** Bumped by every phrase and every stop: a process that is not the latest one is done. */
    private var generation = 0L
    private var process: Process? = null
    private var ducked = false

    @Volatile
    private var previewOnDone: (() -> Unit)? = null

    /** Whether this computer has a speech command at all. */
    val isAvailable: Boolean get() = speech != null

    init {
        Log.i(TAG, "system voice: ${speech?.let { "${it.kind} (${it.executable})" } ?: "none on PATH"}")
    }

    override val engines: List<String> = listOf("native")

    override fun init() = Unit

    override fun refreshConfig() = Unit

    override fun speak(text: String, force: Boolean) {
        if ((!DjSettings.voiceEnabled(platform) && !force) || text.isBlank()) return
        val engine = speech
        if (engine == null) {
            finishPreview()
            return
        }
        val language = DjSettings.voiceLanguage(platform).ifBlank { Locale.getDefault().language }
        val voice = DjSettings.voiceNativeName(platform)
        val mine = synchronized(lock) {
            generation++
            process?.destroy()
            process = null
            generation
        }
        io.launch {
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
                return@launch
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
    }

    override fun preview(onDone: (() -> Unit)?) {
        previewOnDone = onDone
        speak(DjPhrases.previewPhrase(DjSettings.voiceLanguage(platform)), force = true)
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            process?.destroy()
            process = null
        }
        setDucked(false)
    }

    override fun queryVoices(langCode: String, onResult: (List<Pair<String, String>>) -> Unit) {
        val engine = speech
        io.launch {
            val voices = engine?.voices(langCode).orEmpty()
            withContext(Dispatchers.Main) { onResult(voices) }
        }
    }

    // Not offered on the desktop (see the class comment).
    override suspend fun probeEdge(): Long? = null
    override suspend fun probeGoogleTranslate(): Long? = null
    override suspend fun probeVoice(voice: String): Long? = null

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

    private companion object {
        const val TAG = "DjVoice"
    }
}
