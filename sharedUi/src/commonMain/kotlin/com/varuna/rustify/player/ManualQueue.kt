package com.varuna.rustify.player

/**
 * The arithmetic of the hand-made queue ("Add to queue"), by track id, apart from the players so it
 * can be tested and shared: the phone's `AudioPlayerService` and [EnginePlayerController]. Songs
 * queued by hand play in the order they were added, right after the current one: each new one goes
 * behind the ones still waiting, never in front of them.
 */
object ManualQueue {

    /**
     * Where the manual block that follows [currentIdx] in [ids] ends: one past the last row still owed
     * to [pending] (the manual queue, in the order it was added).
     *
     * Every pending entry is matched to its nearest unclaimed copy ahead of the current track, and the
     * block ends after the furthest one. It used to stop at the first row that was not owed, which
     * read a single gap — an entry dragged elsewhere, or a queued song that the list itself also
     * holds — as "the block ends here", and every later addition landed in front of songs queued
     * before it: newest first.
     */
    fun blockEnd(ids: List<String?>, currentIdx: Int, pending: List<String?>): Int {
        val owed = HashMap<String, Int>()
        pending.forEach { id -> if (id != null) owed[id] = (owed[id] ?: 0) + 1 }
        var end = currentIdx + 1
        var i = currentIdx + 1
        while (i < ids.size && owed.isNotEmpty()) {
            val id = ids[i]
            val left = id?.let { owed[it] }
            if (left != null) {
                if (left == 1) owed.remove(id) else owed[id] = left - 1
                end = i + 1
            }
            i++
        }
        return end
    }

    /**
     * [pending] once the track [id] has started: **one** entry goes, the first. Removing every entry
     * with that id spent the other copies of a song queued twice, and the block they still occupy in
     * the queue was then read as not manual at all.
     */
    fun <T> consume(pending: MutableList<T>, id: String, idOf: (T) -> String?) {
        val at = pending.indexOfFirst { idOf(it) == id }
        if (at >= 0) pending.removeAt(at)
    }

    /**
     * Keeps in [pending] only what is still waiting in [ahead] (the queue after the current track),
     * copy for copy: a song that started, was removed or was jumped past is no longer owed a place.
     */
    fun reconcile(pending: MutableList<String?>, ahead: List<String?>) {
        val left = ahead.filterNotNull().toMutableList()
        pending.retainAll { id -> id != null && left.remove(id) }
    }
}
