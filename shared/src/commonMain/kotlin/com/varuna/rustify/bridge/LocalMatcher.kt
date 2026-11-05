package com.varuna.rustify.bridge

import com.varuna.rustify.platform.Log
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Deciding whether a file on this phone *is* a given Spotify track — the Kotlin half of
 * `core_engine/src/matcher/local.rs`.
 *
 * There is no comparison here, on purpose. This file builds the JSON, calls across, and reads the
 * answer; the rule about what counts as the same recording lives in one place, in Rust, next to the
 * fold it needs.
 *
 * ## Why it moved
 *
 * The comparison used to be `SpotifyRepository.isLocalMatch`, over a `normalizeName` that trimmed,
 * lowercased and stripped `feat.` with four regexes and **did not fold accents** — the same defect
 * that six copies of `contains(query, ignoreCase = true)` had in `LibraryScreen` before local search
 * moved to the core. There, `bjork` did not find `Björk`. Here, a local copy of *Jóga* tagged `Joga`
 * simply never matched, so the track streamed instead of playing off the disk it was already on, and
 * nothing said so.
 *
 * ## Why register-then-ask
 *
 * A lookup runs over the whole local library. Sending one track across JNI per candidate would be
 * slower than the Kotlin it replaces, so the library goes over once, gets folded once, and stays;
 * a lookup then sends one track. It is the same split [NativeEngine.searchIndexNative] uses, for the
 * same reason.
 */
object LocalMatcher {

    /**
     * Hands the local library over. Call it whenever the list changes — it replaces what was there,
     * so a library that shrank does not keep offering files that are gone.
     *
     * Tracks with no id are dropped: an id is what a match is *for*.
     */
    /** What is registered right now: which list it came from and how big it was. */
    @Volatile
    private var registered: String? = null

    /**
     * Registers [tracks] under [source] unless that same source and size is already registered.
     *
     * There are two lists that can be the local library — the repository's, and the one read back
     * from the disk cache before the repository exists — and one registry in the core. Without this
     * they would overwrite each other on alternate lookups, folding the whole library each time.
     *
     * The signature is deliberately coarse. A rescan that replaced one file with another and left
     * the count identical would not re-register, and the stale id then resolves to nothing and the
     * track streams — the same outcome as no match, which is the safe direction to be wrong in.
     */
    fun ensureRegistered(source: String, tracks: List<FullTrack>) {
        val signature = "$source:${tracks.size}"
        if (registered == signature) return
        register(tracks)
        registered = signature
    }

    fun register(tracks: List<FullTrack>) {
        val items = buildJsonArray {
            for (track in tracks) {
                val id = track.id ?: continue
                add(payloadOf(track, id))
            }
        }
        runCatching { NativeEngine.indexLocalTracksNative(items.toString()) }
            .onFailure {
                Log.w(TAG, "could not register ${items.size} local tracks", it)
            }
    }

    /** What the core reads for one track (`matcher::local`), registered or asked about. */
    private fun payloadOf(track: FullTrack, id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", track.name)
        putJsonArray("artists") { track.artists.forEach { add(it.name) } }
        put("isrc", track.isrc)
        put("durationMs", track.durationMs)
    }

    /**
     * The id of the local file that is [track], or null.
     *
     * Null covers "nothing matches" and "nothing registered" alike, because they are the same answer
     * to the caller: play it the way you would have anyway.
     */
    fun matchId(track: FullTrack): String? {
        val payload = payloadOf(track, track.id ?: "")
        return runCatching {
            val answer = Json.parseToJsonElement(NativeEngine.findLocalMatchNative(payload.toString())) as? JsonObject
            // org.json's optString("id"), which this read before 5.x: absent or null is no match.
            when (val id = answer?.get("id")) {
                null, JsonNull -> null
                is JsonPrimitive -> id.content.takeIf { it.isNotBlank() }
                else -> null
            }
        }.getOrElse {
            Log.w(TAG, "local match lookup failed for ${track.id}", it)
            null
        }
    }

    private const val TAG = "LocalMatcher"
}
