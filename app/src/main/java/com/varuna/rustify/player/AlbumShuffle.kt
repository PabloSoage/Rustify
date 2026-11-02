package com.varuna.rustify.player

import android.content.Context
import kotlin.random.Random

/**
 * Shuffle by album (4.10): the albums come in random order, each one played through in its own
 * order. For a library of albums meant to be heard whole — a live record, a concept album — song
 * shuffle scatters exactly what makes them worth hearing.
 *
 * Kotlin, like the song shuffle it sits next to: the order is Rustify's own (the hand-queued block
 * keeps its place), and the core's reducer deliberately holds no random source (`CLAUDE.md`).
 * Pure, so it is tested on the JVM; [AudioPlayerService] decides when it applies.
 */
object AlbumShuffle {
    /**
     * [tracks] rearranged by album. A track's album is [albumOf]; tracks without one are an album of
     * their own each. Within an album the order of [tracks] is kept; [firstAlbum], when given, goes
     * first — what is left of the album the current song belongs to.
     */
    fun <T> arrange(
        tracks: List<T>,
        albumOf: (T) -> String?,
        firstAlbum: String? = null,
        random: Random = Random.Default,
    ): List<T> {
        val groups = LinkedHashMap<String, MutableList<T>>()
        tracks.forEachIndexed { i, t ->
            // A track with no album must not be lumped with the others that have none.
            val key = albumOf(t)?.takeIf { it.isNotBlank() } ?: "\u0000single:$i"
            groups.getOrPut(key) { mutableListOf() }.add(t)
        }
        val first = firstAlbum?.takeIf { it.isNotBlank() }?.let { groups.remove(it) }.orEmpty()
        return first + groups.values.shuffled(random).flatten()
    }

    private const val KEY = "shuffle_by_album"

    private fun prefs(context: Context) = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY, false)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY, on).apply()
}
