package com.varuna.rustify.util

import android.content.Context
import org.json.JSONArray

/**
 * What the user searched lately, newest first (4.7), shown on the empty Search screen.
 *
 * A query is kept when it was meant: submitted from the keyboard or followed by opening a result.
 * The search-as-you-type debounce would otherwise store every half-typed word.
 */
object RecentSearches {
    private const val PREFS = "rustify_settings"
    private const val KEY = "recent_searches"
    const val MAX = 12

    fun list(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun add(context: Context, query: String): List<String> = save(context, push(list(context), query))

    fun remove(context: Context, query: String): List<String> = save(context, list(context).filterNot { it == query })

    fun clear(context: Context): List<String> = save(context, emptyList())

    /** [query] goes first; an older copy of it (any case) is dropped; the list stays at [max]. */
    fun push(current: List<String>, query: String, max: Int = MAX): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return current
        return (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(max)
    }

    private fun save(context: Context, items: List<String>): List<String> {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(items).toString()).apply()
        return items
    }
}
