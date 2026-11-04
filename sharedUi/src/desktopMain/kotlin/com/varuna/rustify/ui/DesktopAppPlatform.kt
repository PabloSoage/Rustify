// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopAppPlatform.kt
package com.varuna.rustify.ui

import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.share_copied
import com.varuna.rustify.ui.resources.share_image_copied
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

    // jpackage's launcher sets this to the installer's packageVersion; a run from Gradle has none.
    override val appVersion: String = System.getProperty("jpackage.app-version") ?: ""

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
        toast(Res.string.share_copied)
        return true
    }

    /**
     * No share sheet to hand an image to: the image goes to the clipboard, ready to paste into a chat
     * or a post; the caption only if the image cannot be read.
     */
    override fun shareImage(file: File, text: String): Boolean {
        val image = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull() ?: return share(text)
        val transferable = object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) =
                flavor == java.awt.datatransfer.DataFlavor.imageFlavor
            override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
                return image
            }
        }
        val copied = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null) }.isSuccess
        if (copied) toast(Res.string.share_image_copied)
        return copied
    }

    override fun copyToClipboard(label: String, text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    /** An interface that is up and not loopback; the JDK has no "has internet" to ask. */
    override fun isOnline(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence().any { it.isUp && !it.isLoopback }
    }.getOrDefault(true)

    override fun readClipboard(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull()
}
