// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/AppPlatform.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.varuna.rustify.platform.KeyValueStore
import java.io.File

/**
 * What the shared screens used Android's `Context` for (docs serie-4.x/26, measured: preferences,
 * folders, toasts, sharing, links, the clipboard), as one interface each platform implements once.
 * Android's is over the `Context`, with the same preference files and folders as before, so nothing
 * stored moves; the desktop's is over `java.util.prefs`, the app's data folder and the window.
 */
interface AppPlatform {
    val kind: Kind

    /** A preferences file by its Android name (`rustify_settings`, `rustify_spotify_prefs`…). */
    fun prefs(name: String = SETTINGS): KeyValueStore

    /** Where the app keeps its own files (Android's `filesDir`). */
    val filesDir: File

    /** What may be deleted to make room (Android's `cacheDir`). */
    val cacheDir: File

    /** A short message over the interface (Android's `Toast`). */
    fun toast(message: String, long: Boolean = false)

    /** Opens a web page or a link in the system's handler for it. */
    fun openUrl(url: String)

    /** Offers [text] to the system's share sheet; on the desktop, copies it and says so. */
    fun share(text: String, subject: String? = null)

    fun copyToClipboard(label: String, text: String)

    enum class Kind { ANDROID, DESKTOP }

    companion object {
        const val SETTINGS = "rustify_settings"
    }
}

val LocalAppPlatform = staticCompositionLocalOf<AppPlatform> { error("no AppPlatform provided") }
