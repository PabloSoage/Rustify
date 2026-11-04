// app/src/main/java/com/varuna/rustify/RustifyApplication.kt

package com.varuna.rustify

import android.app.Application
import android.content.Context

/**
 * The process's `Application`, here only so Coil asks it for the image loader. The first caller of
 * `SingletonImageLoader.get` is not always the activity — the widget loads covers for playback
 * started from Bluetooth or Android Auto with no interface open — and a loader set from the
 * activity came too late then: the default one, without the disk cache, had already been made.
 */
class RustifyApplication : Application(), coil3.SingletonImageLoader.Factory {
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader = rustifyImageLoader(context)
}

/**
 * Coil's loader with a persistent disk cache. Its size is configurable in Settings (pref
 * cache_max_mb, default 500 MB) and takes effect on app restart. Coil 3 ignores the servers' cache
 * headers by default, as respectCacheHeaders(false) did in 2.
 */
fun rustifyImageLoader(context: Context): coil3.ImageLoader {
    val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
    val cacheMaxMb = prefs.getInt("cache_max_mb", 500).coerceIn(100, 8192).toLong()
    return coil3.ImageLoader.Builder(context)
        .memoryCache {
            coil3.memory.MemoryCache.Builder()
                .maxSizePercent(context, 0.25)
                .build()
        }
        .diskCache {
            coil3.disk.DiskCache.Builder()
                .directory(okio.Path.Companion.run { context.cacheDir.resolve("image_cache").toOkioPath() })
                .maxSizeBytes(cacheMaxMb * 1024 * 1024)
                .build()
        }
        .build()
}
