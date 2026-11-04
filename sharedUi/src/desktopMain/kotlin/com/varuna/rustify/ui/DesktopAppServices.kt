// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopAppServices.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.varuna.rustify.bridge.DownloadTask
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LocalMusicScanner
import com.varuna.rustify.bridge.LocalScanRequest
import com.varuna.rustify.bridge.LyricsChain
import com.varuna.rustify.dj.DjAutoController
import com.varuna.rustify.dj.DjListener
import com.varuna.rustify.dj.DjProvider
import com.varuna.rustify.dj.DjVoiceOutput
import com.varuna.rustify.dj.HeuristicDjProvider
import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.FileStore
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/**
 * [AppServices] on the desktop. Tempo and downloads are Android's today (MediaCodec, the Storage
 * Access Framework), and so are translation and video; [can] says so and the screens show them as
 * unavailable. The DJ is the shared one, without a voice, a microphone or a local model yet.
 */
class DesktopAppServices(
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
    // value (8 KB).
    override val lyrics: LyricsChain by lazy { LyricsChain(FileStore(File(AppDirs.data, "lyrics"))) }

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

    // Not yet: the system's voice, a microphone and a model on this computer (docs serie-4.x/27 §2.8).
    override val djVoice: DjVoiceOutput = object : DjVoiceOutput {
        override fun init() = Unit
        override fun speak(text: String, force: Boolean) = Unit
        override fun stop() = Unit
    }

    override fun localDjProvider(): DjProvider = HeuristicDjProvider()

    override suspend fun warmUpLocalDj() = Unit

    @Composable
    override fun rememberDjListener(): DjListener = NoMicrophone

    override val logs: com.varuna.rustify.util.LogBook get() = com.varuna.rustify.util.DesktopLogBook

    override fun can(feature: AppServices.Feature): Boolean = feature == AppServices.Feature.DJ

    override fun bpmCached(trackId: String): Float? = null
    override fun bpmScale(trackId: String, factor: Float): Float? = null
    override suspend fun bpmAnalyze(trackId: String, durationMs: Long): Float? = null

    override val downloads: StateFlow<List<DownloadTask>> = MutableStateFlow(emptyList())
    override val activeDownloadCount: StateFlow<Int> = MutableStateFlow(0)
    override fun enqueueDownload(trackId: String, trackName: String, trackArtist: String, downloadUriStr: String, track: FullTrack?) = Unit
    override fun clearCompleted() = Unit
}

/**
 * The desktop's local scan. Not written yet: a folder here is a path, and reading tags needs the
 * core's tag reader rather than Android's MediaMetadataRetriever. Answers nothing, and says so once.
 */
class DesktopLocalMusicScanner : LocalMusicScanner {
    override suspend fun scan(request: LocalScanRequest): List<FullTrack> {
        if (request.dirs.isNotEmpty()) Log.i("LocalMusic", "local folders are not scanned on the desktop yet")
        return emptyList()
    }
}

/** No speech recognition on the desktop: the DJ screen says so when the microphone is pressed. */
private object NoMicrophone : DjListener {
    override fun isAvailable(): Boolean = false
    override fun listen(languageTag: String, onResult: (String) -> Unit, onError: () -> Unit) = onError()
    override fun stop() = Unit
}
