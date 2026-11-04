package com.varuna.rustify.player

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.readTextOrNull
import com.varuna.rustify.ui.writeTextAtomically
import com.varuna.rustify.util.Lock
import com.varuna.rustify.util.locked
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Several saved queues (4.10, Musicolet 3.1): name the queue you have, start another, come back to
 * the first where you left it.
 *
 * Kept apart from `playback_state.json` on purpose. That file is the live session and has already
 * lost queues to a bad write (`13-playback-state-persistence.md`); a new feature does not get to
 * change its shape. A saved queue is a snapshot with the same `FullTrack` JSON, so loading one needs
 * no network; `saved_queues.json` is written the same atomic way, in the folder the platform keeps its
 * files in (Android's `filesDir`). At most [MAX] — Musicolet's 20 —
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
    private val lock = Lock()

    fun load(dir: AppFile): List<SavedQueue> = lock.locked {
        runCatching {
            val text = dir.child(FILE).readTextOrNull()
            if (text == null) emptyList<SavedQueue>() else RustifyJson.decodeFromString(serializer, text)
        }.getOrDefault(emptyList())
    }

    fun store(dir: AppFile, list: List<SavedQueue>) {
        lock.locked {
            runCatching { dir.child(FILE).writeTextAtomically(RustifyJson.encodeToString(serializer, list)) }
        }
    }
}
