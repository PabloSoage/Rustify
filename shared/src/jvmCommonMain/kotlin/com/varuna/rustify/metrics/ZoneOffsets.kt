// shared/src/jvmCommonMain/kotlin/com/varuna/rustify/metrics/ZoneOffsets.kt
package com.varuna.rustify.metrics

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.time.Instant
import java.time.ZoneId

/**
 * The device zone as the offsets it has had, `[[fromMs, offsetMs], …]`, from 1970 to two years
 * ahead — what the core needs to put each play in its local hour and day, with DST.
 *
 * Moved out of Android's `ListeningTracker` in 5.x so the desktop's stats read the same hours; it
 * is pure `java.time`, which both JVM targets have. iOS will need its own, from `NSTimeZone`.
 */
fun zoneOffsetsJson(zone: ZoneId = ZoneId.systemDefault()): String {
    val rules = zone.rules
    val start = Instant.EPOCH
    val end = Instant.now().plusSeconds(2L * 366 * 86_400)
    return buildJsonArray {
        add(buildJsonArray {
            add(JsonPrimitive(Long.MIN_VALUE))
            add(JsonPrimitive(rules.getOffset(start).totalSeconds * 1000L))
        })
        var t = rules.nextTransition(start)
        while (t != null && t.instant.isBefore(end)) {
            val change = t
            add(buildJsonArray {
                add(JsonPrimitive(change.instant.toEpochMilli()))
                add(JsonPrimitive(change.offsetAfter.totalSeconds * 1000L))
            })
            t = rules.nextTransition(t.instant)
        }
    }.toString()
}
