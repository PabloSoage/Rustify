package com.varuna.rustify.util

/**
 * Parsed Spotify link — covers the entity types Rustify can open.
 * Used by the "+" paste button, the incoming deep-link handler, and (optionally) share.
 */
sealed class SpotifyLink {
    data class Track(val id: String) : SpotifyLink()
    data class Album(val id: String) : SpotifyLink()
    data class Playlist(val id: String) : SpotifyLink()
    data class Artist(val id: String) : SpotifyLink()
}

/**
 * Unified parser for Spotify URLs/links. Replaces the 4 divergent regexes that lived in
 * `MainActivity.extractSpotifyIdFromUrl` and `SearchScreen` (E20).
 *
 * Accepts:
 *  - https://open.spotify.com/(intl-XX/)?{track|album|playlist|artist}/ID
 *  - https://spotify.link/ID  (short link; entity type unknown, returns null — handled by redirect)
 */
object SpotifyLinkParser {
    private val re =
        Regex("""open\.spotify\.com/(?:intl-[a-zA-Z]{2}/)?(track|album|playlist|artist)/([a-zA-Z0-9]+)""")

    fun parse(text: String): SpotifyLink? {
        val m = re.find(text) ?: return null
        val id = m.groupValues[2]
        return when (m.groupValues[1]) {
            "track" -> SpotifyLink.Track(id)
            "album" -> SpotifyLink.Album(id)
            "playlist" -> SpotifyLink.Playlist(id)
            "artist" -> SpotifyLink.Artist(id)
            else -> null
        }
    }

    /** Convenience: just the track id, or null. */
    fun parseTrackId(text: String): String? = (parse(text) as? SpotifyLink.Track)?.id
}