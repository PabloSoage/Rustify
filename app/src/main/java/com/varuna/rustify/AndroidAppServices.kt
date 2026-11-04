package com.varuna.rustify

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.varuna.rustify.audio.BpmAnalyzer
import com.varuna.rustify.audio.YouTubeVideoResolver
import com.varuna.rustify.bridge.DownloadManager
import com.varuna.rustify.bridge.DownloadTask
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LyricsChain
import com.varuna.rustify.bridge.LyricsRepository
import com.varuna.rustify.bridge.LyricsTranslator
import com.varuna.rustify.dj.DjAutoController
import com.varuna.rustify.player.AudioPlayerService
import com.varuna.rustify.player.Jam
import com.varuna.rustify.ui.AppServices
import com.varuna.rustify.ui.LyricsTranslation
import com.varuna.rustify.ui.screens.CanvasVideoPlayer
import com.varuna.rustify.ui.screens.YouTubeVideoPlayer
import kotlinx.coroutines.flow.StateFlow

/** [AppServices] over the objects the phone always had; everything is available here. */
class AndroidAppServices(context: Context) : AppServices {
    private val app = context.applicationContext

    override fun can(feature: AppServices.Feature): Boolean = true

    override fun bpmCached(trackId: String): Float? = BpmAnalyzer.cached(app, trackId)
    override fun bpmScale(trackId: String, factor: Float): Float? = BpmAnalyzer.scale(app, trackId, factor)
    override suspend fun bpmAnalyze(trackId: String, durationMs: Long): Float? =
        BpmAnalyzer.analyze(app, trackId, durationMs)

    override val downloads: StateFlow<List<DownloadTask>> get() = DownloadManager.downloads
    override val activeDownloadCount: StateFlow<Int> get() = DownloadManager.activeDownloadCount

    override fun enqueueDownload(trackId: String, trackName: String, trackArtist: String, downloadUriStr: String, track: FullTrack?) =
        DownloadManager.enqueueDownload(
            context = app,
            trackId = trackId,
            trackName = trackName,
            trackArtist = trackArtist,
            downloadUriStr = downloadUriStr,
            track = track,
        )

    override fun clearCompleted() = DownloadManager.clearCompleted()

    override val lyrics: LyricsChain get() = LyricsRepository.chain(app)

    override suspend fun translateLyrics(key: String, lines: List<String>, onProgress: (LyricsTranslation) -> Unit): LyricsTranslation =
        LyricsTranslator.translate(key, lines, onProgress = onProgress)

    override fun translationTarget(): String = LyricsTranslator.targetLanguage()

    override suspend fun resolveVideo(youtubeId: String, maxQuality: Boolean): Pair<String, String?>? =
        YouTubeVideoResolver.resolve(youtubeId, maxQuality)

    @Composable
    override fun CanvasVideo(url: String, fitWidth: Boolean) = CanvasVideoPlayer(url, fitWidth)

    @Composable
    override fun PlayerVideo(video: Pair<String, String?>) {
        YouTubeVideoPlayer(video, AudioPlayerService.getInstance(app))
    }

    @Composable
    override fun JamDialog(onDismiss: () -> Unit) = com.varuna.rustify.ui.components.JamDialog(onDismiss = onDismiss)

    @Composable
    override fun inJam(): Boolean = Jam.ui.collectAsState().value.role != Jam.Role.NONE

    @Composable
    override fun djActive(): Boolean = DjAutoController.state.collectAsState().value != null

    override fun djNext() = DjAutoController.next()

    override val djVoice: com.varuna.rustify.dj.DjVoiceOutput = com.varuna.rustify.dj.AndroidDjVoice(app)

    override fun localDjProvider(): com.varuna.rustify.dj.DjProvider = com.varuna.rustify.dj.LocalDjProvider()

    // Gemini Nano, loaded while the user is still choosing: 12.5 s cold against 6.9 s warm, measured on
    // a POCO F8 Ultra.
    override suspend fun warmUpLocalDj() {
        com.varuna.rustify.dj.OnDeviceModel.init(app)
        if (com.varuna.rustify.dj.OnDeviceModel.refresh() == com.varuna.rustify.dj.OnDeviceModel.Status.READY) {
            com.varuna.rustify.dj.OnDeviceModel.warmup()
        }
    }

    @Composable
    override fun rememberDjListener(): com.varuna.rustify.dj.DjListener = com.varuna.rustify.dj.rememberAndroidDjListener()

    @Composable
    override fun LocalDjSettings() = com.varuna.rustify.ui.screens.NanoModelPanel()

    override val logs: com.varuna.rustify.util.LogBook get() = com.varuna.rustify.util.LogCapture
}
