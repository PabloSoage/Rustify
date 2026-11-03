// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/AppServices.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.varuna.rustify.bridge.DownloadTask
import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's features that each platform does its own way, for the shared screens (docs
 * serie-4.x/26): Android's are the objects it always had (`BpmAnalyzer` over MediaCodec,
 * `DownloadManager` over the Storage Access Framework), handed in by the app; the desktop answers
 * what it can and says, through [can], what it cannot, so a screen explains a missing feature
 * instead of losing the button.
 */
interface AppServices {
    fun can(feature: Feature): Boolean

    // Tempo, measured from the audio (Android decodes a window of the track with MediaCodec).
    fun bpmCached(trackId: String): Float?
    fun bpmScale(trackId: String, factor: Float): Float?
    suspend fun bpmAnalyze(trackId: String, durationMs: Long): Float?

    // Downloads to the user's folder.
    val downloads: StateFlow<List<DownloadTask>>
    val activeDownloadCount: StateFlow<Int>
    fun enqueueDownload(trackId: String, trackName: String, trackArtist: String, downloadUriStr: String, track: FullTrack?)
    fun clearCompleted()

    /**
     * The Jam dialog. Android's is over its own Jam and the desktop's over the shared JamSession
     * until the two are one (docs serie-4.x/27 §2.7), so each platform hands its own in.
     */
    @Composable
    fun JamDialog(onDismiss: () -> Unit)

    enum class Feature { BPM, DOWNLOADS }
}

val LocalAppServices = staticCompositionLocalOf<AppServices> { error("no AppServices provided") }
