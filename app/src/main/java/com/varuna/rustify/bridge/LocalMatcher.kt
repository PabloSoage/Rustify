package com.varuna.rustify.bridge

import org.json.JSONArray
import org.json.JSONObject

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
        val items = JSONArray()
        for (track in tracks) {
            val id = track.id ?: continue
            items.put(
                JSONObject().apply {
                    put("id", id)
                    put("name", track.name)
                    put("artists", JSONArray().apply { track.artists.forEach { put(it.name) } })
                    put("isrc", track.isrc)
                    put("durationMs", track.durationMs)
                }
            )
        }
        runCatching { NativeEngine.indexLocalTracksNative(items.toString()) }
            .onFailure {
                android.util.Log.w(TAG, "could not register ${items.length()} local tracks", it)
            }
    }

    /**
     * The id of the local file that is [track], or null.
     *
     * Null covers "nothing matches" and "nothing registered" alike, because they are the same answer
     * to the caller: play it the way you would have anyway.
     */
    fun matchId(track: FullTrack): String? {
        val payload = JSONObject().apply {
            put("id", track.id ?: "")
            put("name", track.name)
            put("artists", JSONArray().apply { track.artists.forEach { put(it.name) } })
            put("isrc", track.isrc)
            put("durationMs", track.durationMs)
        }
        return runCatching {
            val answer = JSONObject(NativeEngine.findLocalMatchNative(payload.toString()))
            answer.optString("id").takeIf { it.isNotBlank() }
        }.getOrElse {
            android.util.Log.w(TAG, "local match lookup failed for ${track.id}", it)
            null
        }
    }

    private const val TAG = "LocalMatcher"
}
