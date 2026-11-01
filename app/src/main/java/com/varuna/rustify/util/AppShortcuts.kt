package com.varuna.rustify.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.varuna.rustify.MainActivity
import com.varuna.rustify.R

/**
 * The launcher icon's long-press menu (4.9): carry on listening, search, and the DJ.
 *
 * Published from code rather than `res/xml/shortcuts.xml` because a static shortcut names its target
 * package, and the debug build installs beside the release one under another id: a static file
 * would open the release app from the debug icon.
 *
 * Each one is an intent with [ACTION] and a target in [EXTRA_TARGET]; `MainActivity` turns it into
 * a deep-link token (`shortcut:<target>`) and `navigateDeepLink` does the rest.
 */
object AppShortcuts {
    const val ACTION = "com.varuna.rustify.action.SHORTCUT"
    const val EXTRA_TARGET = "target"
    const val RESUME = "resume"
    const val SEARCH = "search"
    const val DJ = "dj"

    fun publish(context: Context) {
        val list = listOf(
            build(context, RESUME, R.string.shortcut_resume),
            build(context, SEARCH, R.string.shortcut_search),
            build(context, DJ, R.string.shortcut_dj),
        )
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, list) }
    }

    private fun build(context: Context, target: String, label: Int): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_TARGET, target)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return ShortcutInfoCompat.Builder(context, target)
            .setShortLabel(context.getString(label))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
    }
}
