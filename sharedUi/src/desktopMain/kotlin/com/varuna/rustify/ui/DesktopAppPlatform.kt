// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopAppPlatform.kt
package com.varuna.rustify.ui

import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.FileStore
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.PreferencesStore
import com.varuna.rustify.platform.SecureKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.util.prefs.Preferences

/**
 * [AppPlatform] for Windows, Linux and macOS. Each Android preferences file is a node under the
 * app's (so `rustify_settings` keeps its keys apart from `rustify_spotify_prefs`, as on the phone);
 * toasts are a line the window shows at its bottom ([toasts]); sharing copies to the clipboard,
 * because a desktop has no share sheet, and says so.
 */
class DesktopAppPlatform : AppPlatform {
    override val kind = AppPlatform.Kind.DESKTOP

    private val stores = mutableMapOf<String, KeyValueStore>()

    override fun prefs(name: String): KeyValueStore = synchronized(stores) {
        stores.getOrPut(name) {
            val node = PreferencesStore(Preferences.userRoot().node("com/varuna/rustify/$name"))
            // The Spotify session's cookie and token go to the system's credential store, as
            // SpotifyLibrary kept them (same key names, so a session saved there carries over).
            if (name == SPOTIFY_PREFS) SecureKeyValueStore(setOf("sp_dc_cookie", "access_token"), node) else node
        }
    }

    private companion object {
        const val SPOTIFY_PREFS = "rustify_spotify_prefs"
    }

    /** For values past Preferences' 8 KiB (a JSON list that grows): one file per key. */
    fun files(name: String): KeyValueStore = FileStore(File(AppDirs.data, "prefs/$name"))

    override val filesDir: File get() = AppDirs.data
    override val cacheDir: File get() = AppDirs.cache

    private val _toasts = MutableStateFlow<Toast?>(null)

    /** The message the window should show now, with a serial so the same text twice shows twice. */
    val toasts: StateFlow<Toast?> = _toasts

    data class Toast(val message: String, val long: Boolean, val serial: Long)

    private var serial = 0L

    override fun toast(message: String, long: Boolean) {
        _toasts.value = Toast(message, long, ++serial)
    }

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { Log.w("DesktopAppPlatform", "could not open $url: ${it.message}") }
    }

    override fun share(text: String, subject: String?): Boolean {
        copyToClipboard(subject ?: "Rustify", text)
        return true
    }

    /** No share sheet to hand an image to: the caption (title and link) is what is copied. */
    override fun shareImage(file: File, text: String): Boolean = share(text)

    override fun copyToClipboard(label: String, text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override fun readClipboard(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull()
}
