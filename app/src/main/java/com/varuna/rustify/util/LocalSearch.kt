package com.varuna.rustify.util

import android.util.Log
import com.varuna.rustify.bridge.NativeEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Searching a list you already hold — point J.
 *
 * ## Why this exists
 *
 * The evaluation rated local search "low value: we already have it in Kotlin". What it missed is
 * that there was not *a* local search: there were six `contains(query, ignoreCase = true)`
 * predicates in `LibraryScreen`, one per tab, each matching a different set of fields. That is the
 * shape that cost this project four diverging link parsers, and it goes the same way — one
 * implementation, in `core_engine/src/search/`, and the screens ask.
 *
 * Moving it also buys two things `contains` cannot do, and both are things a person notices:
 *
 *   * **Accents stop mattering.** `bjork` finds `Björk`; `corazon` finds `Corazón`. Before, they
 *     simply did not, which reads as the app not having the song.
 *   * **Word order stops mattering.** `dark side moon` finds `The Dark Side of the Moon`.
 *
 * And results are **ranked** rather than merely filtered, which matters the moment word order is
 * free: without it, an album whose *artist* contains the word sits above the album actually named
 * it.
 *
 * ## Why an index rather than a predicate
 *
 * A filter runs on every keystroke over a list that can be thousands of items. A per-item bridge
 * call would be slower than the `contains` it replaces, and "the new search is laggier" is not an
 * improvement however much better it ranks.
 *
 * So the expensive half — folding every field of every item — happens once, in [index], when a
 * screen loads its list. A keystroke then sends one short string and gets back ids. Both calls are
 * pure and in-memory on the Rust side, so neither is `suspend` and neither leaves the thread it was
 * called on: a search that hopped to `Dispatchers.IO` would arrive after the next keystroke.
 */
object LocalSearch {

    private const val TAG = "LocalSearch"

    /**
     * Hands `items` over under [name], folded and ready to query.
     *
     * [fields] are in **importance order**: `fields[0]` is what the thing is called, and a hit there
     * outranks a hit in an artist or an owner. Items with no id are skipped — there would be nothing
     * to map a result back to.
     */
    fun <T> index(name: String, items: List<T>, id: (T) -> String?, fields: (T) -> List<String>) {
        val array = JSONArray()
        for (item in items) {
            val itemId = id(item)
            if (itemId.isNullOrBlank()) continue
            array.put(JSONObject().apply {
                put("id", itemId)
                put("fields", JSONArray().also { f -> fields(item).forEach { f.put(it) } })
            })
        }
        runCatching { NativeEngine.searchIndexNative(name, array.toString()) }
            .onFailure { Log.w(TAG, "could not index $name", it) }
    }

    /**
     * The items matching [query], best first.
     *
     * A blank query returns [items] untouched — including its original order, and without crossing
     * the bridge at all. An empty search box is not a filter, and it is also the common case.
     *
     * If the core cannot answer, the whole list comes back rather than nothing: a search box that
     * empties the screen is worse than one that does not narrow it.
     */
    fun <T> filter(name: String, items: List<T>, query: String, id: (T) -> String?): List<T> {
        if (query.isBlank()) return items

        val answer = runCatching { NativeEngine.searchQueryNative(name, query, 0) }
            .getOrElse {
                Log.w(TAG, "could not search $name", it)
                return items
            }

        val ordered = runCatching {
            val array = JSONArray(answer)
            (0 until array.length()).map { array.optString(it) }
        }.getOrElse {
            Log.w(TAG, "could not read the result for $name: $answer", it)
            return items
        }

        // A library legitimately contains the same id twice — a playlist that repeats a song, an
        // album saved and also in a playlist. The index then holds two entries for it and the core
        // returns it twice, so the copies are **consumed** one per result rather than looked up: a
        // plain map would drop all but one, and a `flatMap` would return four for two.
        val byId = HashMap<String, ArrayDeque<T>>()
        for (item in items) {
            val key = id(item) ?: continue
            if (key.isBlank()) continue
            byId.getOrPut(key) { ArrayDeque() }.addLast(item)
        }
        return ordered.mapNotNull { byId[it]?.removeFirstOrNull() }
    }

    /** Drops an index, for a screen that is going away. */
    fun forget(name: String) {
        runCatching { NativeEngine.searchForgetNative(name) }
            .onFailure { Log.w(TAG, "could not forget $name", it) }
    }

    // Index names. Constants rather than literals at each call site, because a typo here is not an
    // error — it is a search that silently returns nothing.
    const val PLAYLISTS = "library.playlists"
    const val ALBUMS = "library.albums"
    const val ARTISTS = "library.artists"
    const val TRACKS = "library.tracks"
    const val LOCAL = "library.local"
}
