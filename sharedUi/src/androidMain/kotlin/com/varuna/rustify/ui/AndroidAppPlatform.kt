// sharedUi/src/androidMain/kotlin/com/varuna/rustify/ui/AndroidAppPlatform.kt
package com.varuna.rustify.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.SharedPreferencesStore
import java.io.File

/** [AppPlatform] over the application's `Context`: the same preference files and folders as before. */
class AndroidAppPlatform(context: Context) : AppPlatform {
    private val app = context.applicationContext

    override val kind = AppPlatform.Kind.ANDROID

    override val appVersion: String by lazy {
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: ""
    }

    // The system's resources keep the device's locale; the app's own configuration and the JVM's
    // default carry the language picked in Settings.
    override val systemCountry: String
        get() = runCatching { android.content.res.Resources.getSystem().configuration.locales[0].country }.getOrNull() ?: ""

    override fun prefs(name: String): KeyValueStore =
        SharedPreferencesStore(app.getSharedPreferences(name, Context.MODE_PRIVATE))

    override val filesDir: File get() = app.filesDir
    override val cacheDir: File get() = app.cacheDir

    override fun toast(message: String, long: Boolean) {
        Toast.makeText(app, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun openUrl(url: String) {
        runCatching {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // FLAG_ACTIVITY_NEW_TASK and a wide catch: started from a bottom sheet's window, startActivity throws
    // AndroidRuntimeException, and a share must never take the app down.
    override fun share(text: String, subject: String?): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        return runCatching {
            app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    override fun shareImage(file: File, text: String, mimeType: String): Boolean =
        shareImageFrom(app, file, text, mimeType)

    override fun copyToClipboard(label: String, text: String) {
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override fun isOnline(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun readClipboard(): String? = runCatching {
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
    }.getOrNull()

    override suspend fun ensureLocalNetwork(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 37) return true
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                app,
                "android.permission.ACCESS_LOCAL_NETWORK"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return true
        return localNetworkPermissionRequester?.invoke() ?: false
    }

    override fun openAppSettings() {
        runCatching {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", app.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }
    }

    companion object {
        @kotlin.concurrent.Volatile
        var localNetworkPermissionRequester: (suspend () -> Boolean)? = null
    }
}

/**
 * Offers [file] to the share sheet, started from [from]. From an `Activity` the sheet opens in its
 * task, as a screen's share always did; from anything else it needs `FLAG_ACTIVITY_NEW_TASK`. The
 * caption goes only when there is one: an empty `EXTRA_TEXT` is still a text some targets paste.
 */
internal fun shareImageFrom(from: Context, file: File, text: String, mimeType: String): Boolean = runCatching {
    val uri = androidx.core.content.FileProvider.getUriForFile(from, "${from.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
        // Some targets read the payload from clipData rather than the extra.
        clipData = ClipData.newUri(from.contentResolver, "cover", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (from !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    from.startActivity(chooser)
}.isSuccess
