// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopAppServices.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.varuna.rustify.bridge.DesktopLocalLyrics
import com.varuna.rustify.bridge.DownloadTask
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LyricsChain
import com.varuna.rustify.dj.DesktopDjVoice
import com.varuna.rustify.dj.DesktopLocalDjProvider
import com.varuna.rustify.dj.DjAutoController
import com.varuna.rustify.dj.DjListener
import com.varuna.rustify.dj.DjProvider
import com.varuna.rustify.dj.DjVoiceOutput
import com.varuna.rustify.dj.LocalLlmDetector
import com.varuna.rustify.dj.LocalLlmServer
import com.varuna.rustify.dj.LocalLlmSettings
import com.varuna.rustify.dj.LocalModelManager
import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.FileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/**
 * [AppServices] on the desktop. Tempo and downloads are Android's today (MediaCodec, the Storage
 * Access Framework), and so are translation and video; [can] says so and the screens show them as
 * unavailable. The DJ is the shared one: it speaks with the system's voice, and its "local" mode is a
 * model server on this computer, one already there or Rustify's own; it has no microphone.
 */
class DesktopAppServices(
    private val platform: AppPlatform,
    /** The DJ started (true) or stopped (false) speaking: the window lowers the music meanwhile. */
    onVoice: (speaking: Boolean) -> Unit = {},
    /** The desktop's Jam screen, shown as the dialog (desktopApp passes it; it owns the JamSession). */
    private val jamDialog: @Composable (onDismiss: () -> Unit) -> Unit = {},
    /** Whether the JamSession is in a room. */
    private val inJam: @Composable () -> Boolean = { false },
) : AppServices {
    @Composable
    override fun JamDialog(onDismiss: () -> Unit) = jamDialog(onDismiss)

    @Composable
    override fun inJam(): Boolean = inJam.invoke()

    // A file rather than Preferences: the per-song choice is a JSON map that outgrows a Preferences
    // value (8 KB). A local track's own lyrics come from its .lrc or its tag, as on the phone.
    override val lyrics: LyricsChain by lazy {
        LyricsChain(FileStore(File(AppDirs.data, "lyrics")), readLocal = { DesktopLocalLyrics.read(it) })
    }

    // On-device translation is ML Kit's, Android only (docs serie-4.x/27 §2.9).
    override suspend fun translateLyrics(key: String, lines: List<String>, onProgress: (LyricsTranslation) -> Unit) =
        LyricsTranslation.Failed(LyricsTranslation.UNAVAILABLE)

    override fun translationTarget(): String = Locale.getDefault().language

    // Video and Canvas need a video player on the desktop (docs serie-4.x/27 §video): [can] says no
    // and the track screen does not offer them.
    override suspend fun resolveVideo(youtubeId: String, maxQuality: Boolean): Pair<String, String?>? = null

    @Composable
    override fun CanvasVideo(url: String, fitWidth: Boolean) = Unit

    @Composable
    override fun PlayerVideo(video: Pair<String, String?>) = Unit

    // The autonomous DJ is the shared controller, as on the phone.
    @Composable
    override fun djActive(): Boolean = DjAutoController.state.collectAsState().value != null

    override fun djNext() = DjAutoController.next()

    // The system's own voice (SAPI, `say`, Speech Dispatcher or eSpeak NG).
    private val voice = DesktopDjVoice(platform, onVoice)
    override val djVoice: DjVoiceOutput get() = voice

    /** Rustify's own llama-server, for whoever runs no other: one for the process, stopped by [close]. */
    val localModels = LocalModelManager()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Rustify's own server with the model chosen for it, started if it is on disk (never downloaded here).
    private suspend fun managedTarget(): LocalLlmSettings.Target? {
        val model = LocalLlmSettings.managedModel(platform.prefs())
        val url = localModels.ensureRunning(model) ?: return null
        return LocalLlmSettings.Target(LocalLlmServer.Kind.MANAGED, url, model.id)
    }

    // A model server already running here (Ollama, LM Studio, llama-server), else Rustify's own when
    // it is downloaded, else the heuristic.
    override fun localDjProvider(): DjProvider = DesktopLocalDjProvider(platform.prefs(), managed = { managedTarget() })

    // Rustify's own server takes a while to load its model: started while the DJ screen is open,
    // when it is the one the DJ would ask. Not waited for, as the phone does not wait for Gemini Nano.
    override suspend fun warmUpLocalDj() {
        val prefs = platform.prefs()
        val source = LocalLlmSettings.source(prefs)
        if (source != LocalLlmSettings.AUTO && source != LocalLlmServer.Kind.MANAGED.code) return
        background.launch {
            if (source == LocalLlmSettings.AUTO && LocalLlmDetector.detect().any { it.models.isNotEmpty() }) return@launch
            managedTarget()
        }
    }

    // Not yet: a microphone (docs serie-4.x/27 §2.8).
    @Composable
    override fun rememberDjListener(): DjListener = NoMicrophone

    @Composable
    override fun LocalDjSettings() = DesktopLocalDjPanel(platform, localModels)

    /** The window is closing: the model server and the voice stop with it. */
    fun close() {
        localModels.stop()
        voice.stop()
    }

    override val logs: com.varuna.rustify.util.LogBook get() = com.varuna.rustify.util.DesktopLogBook

    override fun can(feature: AppServices.Feature): Boolean = when (feature) {
        AppServices.Feature.DJ -> true
        AppServices.Feature.DJ_VOICE -> voice.isAvailable
        else -> false
    }

    override fun bpmCached(trackId: String): Float? = null
    override fun bpmScale(trackId: String, factor: Float): Float? = null
    override suspend fun bpmAnalyze(trackId: String, durationMs: Long): Float? = null

    override val downloads: StateFlow<List<DownloadTask>> = MutableStateFlow(emptyList())
    override val activeDownloadCount: StateFlow<Int> = MutableStateFlow(0)
    override fun enqueueDownload(trackId: String, trackName: String, trackArtist: String, downloadUriStr: String, track: FullTrack?) = Unit
    override fun clearCompleted() = Unit
}

/** No speech recognition on the desktop: the DJ screen says so when the microphone is pressed. */
private object NoMicrophone : DjListener {
    override fun isAvailable(): Boolean = false
    override fun listen(languageTag: String, onResult: (String) -> Unit, onError: () -> Unit) = onError()
    override fun stop() = Unit
}
