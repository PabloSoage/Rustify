package com.varuna.rustify.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Whether the song a lyrics site answered with is the song playing — over `matcher::lyrics` in the
 * core, never a name comparison written here (`CLAUDE.md`: matching goes through the core).
 *
 * Musixmatch, LRCLIB's search and Genius are searches: they always answer their best match, and the
 * first one used to be shown whatever it was. A song the site does not have got somebody else's
 * lyrics, in whatever language that song was in.
 */
internal object LyricsMatch {

    fun accepts(
        artist: String,
        title: String,
        durationSec: Int,
        foundTitle: String?,
        foundArtists: List<String>,
        foundDurationSec: Int = 0,
    ): Boolean {
        if (foundTitle.isNullOrBlank()) return false
        val answer = runCatching {
            RustifyJson.parseToJsonElement(
                NativeEngine.lyricsMatchNative(
                    candidate(title, listOf(artist), durationSec).toString(),
                    candidate(foundTitle, foundArtists, foundDurationSec).toString(),
                )
            ) as? JsonObject
        }.getOrNull()
        return (answer?.get("match") as? JsonPrimitive)?.booleanOrNull == true
    }

    private fun candidate(name: String, artists: List<String>, durationSec: Int) = JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "artists" to JsonArray(artists.filter { it.isNotBlank() }.map { JsonPrimitive(it) }),
            "durationMs" to JsonPrimitive(durationSec.coerceAtLeast(0) * 1000),
        )
    )
}
