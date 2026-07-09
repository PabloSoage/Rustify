package com.varuna.rustify.util

import java.net.URLEncoder

/**
 * F1.A — Generador del "wrapper" Rustify (§4.A.3 del doc 40).
 *
 * Envuelve un link de Spotify en una URL propia del usuario para que, si su host aloja
 * `assetlinks.json`, el enlace se abra VERIFICADO en Rustify. Sin host configurado, cae al
 * custom scheme `rustify://track/ID` (SIN verificación fuerte, sólo track por simplicidad del
 * scheme actual — ver MainActivity.extractDeepLink).
 */
object RustifyWrapperLink {

    /**
     * Envuelve [spotifyUrl].
     * @param host host propio del usuario (p.ej. "usuario.github.io"); null/blank → fallback scheme.
     * @return wrapper verificable `https://$host/r/?s=<enc>` si hay host; si no, `rustify://track/ID`
     *         (o el propio [spotifyUrl] si no se puede extraer un track id).
     */
    fun wrap(spotifyUrl: String, host: String?): String =
        if (!host.isNullOrBlank()) {
            "https://$host/r/?s=${URLEncoder.encode(spotifyUrl, "UTF-8")}"
        } else {
            SpotifyLinkParser.parseTrackId(spotifyUrl)?.let { "rustify://track/$it" } ?: spotifyUrl
        }
}
