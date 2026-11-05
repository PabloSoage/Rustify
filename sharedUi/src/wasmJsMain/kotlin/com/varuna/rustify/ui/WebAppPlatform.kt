// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/ui/WebAppPlatform.kt
package com.varuna.rustify.ui

import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.ServerKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private fun openWindow(url: String): Unit = js("window.open(url, '_blank', 'noopener')")

private fun writeClipboard(text: String): Unit = js("navigator.clipboard && navigator.clipboard.writeText(text)")

private fun onLine(): Boolean = js("navigator.onLine !== false")

private fun canShare(): Boolean = js("!!navigator.share")

private fun shareText(text: String): Unit = js("navigator.share({ text: text }).catch(function() {})")

/**
 * [AppPlatform] in a browser. The settings and the app's files are on the server, so every browser
 * signed in to it sees the same app (docs serie-4.x/28 §2.2); toasts are a line the interface shows.
 */
class WebAppPlatform(override val appVersion: String) : AppPlatform {
    override val kind = AppPlatform.Kind.WEB

    override val systemCountry: String = WebLocale.tag.substringAfter('-', "").takeIf { it.length == 2 }.orEmpty().uppercase()

    private val stores = mutableMapOf<String, KeyValueStore>()

    override fun prefs(name: String): KeyValueStore = stores.getOrPut(name) {
        filesDir.child("prefs").let { it.makeDirs(); ServerKeyValueStore(it.child("$name.json")) }
    }

    override val filesDir: AppFile = AppFile("/files")
    override val cacheDir: AppFile = AppFile("/cache")

    private val _toasts = MutableStateFlow<ToastMessage?>(null)
    val toasts: StateFlow<ToastMessage?> = _toasts
    private var serial = 0L

    override fun toast(message: String, long: Boolean) {
        _toasts.value = ToastMessage(message, long, ++serial)
    }

    override fun openUrl(url: String) = openWindow(url)

    override fun share(text: String, subject: String?): Boolean {
        if (canShare()) shareText(text) else writeClipboard(text)
        return true
    }

    // An image on the server has no share sheet to go to from here.
    override fun shareImage(file: AppFile, text: String, mimeType: String): Boolean = false

    override fun copyToClipboard(label: String, text: String) = writeClipboard(text)

    // Asynchronous and behind a permission in every browser: nothing a plain function can answer.
    override fun readClipboard(): String? = null

    override fun isOnline(): Boolean = onLine()
}
