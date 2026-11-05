// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/RustifyImageLoader.kt
package com.varuna.rustify.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.varuna.rustify.platform.KeyValueStore
import okio.Path.Companion.toPath

/**
 * Shared Coil 3 [ImageLoader] configuration for Android, Desktop, and iOS.
 *
 * Configures an explicit persistent disk cache at [cacheDir]/image_cache with maximum size
 * driven by the user preference `cache_max_mb` (default 500 MB).
 */
fun newRustifyImageLoader(
    context: PlatformContext,
    cacheDir: AppFile,
    prefs: KeyValueStore,
    /** What this platform adds or leaves out: the web has no disk of its own and no fetcher found by itself. */
    configure: (ImageLoader.Builder.() -> Unit)? = null,
): ImageLoader {
    val cacheMaxMb = prefs.getInt("cache_max_mb", 500).coerceIn(100, 8192).toLong()
    return ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.child("image_cache").fullPath.toPath())
                .maxSizeBytes(cacheMaxMb * 1024 * 1024)
                .build()
        }
        .apply { configure?.invoke(this) }
        .build()
}
