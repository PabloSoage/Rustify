package com.varuna.rustify.player

import android.content.Context
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository

/**
 * E96 — the Android Auto browse tree, in plain data (no Media3 types) so it can be shared by BOTH the
 * [RustifyForegroundService] MediaLibrarySession AND the in-app "Android Auto preview" (debug) screen.
 *
 * v3.0: the tree now mirrors the phone library — Liked, Playlists, Albums, Artists, a Local group
 * (favorites / playlists / tracks / albums / artists) and a YouTube Music group (favorites / playlists),
 * plus a "Now playing" queue whose items jump to that point. Every node carries an [imageUrl] so the
 * service can show cover art (not just text). Cache-backed sections resolve synchronously; the
 * network-backed Spotify leaves (playlist / album / artist tracks) resolve via [childrenAsync].
 */
object AndroidAutoBrowse {
    data class Node(
        val id: String,
        val title: String,
        val subtitle: String,
        val browsable: Boolean,
        val track: FullTrack?,
        val imageUrl: String? = null
    )

    /** Nodos que requieren red (se resuelven en [childrenAsync]; en modo síncrono salen vacíos). */
    private fun isNetworkNode(parentId: String): Boolean =
        parentId.startsWith("spl:") || parentId.startsWith("salb:") || parentId.startsWith("sart:")

    /**
     * Hijos de un nodo — versión **síncrona** (solo caché). La usa el preview in-app. Los nodos que
     * requieren red devuelven vacío aquí; el servicio usa [childrenAsync].
     */
    fun children(context: Context, parentId: String, ytmRepo: YtMusicRepository): List<Node> {
        val repo = SpotifyRepository.instance ?: return emptyList()
        return when {
            parentId == "root" -> rootSections(context, repo, ytmRepo)

            // ── Now playing / cola (saltar a un punto) ──
            parentId == "sec_queue" -> runCatching {
                AudioPlayerService.getInstance(context).state.value.queue
                    .filter { it.id != null }
                    .map { trackNode(it, id = "queue:${it.id}") }
            }.getOrDefault(emptyList())

            // ── Spotify ──
            parentId == "cat_liked" -> repo.likedTracks.map { trackNode(it) }
            parentId == "cat_playlists" -> repo.savedPlaylists.map {
                Node("spl:${it.id}", it.name, "", true, null, it.images.firstOrNull()?.url)
            }
            parentId == "cat_albums" -> repo.savedAlbums.map {
                Node("salb:${it.id}", it.name, it.artists.joinToString(", ") { a -> a.name }, true, null, it.images.firstOrNull()?.url)
            }
            parentId == "cat_artists" -> repo.followedArtists.map {
                Node("sart:${it.id}", it.name, "", true, null, it.images.firstOrNull()?.url)
            }

            // ── Local ──
            parentId == "cat_local" -> localSections(context)
            parentId == "local_favs" -> repo.localTracks.filter { it.id != null && repo.isLocalFavorite(it.id!!) }.map { trackNode(it) }
            parentId == "local_tracks" -> repo.localTracks.map { trackNode(it) }
            parentId == "local_playlists" -> repo.localPlaylists.map {
                Node("localfolder:${it.id}", it.name, "", true, null, repo.localPlaylistTracks(it.id).firstOrNull()?.album?.images?.firstOrNull()?.url)
            }
            parentId.startsWith("localfolder:") ->
                repo.localPlaylistTracks(parentId.removePrefix("localfolder:")).map { trackNode(it) }
            parentId == "local_albums" -> repo.localTracks.mapNotNull { it.album?.name }.distinct().sorted().map { name ->
                Node("localalbum:$name", name, "", true, null,
                    repo.localTracks.firstOrNull { it.album?.name == name }?.album?.images?.firstOrNull()?.url)
            }
            parentId.startsWith("localalbum:") -> {
                val name = parentId.removePrefix("localalbum:")
                repo.localTracks.filter { it.album?.name == name }.map { trackNode(it) }
            }
            parentId == "local_artists" -> repo.localTracks.flatMap { t -> t.artists.map { it.name } }.distinct().sorted().map { name ->
                Node("localartist:$name", name, "", true, null, null)
            }
            parentId.startsWith("localartist:") -> {
                val name = parentId.removePrefix("localartist:")
                repo.localTracks.filter { t -> t.artists.any { it.name == name } }.map { trackNode(it) }
            }

            // ── YouTube Music ──
            parentId == "cat_ytm" -> ytmSections(context)
            parentId == "ytm_favs" -> ytmRepo.favorites.map { trackNode(it.toFullTrack(), imageUrl = it.thumbnailUrl) }
            parentId == "ytm_playlists" -> ytmRepo.playlists.map {
                Node("ytmpl:${it.localId}", it.name, "", true, null, it.items.firstOrNull()?.thumbnailUrl)
            }
            parentId.startsWith("ytmpl:") -> {
                val lid = parentId.removePrefix("ytmpl:")
                ytmRepo.playlists.firstOrNull { it.localId == lid }?.items?.map { trackNode(it.toFullTrack(), imageUrl = it.thumbnailUrl) } ?: emptyList()
            }

            else -> emptyList()
        }
    }

