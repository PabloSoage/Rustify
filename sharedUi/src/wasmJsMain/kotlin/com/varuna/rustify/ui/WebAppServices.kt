// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/ui/WebAppServices.kt
package com.varuna.rustify.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
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

private class WebAudioSettings(platform: AppPlatform) : AudioSettings(platform.prefs()) {
    override suspend fun refreshOutput() = Unit

    // The browser chooses where the sound goes and does not say.
    override fun currentOutput(): Output? = null
}

/**
 * [AppServices] in a browser. Lyrics, the DJ and the settings are the shared ones. What a page has no way
 * to do yet, [can] says so and the screens with it: tempo, downloads to the listener's device, video.
 */
class WebAppServices(
    private val platform: AppPlatform,
    private val jam: JamSession,
    onVoice: (speaking: Boolean) -> Unit,
) : AppServices {
    override fun can(feature: AppServices.Feature): Boolean = when (feature) {
        AppServices.Feature.DJ, AppServices.Feature.DJ_VOICE -> true
        else -> false
    }

    override fun bpmCached(trackId: String): Float? = null
    override fun bpmScale(trackId: String, factor: Float): Float? = null
    override suspend fun bpmAnalyze(trackId: String, durationMs: Long): Float? = null

    override val downloads: StateFlow<List<DownloadTask>> = MutableStateFlow(emptyList())
    override val activeDownloadCount: StateFlow<Int> = MutableStateFlow(0)

    override fun enqueueDownload(trackId: String, trackName: String, trackArtist: String, downloadUriStr: String, track: FullTrack?) = Unit

    override fun clearCompleted() = Unit

    override val lyrics: LyricsChain by lazy { LyricsChain(platform.prefs("lyrics"), readLocal = { null }) }

    override val audioSettings: AudioSettings = WebAudioSettings(platform)

    override suspend fun translateLyrics(key: String, lines: List<String>, onProgress: (LyricsTranslation) -> Unit): LyricsTranslation =
        LyricsTranslation.Failed(LyricsTranslation.UNAVAILABLE)

    override fun translationTarget(): String = WebLocale.tag.substringBefore('-')

    override suspend fun resolveVideo(youtubeId: String, maxQuality: Boolean): Pair<String, String?>? = null

    @Composable
    override fun CanvasVideo(url: String, fitWidth: Boolean) = Unit

    @Composable
    override fun PlayerVideo(video: Pair<String, String?>) = Unit

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

    override fun chatModel(): ChatModel? = null

    override val logs: LogBook get() = WebLogBook
}

private object NoMicrophone : DjListener {
    override fun isAvailable(): Boolean = false
    override fun listen(languageTag: String, onResult: (String) -> Unit, onError: () -> Unit) = onError()
    override fun stop() = Unit
}
