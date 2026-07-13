package com.varuna.rustify.util

import android.net.Uri

/**
 * Parsed YouTube Music link — covers the entity types Rustify can open (E40).
 * Mirror of [SpotifyLink]. Used by the "+" paste button (YTM search), the incoming
 * deep-link handler, and share.
 */
sealed class YtmLink {
    data class Track(val videoId: String) : YtmLink()
    data class Album(val browseId: String) : YtmLink()
    data class Artist(val channelId: String) : YtmLink()
    data class Playlist(val playlistId: String) : YtmLink()
}

/**
 * Unified parser for YouTube / YouTube Music URLs. This is the single source of truth —
 * [com.varuna.rustify.MainActivity.extractDeepLink] delegates its inline YTM routing here so
 * the deep-link, share and paste paths cannot diverge.
 *
 * Accepts:
 *  - https://music.youtube.com/watch?v=VIDEOID        → Track
 *  - https://www.youtube.com/watch?v=VIDEOID          → Track
 *  - https://youtu.be/VIDEOID                         → Track
 *  - https://music.youtube.com/...?list=PLAYLISTID    → Playlist
 *  - https://music.youtube.com/playlist?list=ID       → Playlist
 *  - https://music.youtube.com/browse/BROWSEID        → Album  (MPRE...)
 *  - https://music.youtube.com/channel/CHANNELID      → Artist (UC...)
 */
object YtMusicLinkParser {

    private val ytmHosts = setOf("music.youtube.com", "www.youtube.com", "youtube.com")

    fun parse(text: String?): YtmLink? {
        if (text.isNullOrBlank()) return null
        // Pull the first URL-looking token out of arbitrary shared text.
        val raw = Regex("""https?://\S+""").find(text)?.value ?: text.trim()
        val uri = try { Uri.parse(raw) } catch (e: Exception) { return null }
        val host = uri.host ?: return null

        if (host == "youtu.be") {
            val v = uri.lastPathSegment
            return if (!v.isNullOrBlank()) YtmLink.Track(v) else null
        }
        if (host !in ytmHosts) return null

        val v = uri.getQueryParameter("v")
        val list = uri.getQueryParameter("list")
        val first = uri.pathSegments.firstOrNull()
        val second = uri.pathSegments.getOrNull(1)
        return when {
            !v.isNullOrBlank() -> YtmLink.Track(v)
            !list.isNullOrBlank() -> YtmLink.Playlist(list)
            first == "playlist" && !second.isNullOrBlank() -> YtmLink.Playlist(second)
            first == "browse" && !second.isNullOrBlank() -> YtmLink.Album(second)
            first == "channel" && !second.isNullOrBlank() -> YtmLink.Artist(second)
            else -> null
        }
    }

    /** Deep-link token ("ytmtrack:ID" / "ytmalbum:ID" / ...) or null. Mirrors extractSpotifyLink. */
    fun toDeepLinkToken(text: String?): String? = when (val link = parse(text)) {
        is YtmLink.Track -> "ytmtrack:${link.videoId}"
        is YtmLink.Album -> "ytmalbum:${link.browseId}"
        is YtmLink.Artist -> "ytmartist:${link.channelId}"
        is YtmLink.Playlist -> "ytmplaylist:${link.playlistId}"
        null -> null
    }

    /** Canonical YTM URL for an entity — used by outgoing share. */
    fun canonicalUrl(link: YtmLink): String = when (link) {
        is YtmLink.Track -> "https://music.youtube.com/watch?v=${link.videoId}"
        is YtmLink.Album -> "https://music.youtube.com/browse/${link.browseId}"
        is YtmLink.Artist -> "https://music.youtube.com/channel/${link.channelId}"
        is YtmLink.Playlist -> "https://music.youtube.com/playlist?list=${link.playlistId}"
    }
}
