package com.varuna.rustify.player

import android.content.Context
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.RustifyJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Several saved queues (4.10, Musicolet 3.1): name the queue you have, start another, come back to
 * the first where you left it.
 *
 * Kept apart from `playback_state.json` on purpose. That file is the live session and has already
 * lost queues to a bad write (`13-playback-state-persistence.md`); a new feature does not get to
 * change its shape. A saved queue is a snapshot with the same `FullTrack` JSON, so loading one needs
 * no network; `saved_queues.json` is written the same atomic way. At most [MAX] — Musicolet's 20 —
 * the oldest going first. The list logic is pure and tested on the JVM.
 */
object SavedQueues {
    const val MAX = 20
    private const val FILE = "saved_queues.json"

    @Serializable
    data class SavedQueue(
        val name: String,
        val tracks: List<FullTrack>,
        val index: Int = 0,
        val positionMs: Long = 0L,
        val savedAt: Long = 0L,
    )

    /** [queue] first; one of the same name (ignoring case) replaced; at most [MAX]. */
    fun upsert(list: List<SavedQueue>, queue: SavedQueue): List<SavedQueue> =
        (listOf(queue) + list.filterNot { it.name.equals(queue.name, ignoreCase = true) }).take(MAX)

    fun remove(list: List<SavedQueue>, name: String): List<SavedQueue> =
        list.filterNot { it.name.equals(name, ignoreCase = true) }

    /** "Queue 3": the first number not taken, for a save without a name. */
    fun defaultName(list: List<SavedQueue>, prefix: String): String =
        generateSequence(1) { it + 1 }.map { "$prefix $it" }
            .first { n -> list.none { it.name.equals(n, ignoreCase = true) } }

    private val serializer = ListSerializer(SavedQueue.serializer())

    @Synchronized
    fun load(context: Context): List<SavedQueue> = runCatching {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) emptyList() else RustifyJson.decodeFromString(serializer, file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun store(context: Context, list: List<SavedQueue>) {
        runCatching {
            val text = RustifyJson.encodeToString(serializer, list)
            val tmp = File(context.filesDir, "$FILE.tmp")
            val dst = File(context.filesDir, FILE)
            tmp.writeText(text)
            if (!tmp.renameTo(dst)) { dst.writeText(text); tmp.delete() }
        }
    }
}
