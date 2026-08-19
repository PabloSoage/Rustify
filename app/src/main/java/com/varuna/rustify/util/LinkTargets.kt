package com.varuna.rustify.util

import android.util.Log
import com.varuna.rustify.bridge.NativeEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * A link Rustify can act on — the Kotlin face of `core_engine/src/links/`.
 *
 * The parsing itself lives in Rust (point F). This file is the typed surface over it, exactly as
 * [com.varuna.rustify.bridge.TrackRef] is over `TrackId`: an exhaustive `when` here is checked by
 * the compiler, and the regexes that used to live in four separate Kotlin files are gone.
 *
 * Crossing JNI for a link looks extravagant until you notice what it buys: one implementation
 * instead of four, and one that Windows and iOS inherit. It is a pure in-memory call — no network,
 * no `block_on` — so it is safe to make from the main thread, which is where the deep-link handler
 * runs.
 */
sealed interface LinkTarget {
    val id: String

    data class SpotifyTrack(override val id: String) : LinkTarget
    data class SpotifyAlbum(override val id: String) : LinkTarget
    data class SpotifyPlaylist(override val id: String) : LinkTarget
    data class SpotifyArtist(override val id: String) : LinkTarget
    data class YtmTrack(override val id: String) : LinkTarget
    data class YtmAlbum(override val id: String) : LinkTarget
    data class YtmArtist(override val id: String) : LinkTarget
    data class YtmPlaylist(override val id: String) : LinkTarget

    companion object {
        private const val TAG = "LinkTarget"

        /**
         * Reads a link out of arbitrary text — a share sheet hands over "look at this: https://…".
         *
         * Null means "not one of ours", never "unsafe": refusing hostile URLs is a different
         * question, answered by `addon::security`.
         */
        fun parse(text: String?): LinkTarget? {
            if (text.isNullOrBlank()) return null
            val raw = runCatching { NativeEngine.parseLinkNative(text) }
                .onFailure { Log.w(TAG, "parseLinkNative threw", it) }
                .getOrNull() ?: return null
            return runCatching {
                val json = JSONObject(raw)
                val id = json.optString("id")
                if (id.isBlank()) return null
                when (json.optString("kind")) {
                    "track" -> SpotifyTrack(id)
                    "album" -> SpotifyAlbum(id)
                    "playlist" -> SpotifyPlaylist(id)
                    "artist" -> SpotifyArtist(id)
                    "ytmtrack" -> YtmTrack(id)
                    "ytmalbum" -> YtmAlbum(id)
                    "ytmartist" -> YtmArtist(id)
                    "ytmplaylist" -> YtmPlaylist(id)
                    // A kind the core knows and this build does not. Ignoring it beats guessing.
                    else -> null
                }
            }.getOrElse {
                Log.w(TAG, "could not read the parsed link: $raw", it)
                null
            }
        }

        /**
         * Wraps a link so that tapping it opens Rustify.
         *
         * With a [host] the user controls and an `assetlinks.json` on it, the result is a verified
         * App Link. Without one it is the `rustify://` scheme, which works and is not verified.
         */
        fun wrap(url: String, host: String?): String =
            runCatching { NativeEngine.wrapLinkNative(url, host.orEmpty()) }
                .getOrElse {
                    Log.w(TAG, "wrapLinkNative threw", it)
                    url
                }

        /**
         * The link inside a Rustify wrapper URL, or null if it is not one.
         *
         * [knownHosts] is checked rather than trusted: an arbitrary site must not be able to hand
         * the app a link and have it treated as if it came from the user's own domain. Both the
         * wrapper form (`/r/?s=…`) and the older path form (`/r/track/ID`) are understood, because
         * links live in other people's messages long after the app that made them has changed.
         */
        fun unwrap(url: String, knownHosts: List<String>): String? {
            val hosts = JSONArray().also { array -> knownHosts.forEach { array.put(it) } }
            return runCatching { NativeEngine.unwrapLinkNative(url, hosts.toString()) }
                .onFailure { Log.w(TAG, "unwrapLinkNative threw", it) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
    }
}

/** `"track:ID"` — the token the app routes on internally. */
val LinkTarget.deepLinkToken: String
    get() = when (this) {
        is LinkTarget.SpotifyTrack -> "track:$id"
        is LinkTarget.SpotifyAlbum -> "album:$id"
        is LinkTarget.SpotifyPlaylist -> "playlist:$id"
        is LinkTarget.SpotifyArtist -> "artist:$id"
        is LinkTarget.YtmTrack -> "ytmtrack:$id"
        is LinkTarget.YtmAlbum -> "ytmalbum:$id"
        is LinkTarget.YtmArtist -> "ytmartist:$id"
        is LinkTarget.YtmPlaylist -> "ytmplaylist:$id"
    }

/** The public URL for this target — what outgoing share sends. */
val LinkTarget.canonicalUrl: String
    get() = when (this) {
        is LinkTarget.SpotifyTrack -> "https://open.spotify.com/track/$id"
        is LinkTarget.SpotifyAlbum -> "https://open.spotify.com/album/$id"
        is LinkTarget.SpotifyPlaylist -> "https://open.spotify.com/playlist/$id"
        is LinkTarget.SpotifyArtist -> "https://open.spotify.com/artist/$id"
        is LinkTarget.YtmTrack -> "https://music.youtube.com/watch?v=$id"
        is LinkTarget.YtmAlbum -> "https://music.youtube.com/browse/$id"
        is LinkTarget.YtmArtist -> "https://music.youtube.com/channel/$id"
        is LinkTarget.YtmPlaylist -> "https://music.youtube.com/playlist?list=$id"
    }
