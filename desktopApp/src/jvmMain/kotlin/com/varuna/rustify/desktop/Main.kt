// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/Main.kt
package com.varuna.rustify.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.desktop.ui.App
import com.varuna.rustify.desktop.ui.RustifyTheme
import com.varuna.rustify.engine.JvmPlayerBackend
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.jam.JamSession
import com.varuna.rustify.jam.JvmJamCore
import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.FileStore
import com.varuna.rustify.platform.PreferencesStore
import com.varuna.rustify.platform.SecureKeyValueStore
import com.varuna.rustify.spotify.JvmSpotifyCore
import com.varuna.rustify.spotify.SpotifyLibrary
import com.varuna.rustify.platform.defaultLanguage
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

fun main() {
    // Before anything names NativeEngine: on the desktop the library is not on java.library.path.
    CoreLoader.install()

    application {
        val scope = rememberCoroutineScope()
        val player = remember {
            QueuePlayer(scope, JvmPlayerBackend, FileStore(File(AppDirs.data, "player"))).also { it.restore() }
        }
        // The session is three short values: Preferences holds them (the queue does not fit, see
        // FileStore), except the cookie and the token, which go to the system's credential store.
        val spotify = remember {
            SpotifyLibrary(
                JvmSpotifyCore,
                SecureKeyValueStore(setOf(SpotifyLibrary.KEY_SP_DC, SpotifyLibrary.KEY_ACCESS_TOKEN), PreferencesStore()),
            )
        }

        // The computer's name is what the others in a Jam see, unless one is set in the Jam tab. From
        // the environment, not InetAddress.getLocalHost(): that can wait on a reverse DNS lookup, and
        // this runs on the UI thread.
        val jam = remember {
            val host = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
            JamSession(scope, JvmJamCore, player, PreferencesStore(), host?.takeIf { it.isNotBlank() } ?: "Rustify desktop")
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
            state = rememberWindowState(width = 1100.dp, height = 760.dp),
        ) {
            // The window's native handle is what Windows attaches the media overlay to.
            val handle = window.windowHandle
            LaunchedEffect(handle) {
                MediaKeys.run(
                    player = player,
                    windowHandle = handle,
                    onRaise = { window.toFront() },
                    onQuit = ::quit,
                )
            }
            RustifyTheme {
                if (ready) App(player, spotify, jam)
            }
        }
    }
}
