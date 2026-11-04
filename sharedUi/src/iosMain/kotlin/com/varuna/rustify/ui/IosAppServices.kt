// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/IosAppServices.kt
package com.varuna.rustify.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import com.varuna.rustify.audio.IosAudioSources
import com.varuna.rustify.bridge.DownloadTask
import com.varuna.rustify.bridge.EngineDownloads
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LocalFileLyrics
import com.varuna.rustify.bridge.LyricsChain
import com.varuna.rustify.dj.ChatModel
import com.varuna.rustify.dj.DjAutoController
import com.varuna.rustify.dj.DjListener
import com.varuna.rustify.dj.DjProvider
import com.varuna.rustify.dj.DjVoiceOutput
import com.varuna.rustify.dj.HeuristicDjProvider
import com.varuna.rustify.dj.IosDjVoice
import com.varuna.rustify.engine.IosPlayerBackend
import com.varuna.rustify.engine.StreamResolver
import com.varuna.rustify.platform.UserDefaultsStore
import com.varuna.rustify.player.AudioSettings
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.feature_not_on_platform
import com.varuna.rustify.ui.screens.JamSection
import com.varuna.rustify.jam.JamSession
import com.varuna.rustify.util.IosLogBook
import com.varuna.rustify.util.LogBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.currentRoute
import platform.Foundation.NSUserDefaults

/** The output the system plays through now, which AutoEq keeps its profile under. */
object IosOutput {
    private val _name = MutableStateFlow<String?>(null)
    val name: StateFlow<String?> = _name

    fun refresh() {
        val port = AVAudioSession.sharedInstance().currentRoute.outputs.firstOrNull() as? AVAudioSessionPortDescription
        _name.value = port?.portName
    }
}

private class IosAudioSettings(platform: AppPlatform) : AudioSettings(platform.prefs()) {
    override suspend fun refreshOutput() = IosOutput.refresh()

    override fun currentOutput(): Output? = IosOutput.name.value?.let { Output(it, it) }
}

/**
 * [AppServices] on iOS. Lyrics, the DJ and the audio settings are the shared ones; the DJ speaks with
 * the system's voice, and with no model on the phone its "local" mode is the heuristic. What is not
 * here yet ([can] says so, and the screens with it): tempo and translation. Downloads stop if
 * the system suspends the app, which only keeps playing audio in the background.
 */
class IosAppServices(
    private val platform: AppPlatform,
    private val jam: JamSession,
    onVoice: (speaking: Boolean) -> Unit,
) : AppServices {
    override fun can(feature: AppServices.Feature): Boolean = when (feature) {
        AppServices.Feature.DJ, AppServices.Feature.DJ_VOICE, AppServices.Feature.DOWNLOADS, AppServices.Feature.VIDEO -> true
        else -> false
    }

    override fun bpmCached(trackId: String): Float? = null
    override fun bpmScale(trackId: String, factor: Float): Float? = null
    override suspend fun bpmAnalyze(trackId: String, durationMs: Long): Float? = null

    // The player's sources, so the file is the recording that plays. Made here, on the main queue.
    private val downloader = EngineDownloads(
        platform,
        StreamResolver(IosPlayerBackend, IosAudioSources.install(platform.prefs())),
        IosFolders::folderOf,
    )

    override val downloads: StateFlow<List<DownloadTask>> get() = downloader.downloads
    override val activeDownloadCount: StateFlow<Int> get() = downloader.activeDownloadCount

    // downloadUriStr is the folder from Settings (download_directory): a kept folder, or the Documents.
    override fun enqueueDownload(trackId: String, trackName: String, trackArtist: String, downloadUriStr: String, track: FullTrack?) =
        downloader.enqueue(trackId, trackName, trackArtist, downloadUriStr, track)

    override fun clearCompleted() = downloader.clearCompleted()

    // Defaults of its own: the per-song choice is a map that grows with the library.
    override val lyrics: LyricsChain by lazy {
        LyricsChain(
            UserDefaultsStore(NSUserDefaults(suiteName = "com.varuna.rustify.lyrics")),
            readLocal = { LocalFileLyrics.read(it) },
        )
    }

    override val audioSettings: AudioSettings = IosAudioSettings(platform)

    override suspend fun translateLyrics(key: String, lines: List<String>, onProgress: (LyricsTranslation) -> Unit): LyricsTranslation =
        LyricsTranslation.Failed(LyricsTranslation.UNAVAILABLE)

    override fun translationTarget(): String = IosLocale.languageCode

    override suspend fun resolveVideo(youtubeId: String, maxQuality: Boolean): Pair<String, String?>? =
        resolveVideoFromCore(youtubeId)

    @Composable
    override fun CanvasVideo(url: String, fitWidth: Boolean) = IosCanvasVideo(url, fitWidth)

    @Composable
    override fun PlayerVideo(video: Pair<String, String?>) = IosPlayerVideo(video.first)

    @Composable
    override fun JamDialog(onDismiss: () -> Unit) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                JamSection(jam)
            }
        }
    }

    @Composable
    override fun inJam(): Boolean = jam.ui.collectAsState().value.role != JamSession.Role.NONE

    @Composable
    override fun djActive(): Boolean = DjAutoController.state.collectAsState().value != null

    override fun djNext() = DjAutoController.next()

    override val djVoice: DjVoiceOutput = IosDjVoice(platform, onVoice)

    override fun localDjProvider(): DjProvider = HeuristicDjProvider()

    override suspend fun warmUpLocalDj() = Unit

    @Composable
    override fun rememberDjListener(): DjListener = NoMicrophone

    @Composable
    override fun LocalDjSettings() {
        Text(stringResource(Res.string.feature_not_on_platform), color = androidx.compose.ui.graphics.Color.Gray)
    }

    override fun chatModel(): ChatModel? = null

    override val logs: LogBook get() = IosLogBook
}

/** No speech recognition here yet: the DJ screen says so when the microphone is pressed. */
private object NoMicrophone : DjListener {
    override fun isAvailable(): Boolean = false
    override fun listen(languageTag: String, onResult: (String) -> Unit, onError: () -> Unit) = onError()
    override fun stop() = Unit
}
