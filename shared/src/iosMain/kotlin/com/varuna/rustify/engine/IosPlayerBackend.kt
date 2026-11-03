// shared/src/iosMain/kotlin/com/varuna/rustify/engine/IosPlayerBackend.kt
package com.varuna.rustify.engine

import com.varuna.rustify.bridge.CoreCall
import com.varuna.rustify.bridge.FullTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [PlayerBackend] on iOS: everything through the C ABI (`rustify_call`), the engine included — the
 * core built with `desktop-audio`, whose cpal output is CoreAudio here (tools/ios/build-core.sh).
 */
object IosPlayerBackend : PlayerBackend {
    override suspend fun startEngine(): String = CoreCall.call("engineStart")

    override fun send(command: EngineCommand) {
        // Queues and returns: memory only, fine on the main thread.
        CoreCall.callBlocking(
            "engineSend",
            buildJsonObject { put("command", Json.parseToJsonElement(command.toJson())) },
        )
    }

    override fun events(): Flow<EngineEvent> = flow {
        while (currentCoroutineContext().isActive) {
            val raw = CoreCall.callBlocking("enginePoll", buildJsonObject { put("timeoutMs", 250) })
            EngineEvent.parse(raw)?.let { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    override fun reduce(stateJson: String, actionJson: String): String = CoreCall.callBlocking(
        "playerReduce",
        JsonObject(
            mapOf(
                "state" to Json.parseToJsonElement(stateJson),
                "action" to Json.parseToJsonElement(actionJson),
            ),
        ),
    )

    override fun registerTrackMetadata(track: FullTrack) {
        val id = track.id ?: return
        CoreCall.callBlocking(
            "registerTrackMetadata",
            buildJsonObject {
                put("id", id)
                put("name", track.name)
                put("artists", JsonArray(track.artists.map { kotlinx.serialization.json.JsonPrimitive(it.name) }))
                put("durationMs", track.durationMs)
                put("isrc", track.isrc)
            },
        )
    }

    /** `resolveYouTubeId` answers the id as a JSON string. */
    override suspend fun resolveYouTubeId(spotifyId: String): String {
        val answer = CoreCall.call("resolveYouTubeId") { put("spotifyId", spotifyId) }
        return runCatching { Json.parseToJsonElement(answer).jsonPrimitive.content }.getOrDefault("")
    }

    override suspend fun recordPlay(eventJson: String): String = CoreCall.call(
        "recordPlay",
        JsonObject(mapOf("event" to Json.parseToJsonElement(eventJson))),
    )

    override suspend fun resolveAudio(videoId: String, prefsJson: String): String = CoreCall.call(
        "resolveInnertubeAudio",
        JsonObject(
            mapOf(
                "videoId" to kotlinx.serialization.json.JsonPrimitive(videoId),
                "prefs" to Json.parseToJsonElement(prefsJson),
            ),
        ),
    )
}
