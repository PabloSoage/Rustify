// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/ui/WebMain.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import com.varuna.rustify.audio.AudioSourceEnvironment
import com.varuna.rustify.audio.AudioSourceRegistry
import com.varuna.rustify.audio.StreamCacheAccess
import com.varuna.rustify.audio.asStreamSources
import com.varuna.rustify.bridge.FileTreeMusicScanner
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.engine.WebPlayerBackend
import com.varuna.rustify.jam.JamSession
import com.varuna.rustify.jam.JvmJamCore
import com.varuna.rustify.player.EnginePlayerController
import com.varuna.rustify.player.LocalPlayer
import com.varuna.rustify.ui.theme.RustifyTheme
import com.varuna.rustify.util.WebLogBook
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

private const val VERSION = "5.6.4"

/** The phone's app in a browser, the same [RustifyApp] the phone and the desktop draw (docs serie-4.x/28). */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    WebLogBook.install()
    val scope = MainScope()
    val platform = WebAppPlatform(VERSION)
    // The interface's language, for what the core asks Spotify and YouTube in: the server starts with its own.
    platform.prefs().getString("app_language")?.takeIf { it != "system" }?.let { WebLocale.tag = it }
    NativeEngine.setLanguageNative(WebLocale.tag.substringBefore('-'))
    coil3.SingletonImageLoader.setSafe {
        newRustifyImageLoader(coil3.PlatformContext.INSTANCE, platform.cacheDir, platform.prefs()) {
            // The browser keeps what it fetched; a page has no folder for Coil's own cache.
            diskCache(null)
            components {
                add(coil3.network.ktor3.KtorNetworkFetcherFactory(com.varuna.rustify.platform.httpClient()))
                // A local track's cover is a file on the server.
                add(coil3.map.Mapper<String, String> { data, _ ->
                    if (data.startsWith("file://")) com.varuna.rustify.platform.rawUrlOf(data.removePrefix("file://")) else null
                })
            }
        }
    }
    AudioSourceRegistry.initialize(
        // AAC: the one format every browser's <audio> reads, Safari's included.
        AudioSourceEnvironment(settings = platform.prefs(), streamCache = StreamCacheAccess.None, decodesOpus = false),
        ytDlp = null,
    )
    // The music the server was told to offer is local music here, and it decides: no folder can be picked.
    platform.prefs().putStringSet("local_music_directories", AppFile("/music").listChildren().map { it.fullPath }.toSet())
    val queue = QueuePlayer(scope, WebPlayerBackend, platform.prefs()).also { it.restore() }
    scope.launch { queue.start() }
    val player = EnginePlayerController(
        queue,
        scope,
        platform,
        sources = AudioSourceRegistry.asStreamSources(),
        // One output, the browser: AutoEq keeps its profile under it (WebAudioSettings).
        output = MutableStateFlow(WebAudioSettings.OUTPUT_KEY),
        refreshOutput = {},
    )
    val jam = JamSession(scope, JvmJamCore, queue, platform.prefs(), "Browser")
    var volumeBeforeVoice = 1f
    val services = WebAppServices(platform, jam, onVoice = { speaking ->
        if (speaking) {
            volumeBeforeVoice = queue.ui.value.volume
            queue.setVolume(volumeBeforeVoice * 0.22f)
        } else {
            queue.setVolume(volumeBeforeVoice)
        }
    })
    ComposeViewport {
        // The canvas draws with Compose's own font, which has no kana or kanji: with the browser in
        // Japanese the whole interface was empty boxes, and so was every Japanese title in any
        // language. Noto Sans JP (OFL, web-only resources) is preloaded as the fallback before
        // anything is drawn, since text laid out before it would keep its boxes.
        val fonts = androidx.compose.ui.platform.LocalFontFamilyResolver.current
        var fontsReady by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching {
                val bytes = com.varuna.rustify.ui.resources.Res.readBytes("files/NotoSansJP-Regular.otf")
                fonts.preload(
                    androidx.compose.ui.text.font.FontFamily(
                        androidx.compose.ui.text.platform.Font("NotoSansJP", bytes)
                    )
                )
            }.onFailure { com.varuna.rustify.platform.Log.w("WebMain", "no Japanese font: ${it.message}") }
            fontsReady = true
        }
        if (!fontsReady) return@ComposeViewport
        var language by remember {
            mutableStateOf(platform.prefs().getString("app_language") ?: "system")
        }
        val shell = remember {
            WebAppShell(onLanguage = { code ->
                if (code != "system") WebLocale.tag = code else WebLocale.useBrowsers()
                NativeEngine.setLanguageNative(WebLocale.tag.substringBefore('-'))
                language = code
            })
        }
        CompositionLocalProvider(
            LocalAppPlatform provides platform,
            LocalAppServices provides services,
            LocalPlayer provides player,
        ) {
            val repo = remember { SpotifyRepository(platform, FileTreeMusicScanner({ it })) }
            key(language) {
                RememberStringEnvironment()
                RustifyTheme {
                    Box {
                        RustifyApp(spotifyRepo = repo, shell = shell, intentFlow = emptyFlow())
                        Toasts(platform.toasts, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}
