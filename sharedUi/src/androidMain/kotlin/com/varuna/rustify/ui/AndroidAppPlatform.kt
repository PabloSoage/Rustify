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

    override fun share(text: String, subject: String?) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        runCatching {
            app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun copyToClipboard(label: String, text: String) {
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
