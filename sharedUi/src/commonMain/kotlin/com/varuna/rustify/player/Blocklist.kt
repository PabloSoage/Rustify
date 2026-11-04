package com.varuna.rustify.player

import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.flow.MutableStateFlow
import com.varuna.rustify.util.Lock
import com.varuna.rustify.util.locked
import com.varuna.rustify.util.optObject
import com.varuna.rustify.util.optString
import com.varuna.rustify.util.optStringOrNull
import com.varuna.rustify.util.parseArray
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile

/**
 * Songs and artists the user never wants to hear (`23` §2; Spotube 6 calls it the blacklist).
 *
 * A blocked one is never added by the radio, and the queue steps over it when it moves on by
 * itself or with next/previous. Choosing it by hand still plays it: the user asked for that one
 * song, and a tap that does nothing would look like a bug.
 *
 * Kept with the name beside the id, so the list in Settings can say what it holds without a
 * request per row.
 */
object Blocklist {
    private const val KEY_TRACKS = "blocklist_tracks"
    private const val KEY_ARTISTS = "blocklist_artists"

    data class Entry(val id: String, val name: String, val detail: String = "")

    data class Lists(val tracks: List<Entry> = emptyList(), val artists: List<Entry> = emptyList()) {
        private val trackIds = tracks.mapTo(HashSet()) { it.id }
        private val artistIds = artists.mapTo(HashSet()) { it.id }

        fun blocksTrack(id: String?) = id != null && id in trackIds
        fun blocksArtist(id: String?) = id != null && id in artistIds

        /** Blocked itself, or by any of its artists. */
        fun blocks(trackId: String?, artistIds: List<String>): Boolean =
            blocksTrack(trackId) || artistIds.any { blocksArtist(it) }

        fun blocks(track: FullTrack): Boolean = blocks(track.id, track.artists.map { it.id })
    }

    private val _lists = MutableStateFlow(Lists())
    val lists: StateFlow<Lists> = _lists

    @Volatile private var loaded = false
    private val lock = Lock()

    fun current(prefs: KeyValueStore): Lists {
        if (!loaded) lock.locked {
            if (!loaded) {
                _lists.value = Lists(decode(prefs.getString(KEY_TRACKS)), decode(prefs.getString(KEY_ARTISTS)))
                loaded = true
            }
        }
        return _lists.value
    }

    fun toggleTrack(prefs: KeyValueStore, track: FullTrack) {
        val id = track.id ?: return
        val l = current(prefs)
        val tracks = if (l.blocksTrack(id)) l.tracks.filterNot { it.id == id }
            else l.tracks + Entry(id, track.name, track.artists.joinToString(", ") { it.name })
        save(prefs, l.copy(tracks = tracks))
    }

    fun toggleArtist(prefs: KeyValueStore, id: String, name: String) {
        if (id.isBlank()) return
        val l = current(prefs)
        val artists = if (l.blocksArtist(id)) l.artists.filterNot { it.id == id } else l.artists + Entry(id, name)
        save(prefs, l.copy(artists = artists))
    }

    private fun save(prefs: KeyValueStore, l: Lists) {
        _lists.value = l
        prefs.putString(KEY_TRACKS, encode(l.tracks))
        prefs.putString(KEY_ARTISTS, encode(l.artists))
    }

    /**
     * The first index from [start] on, stepping by [step], whose track is allowed; null when the
     * rest of the queue in that direction is all blocked.
     */
    fun firstAllowed(size: Int, start: Int, step: Int, blocked: (Int) -> Boolean): Int? {
        var i = start
        while (i in 0 until size) {
            if (!blocked(i)) return i
            i += step
        }
        return null
    }

    fun encode(entries: List<Entry>): String = buildJsonArray {
        entries.forEach { addJsonObject { put("id", it.id); put("name", it.name); put("detail", it.detail) } }
    }.toString()

    // An entry without an id is not one: org.json's getString threw, and the whole list read as empty.
    fun decode(raw: String?): List<Entry> = runCatching {
        val a = parseArray(raw ?: return emptyList())
        a.map { it.optObject() ?: error("not an object") }.map { o ->
            Entry(o["id"].optStringOrNull() ?: error("no id"), o["name"].optString(), o["detail"].optString())
        }
    }.getOrDefault(emptyList())
}
