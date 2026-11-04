// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/Main.kt
package com.varuna.rustify.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.varuna.rustify.platform.PreferencesStore
import com.varuna.rustify.platform.defaultLanguage
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

// The computer's locale, before Settings' language replaces the JVM's default.
private val systemLocale: java.util.Locale = java.util.Locale.getDefault()

private fun applyLocale(code: String) {
    java.util.Locale.setDefault(if (code == "system") systemLocale else java.util.Locale.forLanguageTag(code))
}

fun main() {
    // Before anything names NativeEngine: on the desktop the library is not on java.library.path.
    CoreLoader.install()
    // The log viewer's buffer, from the first line on.
    com.varuna.rustify.util.DesktopLogBook.install()
    // The macOS title bar in dark, as Windows' (WindowStyle): AWT reads it before the first window.
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")

    application {
        val scope = rememberCoroutineScope()
        val player = remember {
            QueuePlayer(scope, JvmPlayerBackend, FileStore(File(AppDirs.data, "player"))).also { it.restore() }
        }

        // What the shared screens (docs serie-4.x/26) take from the platform: the phone has these
        // over Android; here they are over the desktop's folders, preferences and the core's engine.
        val appPlatform = remember { com.varuna.rustify.ui.DesktopAppPlatform() }
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
            JamSession(scope, JvmJamCore, player, PreferencesStore(), host?.takeIf { it.isNotBlank() } ?: "Rustify desktop")
        }
        val appServices = remember {
            com.varuna.rustify.ui.DesktopAppServices(inJam = { jam.ui.collectAsState().value.role != JamSession.Role.NONE }, jamDialog = { onDismiss ->
                androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
                    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(560.dp, 640.dp)) {
                            com.varuna.rustify.desktop.ui.JamSection(jam)
                        }
                    }
                }
            })
        }

        // Closing waits, briefly, for the last listen to be stored and the Jam room to be told:
        // launched and then exited, both were lost.
        fun quit() {
            runBlocking {
                withTimeoutOrNull(3_000) {
                    jam.leaveNow()
                    player.close()
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

        Window(
            onCloseRequest = ::quit,
            title = "Rustify",
            // The phone's launcher icon, also in the title bar and the taskbar.
            icon = androidx.compose.ui.res.painterResource("rustify.png"),
            state = rememberWindowState(width = 1100.dp, height = 760.dp),
        ) {
            // The window's native handle is what Windows attaches the media overlay to.
            val handle = window.windowHandle
            LaunchedEffect(handle) { com.varuna.rustify.engine.WindowStyle.darkFrame(handle) }
            LaunchedEffect(handle) {
                MediaKeys.run(
                    player = player,
                    windowHandle = handle,
                    onRaise = { window.toFront() },
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
                            if (repo != null) com.varuna.rustify.ui.RustifyApp(spotifyRepo = repo, shell = shell)
                            Toasts(appPlatform, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                        }
                    }
                }
            }
        }
    }
}
