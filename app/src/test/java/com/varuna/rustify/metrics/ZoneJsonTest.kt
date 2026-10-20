package com.varuna.rustify.metrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The zone the core uses to put each play in its local hour and day. If this table is wrong, every
 * play on the clock and the weekday chart moves by the offset — silently, which is why it is tested.
 */
class ZoneJsonTest {

    private fun table(zone: String): List<Pair<Long, Long>> =
        Json.parseToJsonElement(ListeningTracker.zoneJson(ZoneId.of(zone))).jsonArray.map {
            val pair = it.jsonArray
            pair[0].jsonPrimitive.long to pair[1].jsonPrimitive.long
        }

    @Test
    fun `a zone with daylight saving lists its changes, starting from the beginning of time`() {
        val madrid = table("Europe/Madrid")
        assertEquals(Long.MIN_VALUE, madrid.first().first)
        // Two changes a year since the 1970s.
        assertTrue("got ${madrid.size} entries", madrid.size > 80)
        assertTrue(madrid.map { it.second }.toSet().containsAll(setOf(3_600_000L, 7_200_000L)))
        assertTrue("sorted", madrid.zipWithNext().all { (a, b) -> a.first < b.first })
    }

    @Test
    fun `a zone without daylight saving is a single offset`() {
        assertEquals(listOf(Long.MIN_VALUE to 0L), table("UTC"))
    }
}
