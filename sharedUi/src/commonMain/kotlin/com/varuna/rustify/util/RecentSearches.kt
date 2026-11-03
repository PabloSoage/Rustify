package com.varuna.rustify.util

import com.varuna.rustify.platform.KeyValueStore
import org.json.JSONArray

/**
 * What the user searched lately, newest first (4.7), shown on the empty Search screen.
 *
 * A query is kept when it was meant: submitted from the keyboard or followed by opening a result.
 * The search-as-you-type debounce would otherwise store every half-typed word.
 */
object RecentSearches {
    private const val KEY = "recent_searches"
    const val MAX = 12

    fun list(prefs: KeyValueStore): List<String> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun add(prefs: KeyValueStore, query: String): List<String> = save(prefs, push(list(prefs), query))

    fun remove(prefs: KeyValueStore, query: String): List<String> = save(prefs, list(prefs).filterNot { it == query })

    fun clear(prefs: KeyValueStore): List<String> = save(prefs, emptyList())

    /** [query] goes first; an older copy of it (any case) is dropped; the list stays at [max]. */
    fun push(current: List<String>, query: String, max: Int = MAX): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return current
        return (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(max)
    }

    private fun save(prefs: KeyValueStore, items: List<String>): List<String> {
        prefs.putString(KEY, JSONArray(items).toString())
        return items
    }
}
