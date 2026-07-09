package com.varuna.rustify.util

import java.net.URLEncoder

/**
 * F1.A — Generador del "wrapper" Rustify (§4.A.3 del doc 40).
 *
 * Envuelve un link de Spotify en una URL propia del usuario para que, si su host aloja
 * `assetlinks.json`, el enlace se abra VERIFICADO en Rustify. Sin host configurado, cae al
 * custom scheme `rustify://<type>/<ID>` (track/album/playlist/artist, SIN verificación fuerte).
 */
object RustifyWrapperLink {

    /**
     * Envuelve [spotifyUrl].
     * @param host host propio del usuario (p.ej. "pablosoage.github.io"); null/blank → fallback scheme.
     * @return wrapper verificable `https://$host/r/?s=<enc>` si hay host; si no, `rustify://<type>/<ID>`
     *         (track/album/playlist/artist); o el propio [spotifyUrl] si no se puede parsear.
     */
    fun wrap(spotifyUrl: String, host: String?): String =
        if (!host.isNullOrBlank()) {
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
            } else spotifyUrl
        }
}
