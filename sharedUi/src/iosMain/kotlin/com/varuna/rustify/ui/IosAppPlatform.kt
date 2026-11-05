// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/IosAppPlatform.kt
package com.varuna.rustify.ui

import com.varuna.rustify.platform.KeychainKeyValueStore
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.SecretStorage
import com.varuna.rustify.platform.UserDefaultsStore
import com.varuna.rustify.util.Lock
import com.varuna.rustify.util.locked
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
// A star import, as in IosLocale: Foundation declares much of these in categories.
import platform.Foundation.*
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIPasteboard
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile

/**
 * [AppPlatform] on iOS. The Spotify session keeps its cookie and token in the Keychain ([secrets]) and
 * the rest in the defaults it was already in, so a session saved by the earlier app carries over; every
 * other preferences file by its Android name is a defaults suite of its own. Toasts are a line the
 * interface shows ([toasts]), as on the desktop.
 */
class IosAppPlatform(private val secrets: SecretStorage) : AppPlatform {
    init {
        IosNetworkMonitor.start()
    }

    override val kind = AppPlatform.Kind.IOS

    override val appVersion: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""

    override val systemCountry: String = NSLocale.currentLocale.objectForKey(NSLocaleCountryCode) as? String ?: ""

    private val stores = mutableMapOf<String, KeyValueStore>()

    // Main queue or not: a screen's IO may ask for a store, and creating one twice loses writes.
    private val lock = Lock()

    override fun prefs(name: String): KeyValueStore = lock.locked { stores.getOrPut(name) { open(name) } }

    private fun open(name: String): KeyValueStore =
        if (name == SPOTIFY_PREFS) {
            KeychainKeyValueStore(KeychainKeyValueStore.SPOTIFY_SECRETS, secrets, UserDefaultsStore())
        } else {
            UserDefaultsStore(NSUserDefaults(suiteName = "com.varuna.rustify.$name"))
        }

    private fun folder(directory: NSSearchPathDirectory): AppFile {
        val path = NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).first() as String
        return AppFile(path).also { it.makeDirs() }
    }

    override val filesDir: AppFile by lazy { folder(NSApplicationSupportDirectory) }
    override val cacheDir: AppFile by lazy { folder(NSCachesDirectory) }

    private val _toasts = MutableStateFlow<ToastMessage?>(null)

    /** The message the interface should show now. */
    val toasts: StateFlow<ToastMessage?> = _toasts
    private var serial = 0L

    override fun toast(message: String, long: Boolean) {
        _toasts.value = ToastMessage(message, long, ++serial)
    }

    override fun openUrl(url: String) {
        val target = NSURL.URLWithString(url)
        if (target == null) {
            Log.w(TAG, "not a URL: $url")
            return
        }
        UIApplication.sharedApplication.openURL(target, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    private fun shareSheet(items: List<Any>): Boolean =
        IosPresenter.present(UIActivityViewController(activityItems = items, applicationActivities = null))

    override fun share(text: String, subject: String?): Boolean = shareSheet(listOf(text))

    override fun shareImage(file: AppFile, text: String, mimeType: String): Boolean {
        val image = UIImage.imageWithContentsOfFile(file.path) ?: return false
        return shareSheet(listOfNotNull(image, text.takeIf { it.isNotBlank() }))
    }

    override fun copyToClipboard(label: String, text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override fun readClipboard(): String? = UIPasteboard.generalPasteboard.string

    override fun isOnline(): Boolean = IosNetworkMonitor.isOnline

    private companion object {
        const val TAG = "IosAppPlatform"
        const val SPOTIFY_PREFS = "rustify_spotify_prefs"
    }
}

/**
 * Monitors iOS network connectivity via [NWPathMonitor] (`platform.Network`).
 * Lives for the whole application lifetime and updates [isOnline] when path status changes.
 */
private object IosNetworkMonitor {
    @Volatile
    var isOnline: Boolean = true
        private set

    private val monitor: nw_path_monitor_t = nw_path_monitor_create()

    fun start() {
        // Accessing this function ensures the singleton object is initialized.
    }

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            isOnline = path != null && nw_path_get_status(path) == nw_path_status_satisfied
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }
}
