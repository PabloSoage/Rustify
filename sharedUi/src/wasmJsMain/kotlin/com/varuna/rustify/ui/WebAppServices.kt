// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/ui/WebAppServices.kt
package com.varuna.rustify.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import com.varuna.rustify.audio.asStreamSources
import com.varuna.rustify.bridge.DownloadTask
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LyricsChain
import com.varuna.rustify.dj.ChatModel
import com.varuna.rustify.dj.DjAutoController
import com.varuna.rustify.dj.DjListener
import com.varuna.rustify.dj.DjProvider
import com.varuna.rustify.dj.DjVoiceOutput
import com.varuna.rustify.dj.HeuristicDjProvider
import com.varuna.rustify.dj.WebDjVoice
import com.varuna.rustify.jam.JamSession
import com.varuna.rustify.platform.ServerKeyValueStore
import com.varuna.rustify.player.AudioSettings
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.feature_not_on_platform
import com.varuna.rustify.ui.screens.JamSection
import com.varuna.rustify.util.LogBook
import com.varuna.rustify.util.WebLogBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

internal class WebAudioSettings(platform: AppPlatform) : AudioSettings(platform.prefs()) {
    override suspend fun refreshOutput() = Unit

    // The browser chooses where the sound goes and does not say: AutoEq's profile is this browser's.
    override fun currentOutput(): Output = Output(OUTPUT_KEY, "Browser")

    companion object {
        const val OUTPUT_KEY = "web:browser"
    }
}

/**
 * [AppServices] in a browser. Lyrics, the DJ and the settings are the shared ones. What a page has no way
 * to do, [can] says so and the screens with it: tempo, and downloads where the server has no folder
 * for them (`--download-dir`): a download lands on the server, not on the listener's device.
 */
/** The server's folder for downloads, as the interface names it (server/src/files.rs). */
internal const val DOWNLOADS = "/downloads"

class WebAppServices(
    private val platform: AppPlatform,
    private val jam: JamSession,
    onVoice: (speaking: Boolean) -> Unit,
) : AppServices {
    override fun can(feature: AppServices.Feature): Boolean = when (feature) {
        AppServices.Feature.DJ, AppServices.Feature.DJ_VOICE, AppServices.Feature.TRANSLATION, AppServices.Feature.VIDEO -> true
        AppServices.Feature.DOWNLOADS -> serverDownloads
        else -> false
    }

    /** Whether the server was given a folder for downloads: `/downloads` exists only then. Asked once. */
    private val serverDownloads: Boolean by lazy { runCatching { appFileAt(DOWNLOADS).isFolder() }.getOrDefault(false) }

    init {
        // The track menu offers a download where a folder is set: on the web that folder is the server's.
        if (serverDownloads && platform.prefs().getString("download_directory").isNullOrBlank()) {
            platform.prefs().putString("download_directory", DOWNLOADS)
        }
    }

    /** The phone's downloads, on the server: the player's sources, and the one folder the server offers. */
    private val downloader by lazy {
        com.varuna.rustify.bridge.EngineDownloads(
            platform,
            com.varuna.rustify.engine.StreamResolver(
                com.varuna.rustify.engine.WebPlayerBackend,
                com.varuna.rustify.audio.AudioSourceRegistry.asStreamSources(),
            ),
            folderOf = { appFileAt(DOWNLOADS) },
        )
    }

    override fun bpmCached(trackId: String): Float? = null
    override fun bpmScale(trackId: String, factor: Float): Float? = null
    override suspend fun bpmAnalyze(trackId: String, durationMs: Long): Float? = null

    override val downloads: StateFlow<List<DownloadTask>> get() = downloader.downloads
    override val activeDownloadCount: StateFlow<Int> get() = downloader.activeDownloadCount

    // Whatever folder the settings name, a browser's downloads go to the server's.
    override fun enqueueDownload(trackId: String, trackName: String, trackArtist: String, downloadUriStr: String, track: FullTrack?) {
        if (serverDownloads) downloader.enqueue(trackId, trackName, trackArtist, DOWNLOADS, track)
    }

    override fun clearCompleted() = downloader.clearCompleted()

    override val lyrics: LyricsChain by lazy { LyricsChain(platform.prefs("lyrics"), readLocal = { null }) }

    override val audioSettings: AudioSettings = WebAudioSettings(platform)

    // The desktop's sign-in with the answer brought back to this server (WebDriveAuth); only with the
    // OAuth client the build was given, as on the desktop.
    override val driveAuth: com.varuna.rustify.sync.DriveAuth? =
        com.varuna.rustify.sync.WebDriveAuth(platform).takeIf { it.isConfigured(com.varuna.rustify.sync.DriveAuth.BROWSER) }

    private val chat = com.varuna.rustify.dj.DjServiceChatModel(platform, device = "this browser")
    private val translator = ModelLyricsTranslator(client = { chat.client() })

    override suspend fun translateLyrics(key: String, lines: List<String>, onProgress: (LyricsTranslation) -> Unit): LyricsTranslation =
        translator.translate(key, lines, translationTarget(), onProgress)

    override fun translationTarget(): String = WebLocale.tag.substringBefore('-')

    override suspend fun resolveVideo(youtubeId: String, maxQuality: Boolean): Pair<String, String?>? =
        com.varuna.rustify.bridge.resolveVideoFromCore(youtubeId)

    @Composable
    override fun CanvasVideo(url: String, fitWidth: Boolean) = WebCanvasVideo(url, fitWidth)

    @Composable
    override fun PlayerVideo(video: Pair<String, String?>) = WebPlayerVideo(video.first)

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

    override val djVoice: DjVoiceOutput = WebDjVoice(platform, onVoice)

    override fun localDjProvider(): DjProvider = HeuristicDjProvider()

    override suspend fun warmUpLocalDj() = Unit

    @Composable
    override fun rememberDjListener(): DjListener = NoMicrophone

    @Composable
    override fun LocalDjSettings() {
        Text(stringResource(Res.string.feature_not_on_platform), color = androidx.compose.ui.graphics.Color.Gray)
    }

    override fun chatModel(): ChatModel = chat

    override val logs: LogBook get() = WebLogBook

    override val travel: com.varuna.rustify.travel.TravelPlatform get() = com.varuna.rustify.travel.WebTravelPlatform
}

private object NoMicrophone : DjListener {
    override fun isAvailable(): Boolean = false
    override fun listen(languageTag: String, onResult: (String) -> Unit, onError: () -> Unit) = onError()
    override fun stop() = Unit
}
