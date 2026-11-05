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

private const val VERSION = "5.0.0"

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
            components { add(coil3.network.ktor3.KtorNetworkFetcherFactory(com.varuna.rustify.platform.httpClient())) }
        }
    }
    AudioSourceRegistry.initialize(
        // AAC: the one format every browser's <audio> reads, Safari's included.
        AudioSourceEnvironment(settings = platform.prefs(), streamCache = StreamCacheAccess.None, decodesOpus = false),
        ytDlp = null,
    )
    val queue = QueuePlayer(scope, WebPlayerBackend, platform.prefs()).also { it.restore() }
    scope.launch { queue.start() }
    val player = EnginePlayerController(
        queue,
        scope,
        platform,
        sources = AudioSourceRegistry.asStreamSources(),
        output = MutableStateFlow(null),
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
        var language by remember {
            mutableStateOf(platform.prefs().getString("app_language") ?: "system")
        }
        val shell = remember {
            WebAppShell(onLanguage = { code ->
                if (code != "system") WebLocale.tag = code
                NativeEngine.setLanguageNative(WebLocale.tag.substringBefore('-'))
                language = code
            })
        }
        CompositionLocalProvider(
            LocalAppPlatform provides platform,
            LocalAppServices provides services,
            LocalPlayer provides player,
        ) {
            val repo = remember { SpotifyRepository(platform, FileTreeMusicScanner({ it }, { null })) }
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
