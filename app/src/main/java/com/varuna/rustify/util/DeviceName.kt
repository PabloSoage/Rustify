package com.varuna.rustify.util

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The name the user gave this phone (Settings → About phone → Device name), for telling devices apart
 * where the backup syncs between them (4.7).
 *
 * The Bluetooth adapter's name would be a third source, but reading it needs BLUETOOTH_CONNECT on
 * Android 12+, a runtime permission the app has no other use for — so the system setting, then the
 * model.
 */
object DeviceName {
    fun of(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fromModel(Build.MANUFACTURER, Build.MODEL)

    /** "samsung" + "SM-X700" → "Samsung SM-X700"; "Google" + "Google Pixel 9" → "Google Pixel 9". */
    fun fromModel(manufacturer: String?, model: String?): String {
        val maker = manufacturer.orEmpty().trim().replaceFirstChar { it.titlecase() }
        val m = model.orEmpty().trim()
        return when {
            m.isEmpty() -> maker.ifEmpty { "Android" }
            maker.isEmpty() || m.startsWith(maker, ignoreCase = true) -> m
            else -> "$maker $m"
        }
    }
}
