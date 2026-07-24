package com.varuna.rustify.player

import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession

/**
 * Static bridge between the library repositories ([SpotifyRepository] / [YtMusicRepository]) and the
 * Android Auto media session ([MediaLibraryService.MediaLibrarySession]).
 *
 * Android Auto caches the browse tree; when the user adds/removes a favorite or edits a playlist
 * inside the app, the car's cache must be invalidated via
 * [MediaLibraryService.MediaLibrarySession.notifyChildrenChanged]. The repositories do not know the
 * session (it lives in `RustifyForegroundService`), so the service registers its session here and the
 * repos call [notifyLibraryChanged] when their library changes. The session handle is held weakly so
 * it does not prevent GC of the service.
 */
object MediaBrowserNotifier {

    @Volatile
    private var sessionRef: java.lang.ref.WeakReference<MediaLibrarySession>? = null

    /** All Android Auto tree parentIds that a library change can affect. Notifying them all at once
     *  is cheap (each one is a cache invalidation). */
    private val ALL_PARENTS = listOf(
        "root",
        "cat_liked", "cat_playlists", "cat_albums", "cat_artists",
        "cat_local", "local_favs", "local_playlists", "local_tracks", "local_albums", "local_artists",
        "cat_ytm", "ytm_favs", "ytm_playlists",
        "sec_queue"
    )

    fun bind(session: MediaLibrarySession?) {
        sessionRef = session?.let { java.lang.ref.WeakReference(it) }
    }

    fun unbind() {
        sessionRef = null
    }

    // itemCount = -1 → "unknown child count, re-query the tree" (the convention we use to force a
    // refresh in Android Auto). The lint range check does not account for it.
    @Suppress("Range")
    fun notifyChildrenChanged(parentId: String) {
        runCatching { sessionRef?.get()?.notifyChildrenChanged(parentId, -1, null) }
    }

    /** Notifies every node of the tree (use after any library change). */
    @Suppress("Range")
    fun notifyLibraryChanged() {
        val s = sessionRef?.get() ?: return
        ALL_PARENTS.forEach { runCatching { s.notifyChildrenChanged(it, -1, null) } }
    }
}