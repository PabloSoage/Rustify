package com.varuna.rustify.util

import android.util.Log
import com.varuna.rustify.R
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.SimpleAlbum
import org.json.JSONArray
import org.json.JSONObject

/**
 * Grouping releases by when they came out — point K.
 *
 * The evaluation was right that there is nothing to import from Stremio's `calendar`: theirs is
 * about episodes airing, ours is about albums coming out. It was also right that "a calendar of
 * releases by the artists you follow" is the natural evolution of the New Releases screen, so this
 * groups that screen rather than adding another one.
 *
 * The part worth having in the core is the part that is easy to get wrong and impossible to notice:
 * **Spotify release dates come in three precisions** — `2024-03-15`, `2024-03`, `2024`. Treating all
 * three as a day puts everything "from 1975" on the 1st of January 1975, sorted against albums that
 * really came out that day. That comparison lives in `core_engine/src/calendar/` with its own tests;
 * this side only turns the answer into headings.
 */
object ReleaseCalendar {

    private const val TAG = "ReleaseCalendar"

    /** One heading and the albums under it. */
    data class Group(val titleRes: Int, val albums: List<SimpleAlbum>)

    private fun headingFor(bucket: String): Int = when (bucket) {
        "upcoming" -> R.string.calendar_upcoming
        "today" -> R.string.calendar_today
        "this_week" -> R.string.calendar_this_week
        "this_month" -> R.string.calendar_this_month
        "this_year" -> R.string.calendar_this_year
        "older" -> R.string.calendar_older
        else -> R.string.calendar_undated
    }

    /**
     * Groups [albums] newest first, headings in calendar order.
     *
     * If the core cannot answer, everything comes back under one undated heading in the order it
     * arrived — a list that is not grouped is still a list, and it is what this screen showed
     * before.
     */
    fun group(albums: List<SimpleAlbum>, nowMs: Long): List<Group> {
        if (albums.isEmpty()) return emptyList()

        val entries = JSONArray()
        for (album in albums) {
            entries.put(JSONObject().apply {
                put("id", album.id)
                put("release_date", album.releaseDate.orEmpty())
                put("release_date_precision", album.releaseDatePrecision.orEmpty())
            })
        }

        val placed = runCatching {
            val answer = NativeEngine.arrangeReleasesNative(entries.toString(), nowMs)
            val array = JSONArray(answer)
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.getOrElse {
            Log.w(TAG, "could not arrange the releases", it)
            return listOf(Group(R.string.calendar_undated, albums))
        }

        // The same album can appear twice in a paged feed when a page boundary shifts under a new
        // release. The core then places it twice, so the copies are **consumed** one per placement
        // rather than looked up — otherwise two copies come back as four.
        val byId = HashMap<String, ArrayDeque<SimpleAlbum>>()
        for (album in albums) {
            byId.getOrPut(album.id) { ArrayDeque() }.addLast(album)
        }
        val ordered = LinkedHashMap<String, MutableList<SimpleAlbum>>()
        for (item in placed) {
            val bucket = item.optString("bucket").ifBlank { "unknown" }
            val album = byId[item.optString("id")]?.removeFirstOrNull() ?: continue
            ordered.getOrPut(bucket) { mutableListOf() }.add(album)
        }
        // The core already sorted newest first, so the buckets come out in calendar order for free —
        // no second ordering to keep in step with the first.
        return ordered.map { (bucket, list) -> Group(headingFor(bucket), list) }
    }
}
