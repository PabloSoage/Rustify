// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/MainViewController.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.varuna.rustify.audio.IosAudioSources
import com.varuna.rustify.bridge.FileTreeMusicScanner
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ios.IosController
import com.varuna.rustify.jam.IosJamCore
import com.varuna.rustify.jam.JamSession
import com.varuna.rustify.player.EnginePlayerController
import com.varuna.rustify.player.LocalPlayer
import com.varuna.rustify.ui.theme.RustifyTheme
import com.varuna.rustify.util.IosLogBook
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/** The links the system hands the app (`onOpenURL`), for the root to open once it is up. */
object IosLinks {
    private val pending = Channel<String>(Channel.UNLIMITED)
    val intents = pending.receiveAsFlow()

    fun offer(link: String) {
        pending.trySend(link)
    }
}

/**
 * The phone's app on iOS, the same [RustifyApp] the phone and the desktop draw. The Swift side makes
 * [controller], and keeps what is plain Swift over it: the audio session and the lock screen.
 */
fun MainViewController(controller: IosController): UIViewController = ComposeUIViewController {
    val platform = remember {
        IosLogBook.install()
        IosFolders.restore()
        IosAppPlatform(controller.secrets).also { IosFolders.seedDefaults(it.prefs()) }
    }
    val player = remember {
        EnginePlayerController(
            controller.player,
            controller.scope,
            platform,
            sources = IosAudioSources.install(platform.prefs()),
            output = IosOutput.name,
            refreshOutput = { IosOutput.refresh() },
        )
    }
    val jam = remember {
        JamSession(controller.scope, IosJamCore, controller.player, platform.prefs(), UIDevice.currentDevice.name)
    }
    val services = remember {
        // While the DJ speaks the music goes down to about a fifth, as on the phone, and back.
        var volumeBeforeVoice = 1f
        IosAppServices(platform, jam, onVoice = { speaking ->
            if (speaking) {
                volumeBeforeVoice = controller.player.ui.value.volume
                controller.setVolume(volumeBeforeVoice * 0.22f)
            } else {
                controller.setVolume(volumeBeforeVoice)
            }
        })
    }
    // The language picked in Settings ("system" = the phone's), the phone's `app_language`.
    var language by remember {
        val code = platform.prefs().getString("app_language") ?: IosLocale.SYSTEM
        IosLocale.install(code)
        mutableStateOf(code)
    }
    val shell = remember {
        IosAppShell(onLanguage = { code ->
            IosLocale.apply(code)
            NativeEngine.setLanguageNative(IosLocale.languageCode)
            language = code
        })
    }
    val ready by controller.ready.collectAsState()

    CompositionLocalProvider(
        LocalAppPlatform provides platform,
        LocalAppServices provides services,
        LocalPlayer provides player,
    ) {
        // The repository the screens read, created once the core knows its folders.
        val repo = remember(ready) {
            if (ready) SpotifyRepository(platform, FileTreeMusicScanner(::scaledCover, IosFolders::folderOf)) else null
        }
        key(language) {
            RememberStringEnvironment()
            RustifyTheme {
                Box {
                    if (repo != null) RustifyApp(spotifyRepo = repo, shell = shell, intentFlow = IosLinks.intents)
                    Toasts(platform.toasts, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}
