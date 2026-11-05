// shared/src/desktopMain/kotlin/com/varuna/rustify/engine/WindowStyle.kt
package com.varuna.rustify.engine

import com.varuna.rustify.bridge.NativeLibrary
import com.varuna.rustify.platform.Log

/**
 * The window's frame in the app's colours (`core_engine/src/window_style.rs`): a dark title bar on
 * Windows, where the system's is light above an app that is dark everywhere else. Elsewhere the frame
 * is the window manager's and this does nothing.
 */
object WindowStyle {
    private external fun darkFrameNative(hwnd: Long): Boolean

    /** [windowHandle] is the window's native handle (`ComposeWindow.windowHandle`). */
    fun darkFrame(windowHandle: Long) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        // The window can be up before anything else has touched the core: load it first.
        val took = runCatching { NativeLibrary.load(); darkFrameNative(windowHandle) }
            .onFailure { Log.w("WindowStyle", "no dark frame: ${it.message}") }
            .getOrDefault(false)
        if (!took) Log.i("WindowStyle", "the system kept its own frame")
    }
}
