package com.varuna.rustify

import android.content.Context
import com.varuna.rustify.audio.BpmAnalyzer
import com.varuna.rustify.bridge.DownloadManager
import com.varuna.rustify.bridge.DownloadTask
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.ui.AppServices
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

    @androidx.compose.runtime.Composable
    override fun JamDialog(onDismiss: () -> Unit) = com.varuna.rustify.ui.components.JamDialog(onDismiss = onDismiss)
}
