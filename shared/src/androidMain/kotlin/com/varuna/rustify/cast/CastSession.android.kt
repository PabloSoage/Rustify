// shared/src/androidMain/kotlin/com/varuna/rustify/cast/CastSession.android.kt
package com.varuna.rustify.cast

import android.content.Context
import com.varuna.rustify.platform.SharedPreferencesStore

fun CastSession.isEnabled(context: Context): Boolean =
    isEnabled(SharedPreferencesStore(context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)))

fun CastSession.setEnabled(context: Context, enabled: Boolean) =
    setEnabled(SharedPreferencesStore(context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)), enabled)

suspend fun CastSession.start(
    context: Context,
    target: CastDiscovery.Device,
    handle: String,
    title: String,
    artist: String,
    durationMs: Long
): String? = start(
    SharedPreferencesStore(context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)),
    target, handle, title, artist, durationMs
)
