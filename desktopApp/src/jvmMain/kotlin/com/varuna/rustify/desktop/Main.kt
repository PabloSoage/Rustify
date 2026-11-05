// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/Main.kt
package com.varuna.rustify.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.ui.theme.RustifyTheme
import com.varuna.rustify.engine.JvmPlayerBackend
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.jam.JamSession
import com.varuna.rustify.jam.JvmJamCore
import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.FileStore
import com.varuna.rustify.platform.DesktopStores
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.defaultLanguage
import com.varuna.rustify.ui.DesktopLinks
import com.varuna.rustify.ui.DesktopSettings
import com.varuna.rustify.ui.DesktopTray
import com.varuna.rustify.ui.SingleInstance
import com.varuna.rustify.ui.UrlScheme
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

// The computer's locale, before Settings' language replaces the JVM's default.
private val systemLocale: java.util.Locale = java.util.Locale.getDefault()

private fun applyLocale(code: String) {
    java.util.Locale.setDefault(if (code == "system") systemLocale else java.util.Locale.forLanguageTag(code))
}

fun main(args: Array<String>) {
    // One Rustify per data folder. A second launch (the browser opening a rustify:// link, a link on
    // the command line) hands its arguments to the first and leaves, before loading anything.
    val instance = SingleInstance.claim(AppDirs.data)
    if (instance == null && !SingleInstance.unlockable) {
        val forwarded = SingleInstance.forward(AppDirs.data, args.toList())
        if (!forwarded) System.err.println("Rustify is already running for ${AppDirs.data} and did not answer.")
        exitProcess(if (forwarded) 0 else 1)
    }

    // Before anything names NativeEngine: on the desktop the library is not on java.library.path.
    CoreLoader.install()
    // The log viewer's buffer, from the first line on.
    com.varuna.rustify.util.DesktopLogBook.install()
    // The macOS title bar in dark, as Windows' (WindowStyle): AWT reads it before the first window.
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")

    coil3.SingletonImageLoader.setSafe {
        com.varuna.rustify.ui.newRustifyImageLoader(
            coil3.PlatformContext.INSTANCE,
            AppDirs.cache,
            DesktopStores.named(com.varuna.rustify.ui.AppPlatform.SETTINGS),
        )
    }

    // The links this launch was for, and the ones later launches and macOS hand over, in the
    // phone's `intentFlow`: the root opens them once it is up (DesktopLinks).
    DesktopLinks.installSystemHandlers()
    args.forEach { DesktopLinks.offer(it) }
    instance?.let {
        runCatching {
            it.listen { forwarded ->
                forwarded.forEach { link -> DesktopLinks.offer(link) }
                DesktopLinks.raise()
            }
        }.onFailure { e -> Log.w("Main", "a second launch will not reach this one", e) }
    }
    // rustify:// for this user where the installer could not register it (Windows, Linux).
    thread(name = "rustify-url-scheme", isDaemon = true) { UrlScheme.registerIfNeeded() }

    application {
        val scope = rememberCoroutineScope()
        val player = remember {
            QueuePlayer(scope, JvmPlayerBackend, FileStore(File(AppDirs.data, "player"))).also { it.restore() }
        }

        // What the shared screens (docs serie-4.x/26) take from the platform: the phone has these
        // over Android; here they are over the desktop's folders, preferences and the core's engine.
        val appPlatform = remember {
            com.varuna.rustify.ui.DesktopAppPlatform(systemCountry = systemLocale.country).also { DesktopLinks.toasts = it }
        }
        val controller = remember { com.varuna.rustify.player.DesktopPlayerController(player, scope, appPlatform) }

        // The language picked in Settings ("system" = the computer's), the phone's `app_language`.
        // Applied as the JVM's default locale, which is what Compose's resources read; the window's
        // content is recomposed under it, as Android recreates the activity.
        var language by remember {
            mutableStateOf(appPlatform.prefs().getString("app_language") ?: "system").also { applyLocale(it.value) }
        }
        val shell = remember {
            com.varuna.rustify.ui.DesktopAppShell(onLanguage = { code ->
                applyLocale(code)
                NativeEngine.setLanguageNative(defaultLanguage())
                language = code
            })
        }

        // The computer's name is what the others in a Jam see, unless one is set in the Jam tab. From
        // the environment, not InetAddress.getLocalHost(): that can wait on a reverse DNS lookup, and
        // this runs on the UI thread.
        val jam = remember {
            val host = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
            JamSession(scope, JvmJamCore, player, DesktopStores.named(null), host?.takeIf { it.isNotBlank() } ?: "Rustify desktop")
        }
        val appServices = remember {
            // While the DJ speaks the music goes down to about a fifth, as on the phone, and back.
            var volumeBeforeVoice = 1f
            com.varuna.rustify.ui.DesktopAppServices(platform = appPlatform, onVoice = { speaking ->
                if (speaking) {
                    volumeBeforeVoice = player.ui.value.volume
                    player.setVolume(volumeBeforeVoice * 0.22f)
                } else {
                    player.setVolume(volumeBeforeVoice)
                }
            }, inJam = { jam.ui.collectAsState().value.role != JamSession.Role.NONE }, jamDialog = { onDismiss ->
                androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
                    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(560.dp, 640.dp)) {
                            com.varuna.rustify.ui.screens.JamSection(jam)
                        }
                    }
                }
            })
        }

        // Size, place and maximised as the last run left them (WindowMemory).
        val savedWindow = remember { WindowMemory.load() }
        val windowState = rememberWindowState(
            placement = savedWindow.placement,
            position = savedWindow.position,
            size = savedWindow.size,
        )
        // Hidden, not closed, when closing keeps the music playing: the tray brings it back.
        var windowVisible by remember { mutableStateOf(true) }
        val traySupported = remember { DesktopSettings.traySupported }

        // Closing waits, briefly, for the last listen to be stored and scrobbled and the Jam room to
        // be told: launched and then exited, all three were lost.
        fun quit() {
            runCatching { WindowMemory.save(windowState) }
            // The next launch is the first again.
            instance?.close()
            // The DJ's model server is a child process: it goes with the window.
            appServices.close()
            runBlocking {
                withTimeoutOrNull(3_000) {
                    jam.leaveNow()
                    player.close()
                    controller.awaitScrobbles()
                }
            }
            exitApplication()
        }

        // The screens wait for the core's start-up: restoring the Spotify session before the client
        // knows its cache folder would work, and then forget what it learned.
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            // The same start-up the Android app does, with the desktop's folders.
            NativeEngine.initCacheDir(AppDirs.data.absolutePath)
            NativeEngine.initSpotifyCacheDir(AppDirs.cache.absolutePath)
            NativeEngine.setLanguageNative(defaultLanguage())
            player.start()
            ready = true
        }

        // Closing quits, as it always did, unless Settings says to keep playing (and there is a tray
        // to come back from).
        fun closeRequested() {
            if (traySupported && appPlatform.prefs().getBoolean(DesktopSettings.CLOSE_TO_TRAY, false)) {
                runCatching { WindowMemory.save(windowState) }
                windowVisible = false
            } else {
                quit()
            }
        }

        // The phone's launcher icon, in the title bar, the taskbar and the tray.
        val icon = androidx.compose.ui.res.painterResource("rustify.png")

        // The phone's widget, as a tray icon (docs serie-4.x/27 §2.11), in the interface's language.
        if (traySupported) {
            key(language) {
                DesktopTray(icon = icon, player = controller, onShow = DesktopLinks::raise, onQuit = ::quit)
            }
        }

        Window(
            onCloseRequest = ::closeRequested,
            title = "Rustify",
            icon = icon,
            state = windowState,
            visible = windowVisible,
        ) {
            // Narrower or shorter than this and the tablet layout gives way to the phone's.
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(
                    WindowMemory.MIN_SIZE.width.value.toInt(),
                    WindowMemory.MIN_SIZE.height.value.toInt(),
                )
            }
            // Kept as the user leaves it, a moment after they stop moving it.
            LaunchedEffect(windowState) {
                snapshotFlow { listOf(windowState.placement, windowState.position, windowState.size, windowState.isMinimized) }
                    .collectLatest {
                        delay(500)
                        runCatching { WindowMemory.save(windowState) }
                    }
            }
            // Up and in front: a second launch, a link, the tray, the Dock, the media overlay.
            LaunchedEffect(Unit) {
                DesktopLinks.raiseRequests.collect {
                    windowVisible = true
                    window.isVisible = true
                    windowState.isMinimized = false
                    window.toFront()
                    // Windows keeps a window from taking the foreground on its own; a moment on top
                    // is what lets it come up over the browser that opened the link.
                    window.isAlwaysOnTop = true
                    window.isAlwaysOnTop = false
                    window.requestFocus()
                }
            }
            // The window's native handle is what Windows attaches the media overlay to.
            val handle = window.windowHandle
            LaunchedEffect(handle) { com.varuna.rustify.engine.WindowStyle.darkFrame(handle) }
            LaunchedEffect(handle) {
                MediaKeys.run(
                    player = player,
                    windowHandle = handle,
                    onRaise = DesktopLinks::raise,
                    onQuit = ::quit,
                )
            }
            androidx.compose.runtime.CompositionLocalProvider(
                com.varuna.rustify.ui.LocalAppPlatform provides appPlatform,
                com.varuna.rustify.ui.LocalAppServices provides appServices,
                com.varuna.rustify.player.LocalPlayer provides controller,
            ) {
                // The repository the phone's screens read, created once the core knows its folders.
                val repo = remember(ready) {
                    if (ready) com.varuna.rustify.bridge.SpotifyRepository(appPlatform, com.varuna.rustify.ui.DesktopLocalMusicScanner()) else null
                }
                androidx.compose.runtime.key(language) {
                    com.varuna.rustify.ui.RememberStringEnvironment()
                    RustifyTheme {
                        androidx.compose.foundation.layout.Box {
                            // The phone's app, as the tablet lays it out (docs serie-4.x/26).
                            if (repo != null) {
                                com.varuna.rustify.ui.RustifyApp(
                                    spotifyRepo = repo,
                                    shell = shell,
                                    intentFlow = DesktopLinks.intents,
                                )
                            }
                            com.varuna.rustify.ui.Toasts(appPlatform.toasts, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                        }
                    }
                }
            }
        }
    }
}
