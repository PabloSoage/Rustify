// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/IosFolders.kt
@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.varuna.rustify.ui

import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import com.varuna.rustify.util.Lock
import com.varuna.rustify.util.locked
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import com.varuna.rustify.bridge.FileTreeMusicScanner
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

/**
 * The folders the user picked outside the app. iOS answers a picked folder with a URL whose access ends
 * with this run of the app, so each is kept as a bookmark, by the path the settings hold, and opened
 * again at start. A folder that moved keeps working: the bookmark says where it is now.
 */
object IosFolders {
    private val defaults by lazy { NSUserDefaults(suiteName = "com.varuna.rustify.folders") }
    private val lock = Lock()
    private val open = mutableMapOf<String, String>()

    /** A folder just picked, whose access this keeps: called with the picker's own [url]. */
    fun add(url: NSURL) {
        val path = url.path ?: return
        url.startAccessingSecurityScopedResource()
        lock.locked { open[path] = path }
        val bookmark = url.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = null)
        if (bookmark == null) {
            Log.w(TAG, "no bookmark for a folder: it lasts until the app closes")
            return
        }
        defaults.setObject(bookmark, forKey = path)
    }

    /** Opens every folder kept, once, at start. */
    fun restore() {
        val keys = defaults.dictionaryRepresentation().keys.filterIsInstance<String>().filter { it.startsWith("/") }
        for (stored in keys) {
            val bookmark = defaults.objectForKey(stored) as? NSData ?: continue
            memScoped {
                val stale = alloc<BooleanVar>()
                val url = NSURL.URLByResolvingBookmarkData(
                    bookmark, options = 0u, relativeToURL = null, bookmarkDataIsStale = stale.ptr, error = null,
                )
                if (url == null) {
                    Log.w(TAG, "a folder kept is gone")
                    return@memScoped
                }
                url.startAccessingSecurityScopedResource()
                url.path?.let { now -> lock.locked { open[stored] = now } }
                if (stale.value) {
                    url.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = null)
                        ?.let { defaults.setObject(it, forKey = stored) }
                }
            }
        }
    }

    /** The folder a setting names, where it is now; null when it is no folder here. */
    fun folderOf(stored: String): AppFile? {
        if (stored == DOCUMENTS) return documents()
        return appFileAt(lock.locked { open[stored] } ?: stored).takeIf { it.isFolder() }
    }

    private fun documents(): AppFile {
        val path = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        return AppFile(path).also { it.makeDirs() }
    }

    /**
     * With no folder chosen yet, the app's Documents take the downloads and join local music, so the
     * menus offer a download from the start. By name and not by path: iOS moves the app's container
     * between updates.
     */
    fun seedDefaults(prefs: KeyValueStore) {
        if (prefs.getString(DOWNLOADS_KEY) == null) prefs.putString(DOWNLOADS_KEY, DOCUMENTS)
        val offered = prefs.getStringSet(OFFERED_KEY, emptySet())
        if (DOCUMENTS !in offered) {
            prefs.putStringSet(OFFERED_KEY, offered + DOCUMENTS)
            prefs.putStringSet(LOCAL_DIRS_KEY, prefs.getStringSet(LOCAL_DIRS_KEY, emptySet()) + DOCUMENTS)
        }
    }

    /** What the settings hold for the app's own Documents, which [folderLabel] shows as it is. */
    private const val DOCUMENTS = "Documents"
    private const val DOWNLOADS_KEY = "download_directory"
    private const val LOCAL_DIRS_KEY = "local_music_directories"
    private const val OFFERED_KEY = "download_dirs_added_to_local_music"
    private const val TAG = "IosFolders"
}

/** A cover at most [FileTreeMusicScanner.COVER_MAX_PX] on its longer side, as a JPEG at quality 80. */
internal fun scaledCover(bytes: ByteArray): ByteArray? = runCatching {
    val src = Image.makeFromEncoded(bytes)
    val scale = minOf(1f, FileTreeMusicScanner.COVER_MAX_PX.toFloat() / maxOf(src.width, src.height))
    val w = maxOf(1, (src.width * scale).toInt())
    val h = maxOf(1, (src.height * scale).toInt())
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(src, Rect.makeWH(w.toFloat(), h.toFloat()))
    surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)?.bytes
}.getOrNull()
