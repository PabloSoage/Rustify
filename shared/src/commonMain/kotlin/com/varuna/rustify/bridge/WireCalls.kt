// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/WireCalls.kt
package com.varuna.rustify.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes a bridge's answer as [T], or throws the failure it reports.
 *
 * Every bridge answers a failure as `{"success":false,"error":…,"kind":…}`; decoding that as a
 * `PaginatedResponse` would quietly give an empty page. This is `SpotifyRepository.decodeChecked`
 * for the platforms without that repository (desktop, iOS); the kind travels in the exception, so
 * `classifyError` and `retrying` decide as they do on the phone.
 */
inline fun <reified T> decodeCheckedWire(json: String): T {
    val element = RustifyJson.parseToJsonElement(json)
    val obj = element as? JsonObject
    if (obj != null && obj["success"]?.jsonPrimitive?.booleanOrNull == false) {
        throw SpotifyEngineException(
            obj["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown engine error",
            obj["kind"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }
    return RustifyJson.decodeFromJsonElement(element)
}

/**
 * Every page of a paged list, joined. A screen's list is how far someone scrolled; anything that
 * decides about the whole playlist asks for the whole playlist (`CLAUDE.md`, "A paged list is not
 * the list").
 *
 * The server's `nextOffset` is followed rather than `offset + limit`, because client-side filtering
 * can drop items from a page; an offset that does not advance ends the loop instead of spinning.
 *
 * Android still has its own loop in `SpotifyRepository.allPages`; moving that repository to shared
 * code (docs 25, T1.3) is when the two become this one.
 */
suspend fun <T> allPages(pageSize: Int = 50, fetch: suspend (limit: Int, offset: Int) -> PaginatedResponse<T>): List<T> {
    val all = mutableListOf<T>()
    var offset = 0
    while (true) {
        val page = fetch(pageSize, offset)
        all += page.items
        val next = page.nextOffset
        if (!page.hasMore || next == null || next <= offset) break
        offset = next
    }
    return all
}
