// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/AppServices.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.varuna.rustify.bridge.DownloadTask
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LyricsChain
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
     * The lyrics providers, their order and the per-song choice: the shared [LyricsChain] over this
     * platform's settings, one for the process so its caches outlive a screen.
     */
    val lyrics: LyricsChain

    /**
     * Translates lyrics [lines] into the system's language (Android: ML Kit, on the device).
     * [onProgress] gets partial outcomes (a model downloading, the lines done so far). Without
     * [Feature.TRANSLATION] it answers [LyricsTranslation.Failed] with [LyricsTranslation.UNAVAILABLE].
     */
    suspend fun translateLyrics(key: String, lines: List<String>, onProgress: (LyricsTranslation) -> Unit): LyricsTranslation

    /** The language [translateLyrics] translates into, as a language code. */
    fun translationTarget(): String

    /** A YouTube recording's video and, when separate, its audio stream; null when there is none. */
    suspend fun resolveVideo(youtubeId: String, maxQuality: Boolean): Pair<String, String?>?

    /** A Spotify Canvas: the short looping clip behind the cover art, muted. */
    @Composable
    fun CanvasVideo(url: String, fitWidth: Boolean)

    /** The playing song's video ([resolveVideo]), with its own audio in place of the player's. */
    @Composable
    fun PlayerVideo(video: Pair<String, String?>)

    /**
     * The Jam dialog. Android's is over its own Jam and the desktop's over the shared JamSession
     * until the two are one (docs serie-4.x/27 §2.7), so each platform hands its own in.
     */
    @Composable
    fun JamDialog(onDismiss: () -> Unit)

    /** Whether this device is in a Jam right now (the player's Jam button goes green). */
    @Composable
    fun inJam(): Boolean

    /** Whether an autonomous DJ session is running (its button shows on the player). */
    @Composable
    fun djActive(): Boolean

    /** Asks the running DJ for the next mood. */
    fun djNext()

    /** The DJ's voice, one for the process (Android: TextToSpeech and the cloud voices). */
    val djVoice: com.varuna.rustify.dj.DjVoiceOutput

    /** The provider behind the DJ's "local" mode (Android: Gemini Nano, on the phone's NPU). */
    fun localDjProvider(): com.varuna.rustify.dj.DjProvider

    /** Gets the local mode's model ready while the DJ screen is open, so the first answer is not the slow one. */
    suspend fun warmUpLocalDj()

    /** The microphone, for a spoken request to the DJ; remembered by the screen that listens. */
    @Composable
    fun rememberDjListener(): com.varuna.rustify.dj.DjListener

    /** The app's own log, for the log viewer. */
    val logs: com.varuna.rustify.util.LogBook

    enum class Feature { BPM, DOWNLOADS, TRANSLATION, VIDEO, DJ }
}

/** What translating lyrics came to; see [AppServices.translateLyrics]. */
sealed class LyricsTranslation {
    data class Translated(val from: String, val lines: List<String>) : LyricsTranslation()

    /**
     * Nothing to translate: already in the target language, a language the translator cannot
     * translate, romanised text (`ja-Latn`: romaji is not what the Japanese model reads) or `und`.
     */
    data class NotNeeded(val language: String) : LyricsTranslation()
    data class Failed(val reason: String) : LyricsTranslation()

    /** Progress only: the language's model is being downloaded first (~30 MB, once). */
    data class Downloading(val language: String) : LyricsTranslation()

    companion object {
        /** [Failed.reason] when this platform has no translator. */
        const val UNAVAILABLE = "unavailable"
    }
}

val LocalAppServices = staticCompositionLocalOf<AppServices> { error("no AppServices provided") }
