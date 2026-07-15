package com.varuna.rustify.player

import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession

/**
 * Puente estático entre los repositorios de biblioteca ([SpotifyRepository] / [YtMusicRepository])
 * y la sesión de medios de Android Auto ([MediaLibraryService.MediaLibrarySession]).
 *
 * Android Auto cachea el árbol de navegación; cuando el usuario añade/quita un favorito o edita una
 * playlist *dentro de la app*, hay que invalidar la caché del coche con
 * [MediaLibraryService.MediaLibrarySession.notifyChildrenChanged]. Los repositorios no conocen a la
 * sesión (vive en `RustifyForegroundService`), así que el servicio les da de alta su sesión aquí y los
 * repos llaman a [notifyAll] cuando su biblioteca cambia. El handle de sesión se guarda de forma
 * débil para no impedir el GC del servicio.
 */
object MediaBrowserNotifier {

    @Volatile
    private var sessionRef: java.lang.ref.WeakReference<MediaLibrarySession>? = null

    /** Todos los parentId del árbol de Android Auto que pueden verse afectados por un cambio de
     *  biblioteca. Notificarlos a todos de golpe es barato (cada uno es un invalidate de caché). */
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

    fun notifyChildrenChanged(parentId: String) {
        runCatching { sessionRef?.get()?.notifyChildrenChanged(parentId, -1, null) }
    }

    /** Notifica a todos los nodos del árbol (usar tras cualquier cambio de biblioteca). */
    fun notifyLibraryChanged() {
        val s = sessionRef?.get() ?: return
        ALL_PARENTS.forEach { runCatching { s.notifyChildrenChanged(it, -1, null) } }
    }
}