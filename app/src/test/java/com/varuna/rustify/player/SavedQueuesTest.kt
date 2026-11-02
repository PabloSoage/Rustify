package com.varuna.rustify.player

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.player.SavedQueues.SavedQueue
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedQueuesTest {
    private fun q(name: String, at: Long = 0) = SavedQueue(name, emptyList(), savedAt = at)

    @Test
    fun `saving again under a name replaces it and moves it first`() {
        val list = SavedQueues.upsert(listOf(q("Gym"), q("Car")), q("gym", 5))
        assertEquals(listOf("gym", "Car"), list.map { it.name })
        assertEquals(listOf("Car"), SavedQueues.remove(list, "GYM").map { it.name })
    }

    @Test
    fun `the twenty-first queue pushes out the oldest`() {
        val full = (1..20).map { q("Q$it") }
        val list = SavedQueues.upsert(full, q("New"))
        assertEquals(SavedQueues.MAX, list.size)
        assertEquals("New", list.first().name)
        assertEquals("Q19", list.last().name)
    }

    @Test
    fun `an unnamed save takes the first free number`() {
        assertEquals("Queue 2", SavedQueues.defaultName(listOf(q("Queue 1"), q("queue 3")), "Queue"))
    }

    @Test
    fun `a saved queue survives the round trip with its tracks`() {
        val track = FullTrack(id = "spotify:track:x", name = "Song", durationMs = 1000)
        val list = listOf(SavedQueue("Car", listOf(track), index = 0, positionMs = 42_000, savedAt = 7))
        val serializer = ListSerializer(SavedQueue.serializer())
        assertEquals(list, RustifyJson.decodeFromString(serializer, RustifyJson.encodeToString(serializer, list)))
    }
}
