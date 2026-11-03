// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopAppServices.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import com.varuna.rustify.bridge.DownloadTask
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LocalMusicScanner
import com.varuna.rustify.bridge.LocalScanRequest
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [AppServices] on the desktop. Tempo and downloads are Android's today (MediaCodec, the Storage
 * Access Framework); [can] says so and the screens show them as unavailable.
 */
class DesktopAppServices(
    /** The desktop's Jam screen, shown as the dialog (desktopApp passes it; it owns the JamSession). */
    private val jamDialog: @Composable (onDismiss: () -> Unit) -> Unit = {},
) : AppServices {
    @Composable
    override fun JamDialog(onDismiss: () -> Unit) = jamDialog(onDismiss)

    override fun can(feature: AppServices.Feature): Boolean = false

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
