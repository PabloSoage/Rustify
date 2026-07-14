package com.varuna.rustify.player

import android.content.Context
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository

/**
 * E96 — the Android Auto browse tree, in plain data (no Media3 types) so it can be shared by BOTH the
 * [RustifyForegroundService] MediaLibrarySession AND the in-app "Android Auto preview" (debug) screen.
 * All data comes from the live caches (liked songs, local playlists, YTM favorites) — no network.
 */
object AndroidAutoBrowse {
    data class Node(
        val id: String,
        val title: String,
        val subtitle: String,
        val browsable: Boolean,
        val track: FullTrack?
    )

    fun children(context: Context, parentId: String, ytmRepo: YtMusicRepository): List<Node> {
        val repo = SpotifyRepository.instance
        return when {
            parentId == "root" -> listOf(
                Node("cat_liked", context.getString(R.string.library_tab_liked_tracks), "", true, null),
                Node("cat_local", context.getString(R.string.local_group_playlists), "", true, null),
                Node("cat_ytm", context.getString(R.string.ytm_title), "", true, null),
            )
            parentId == "cat_liked" -> repo?.likedTracks?.map { node(it) } ?: emptyList()
            parentId == "cat_local" -> repo?.localPlaylists?.map { Node("localfolder:${it.id}", it.name, "", true, null) } ?: emptyList()
            parentId.startsWith("localfolder:") ->
                repo?.localPlaylistTracks(parentId.removePrefix("localfolder:"))?.map { node(it) } ?: emptyList()
            parentId == "cat_ytm" -> ytmRepo.favorites.map { node(it.toFullTrack()) }
            else -> emptyList()
        }
    }

    fun resolveTrack(mediaId: String, ytmRepo: YtMusicRepository): FullTrack? {
        val repo = SpotifyRepository.instance ?: return null
        return when {
            mediaId.startsWith("ytm:") -> ytmRepo.favorites.firstOrNull { "ytm:${it.videoId}" == mediaId }?.toFullTrack()
            mediaId.startsWith("local:") -> repo.localTracks.firstOrNull { it.id == mediaId }
            else -> repo.likedTracks.firstOrNull { it.id == mediaId }
        }
    }

    private fun node(t: FullTrack) =
        Node(t.id ?: "", t.name, t.artists.joinToString(", ") { it.name }, false, t)
}