    /** Versión **asíncrona**: resuelve además los nodos Spotify que requieren red. */
    suspend fun childrenAsync(context: Context, parentId: String, ytmRepo: YtMusicRepository): List<Node> {
        val repo = SpotifyRepository.instance ?: return emptyList()
        if (!isNetworkNode(parentId)) return children(context, parentId, ytmRepo)
        return runCatching {
            when {
                parentId.startsWith("spl:") -> repo.getPlaylistTracks(parentId.removePrefix("spl:"), limit = 100).items.map { trackNode(it) }
                parentId.startsWith("salb:") -> repo.getAlbumTracks(parentId.removePrefix("salb:"), limit = 100).items.map { trackNode(it) }
                parentId.startsWith("sart:") -> repo.getArtistTopTracks(parentId.removePrefix("sart:"), limit = 50).items.map { trackNode(it) }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    fun resolveTrack(mediaId: String, ytmRepo: YtMusicRepository): FullTrack? {
        val repo = SpotifyRepository.instance ?: return null
        return when {
            mediaId.startsWith("queue:") -> {
                val id = mediaId.removePrefix("queue:")
                repo.likedTracks.firstOrNull { it.id == id }
                    ?: repo.localTracks.firstOrNull { it.id == id }
                    ?: ytmRepo.favorites.firstOrNull { "ytm:${it.videoId}" == id }?.toFullTrack()
            }
            mediaId.startsWith("ytm:") -> ytmRepo.favorites.firstOrNull { "ytm:${it.videoId}" == mediaId }?.toFullTrack()
            mediaId.startsWith("local:") -> repo.localTracks.firstOrNull { it.id == mediaId }
            else -> repo.likedTracks.firstOrNull { it.id == mediaId }
        }
    }

    // ── Estructura de secciones ────────────────────────────────────────────────────────────
    private fun rootSections(context: Context, repo: SpotifyRepository, ytmRepo: YtMusicRepository): List<Node> {
        val out = ArrayList<Node>()
        val hasQueue = runCatching { AudioPlayerService.getInstance(context).state.value.queue.isNotEmpty() }.getOrDefault(false)
        if (hasQueue) out += Node("sec_queue", context.getString(R.string.auto_section_queue), "", true, null)
        out += Node("cat_liked", context.getString(R.string.library_tab_liked_tracks), "", true, null)
        out += Node("cat_playlists", context.getString(R.string.library_tab_playlists), "", true, null)
        out += Node("cat_albums", context.getString(R.string.library_tab_albums), "", true, null)
        out += Node("cat_artists", context.getString(R.string.library_tab_artists), "", true, null)
        out += Node("cat_local", context.getString(R.string.auto_section_local), "", true, null)
        out += Node("cat_ytm", context.getString(R.string.ytm_title), "", true, null)
        return out
    }

    private fun localSections(context: Context): List<Node> = listOf(
        Node("local_favs", context.getString(R.string.auto_local_favorites), "", true, null),
        Node("local_playlists", context.getString(R.string.local_group_playlists), "", true, null),
        Node("local_tracks", context.getString(R.string.auto_local_tracks), "", true, null),
        Node("local_albums", context.getString(R.string.library_tab_albums), "", true, null),
        Node("local_artists", context.getString(R.string.library_tab_artists), "", true, null),
    )

    private fun ytmSections(context: Context): List<Node> = listOf(
        Node("ytm_favs", context.getString(R.string.auto_local_favorites), "", true, null),
        Node("ytm_playlists", context.getString(R.string.local_group_playlists), "", true, null),
    )

    private fun trackNode(t: FullTrack, id: String = t.id ?: "", imageUrl: String? = t.album?.images?.firstOrNull()?.url) =
        Node(id, t.name, t.artists.joinToString(", ") { it.name }, false, t, imageUrl)
}
