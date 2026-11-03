// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/Main.kt
package com.varuna.rustify.desktop

import androidx.compose.runtime.LaunchedEffect
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
import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.defaultLanguage

fun main() {
    // Before anything names NativeEngine: on the desktop the library is not on java.library.path.
    CoreLoader.install()

    application {
        val scope = rememberCoroutineScope()
        val player = remember { QueuePlayer(scope, JvmPlayerBackend) }

        LaunchedEffect(Unit) {
            // The same start-up the Android app does, with the desktop's folders.
            NativeEngine.initCacheDir(AppDirs.data.absolutePath)
            NativeEngine.initSpotifyCacheDir(AppDirs.cache.absolutePath)
            NativeEngine.setLanguageNative(defaultLanguage())
            player.start()
        }

        Window(
            onCloseRequest = {
                player.shutdown()
                exitApplication()
            },
            title = "Rustify",
            state = rememberWindowState(width = 1100.dp, height = 760.dp),
        ) {
            RustifyTheme {
                App(player)
            }
        }
    }
}
