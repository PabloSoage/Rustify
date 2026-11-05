package com.varuna.rustify.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** Hand-queued songs play in the order they were added (first in, first out). */
class ManualQueueTest {

    /** Adds [id] the way the players do: behind the manual block, then remembers it as pending. */
    private fun add(queue: MutableList<String>, current: Int, pending: MutableList<String?>, id: String) {
        queue.add(ManualQueue.blockEnd(queue, current, pending), id)
        pending.add(id)
    }

    @Test
    fun additionsPlayInTheOrderTheyWereMade() {
        val queue = mutableListOf("now", "list1", "list2")
        val pending = mutableListOf<String?>()
        add(queue, 0, pending, "a")
        add(queue, 0, pending, "b")
        add(queue, 0, pending, "c")
        assertEquals(listOf("now", "a", "b", "c", "list1", "list2"), queue)
    }

    @Test
    fun aSongQueuedTwiceKeepsItsSecondCopyAfterTheFirstPlays() {
        // Seen on 5.0: one starting copy spent both, and the next addition went in front of the second.
        val queue = mutableListOf("now", "list1")
        val pending = mutableListOf<String?>()
        add(queue, 0, pending, "a")
        add(queue, 0, pending, "b")
        add(queue, 0, pending, "a")
        ManualQueue.consume(pending, "a") { it }
        assertEquals(listOf<String?>("b", "a"), pending)
        add(queue, 1, pending, "c")
        assertEquals(listOf("now", "a", "b", "a", "c", "list1"), queue)
    }

    @Test
    fun aGapInTheBlockDoesNotPutTheNextAdditionInFrontOfIt() {
        // "x" was dragged in between by hand: the block still ends after "b", not before it.
        val queue = listOf("now", "a", "x", "b", "list1")
        assertEquals(4, ManualQueue.blockEnd(queue, 0, listOf("a", "b")))
    }

    @Test
    fun theNearestCopyIsTheQueuedOneNotTheListsOwn() {
        val queue = listOf("now", "a", "list1", "list2", "a")
        assertEquals(2, ManualQueue.blockEnd(queue, 0, listOf("a")))
    }

    @Test
    fun withNothingPendingTheBlockIsEmpty() {
        assertEquals(3, ManualQueue.blockEnd(listOf("before", "x", "now", "list1"), 2, emptyList()))
    }

    @Test
    fun reconcilingDropsWhatIsNoLongerAheadCopyForCopy() {
        val pending = mutableListOf<String?>("a", "b", "a", "gone")
        ManualQueue.reconcile(pending, listOf("b", "a", "list1"))
        assertEquals(listOf<String?>("a", "b"), pending)
    }
}
