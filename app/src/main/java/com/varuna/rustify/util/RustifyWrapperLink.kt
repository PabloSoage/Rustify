package com.varuna.rustify.util

import java.net.URLEncoder

/**
 * Generator for the Rustify "wrapper" link.
 *
 * Wraps a Spotify link in a URL owned by the user so that, if their host serves `assetlinks.json`,
 * the link opens VERIFIED in Rustify. Without a configured host, it falls back to the custom scheme
 * `rustify://<type>/<ID>` (track/album/playlist/artist, WITHOUT strong verification).
 */
object RustifyWrapperLink {

    /**
     * Wraps [spotifyUrl].
     * @param host the user's own host (e.g. "pablosoage.github.io"); null/blank → fallback scheme.
     * @return a verifiable wrapper `https://$host/r/?s=<enc>` if a host is set; otherwise
     *         `rustify://<type>/<ID>` (track/album/playlist/artist); or [spotifyUrl] itself if it
     *         cannot be parsed.
     */
    fun wrap(spotifyUrl: String, host: String?): String =
        if (!host.isNullOrBlank()) {
            // Verified host mode: wraps ANY URL (Spotify or YTM) without parsing.
            "https://$host/r/?s=${URLEncoder.encode(spotifyUrl, "UTF-8")}"
        } else {
            val link = SpotifyLinkParser.parse(spotifyUrl)
            if (link != null) {
                val type = when (link) {
                    is SpotifyLink.Track -> "track"
                    is SpotifyLink.Album -> "album"
                    is SpotifyLink.Playlist -> "playlist"
                    is SpotifyLink.Artist -> "artist"
                }
                val id = when (link) {
                    is SpotifyLink.Track -> link.id
                    is SpotifyLink.Album -> link.id
                    is SpotifyLink.Playlist -> link.id
                    is SpotifyLink.Artist -> link.id
                }
                "rustify://$type/$id"
            } else {
                // Hostless fallback for YTM links → rustify://ytmtrack/VIDEOID (etc.).
                val ytm = YtMusicLinkParser.parse(spotifyUrl)
                when (ytm) {
                    is YtmLink.Track -> "rustify://ytmtrack/${ytm.videoId}"
                    is YtmLink.Album -> "rustify://ytmalbum/${ytm.browseId}"
                    is YtmLink.Artist -> "rustify://ytmartist/${ytm.channelId}"
                    is YtmLink.Playlist -> "rustify://ytmplaylist/${ytm.playlistId}"
                    null -> spotifyUrl
                }
            }
        }
}
