package com.varuna.rustify.player

import com.varuna.rustify.audio.DesktopAudioSources
import com.varuna.rustify.ui.DesktopAppPlatform
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.engine.EngineCommand
import com.varuna.rustify.engine.EngineEvent
import com.varuna.rustify.engine.PlayerBackend
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.engine.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class EnginePlayerControllerManualQueueTest {

    private class FakeBackend : PlayerBackend {
        val incoming = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        override suspend fun startEngine() = """{"success":true}"""
        override fun send(command: EngineCommand) = Unit
        override fun events(): Flow<EngineEvent> = incoming

        override fun reduce(stateJson: String, actionJson: String): String {
            val action = Json.parseToJsonElement(actionJson).jsonObject
            val type = action["type"]?.jsonPrimitive?.content
            if (type == "track_ended" || type == "next") {
                val state = Json.parseToJsonElement(stateJson).jsonObject
                val next = (state["index"]?.jsonPrimitive?.int ?: 0) + 1
                val queueSize = state["queue"]?.jsonArray?.size ?: 0
                if (next >= queueSize) return """{"state":$stateJson,"effects":[{"type":"queue_exhausted"}]}"""
                val moved = JsonObject(state + ("index" to JsonPrimitive(next)))
                return """{"state":$moved,"effects":[{"type":"play"}]}"""
            }
            if (type == "seek") {
                val ms = action["ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                return """{"state":$stateJson,"effects":[{"type":"seek","positionMs":$ms}]}"""
            }
            val queue = action["tracks"]?.jsonArray ?: return """{"state":$stateJson,"effects":[]}"""
            val start = action["start"]?.jsonPrimitive?.int ?: 0
            return """{"state":{"queue":$queue,"index":$start,"position_ms":0,"repeat":"off","shuffle":false},"effects":[{"type":"play"}]}"""
        }

        override suspend fun resolveAudio(videoId: String, prefsJson: String) =
            """{"url":"https://audio.test/$videoId","success":true}"""
        override fun registerTrackMetadata(track: FullTrack) = Unit
        override suspend fun resolveYouTubeId(spotifyId: String) = "12345678901"
        override suspend fun recordPlay(eventJson: String) = """{"success":true}"""
        override suspend fun trackPlace(trackId: String) = -1L
        override suspend fun recordTrackPlace(trackId: String, positionMs: Long, durationMs: Long) = """{"success":true}"""
        override suspend fun forgetTrackPlace(trackId: String) = """{"success":true}"""
    }

    private fun track(id: String) = FullTrack(id = id, name = "Track $id")

    private fun createController(scope: CoroutineScope): EnginePlayerController {
        val platform = DesktopAppPlatform("ES")
        val backend = FakeBackend()
        val queuePlayer = QueuePlayer(scope, backend)
        val controller = EnginePlayerController(
            player = queuePlayer,
            scope = scope,
            platform = platform,
            sources = DesktopAudioSources.install(platform.prefs())
        )
        queuePlayer.sources = object : StreamResolver.Sources {
            override suspend fun resolve(track: FullTrack, hint: String?): StreamResolver.Stream =
                StreamResolver.Stream(url = "https://audio.test/${track.id}", success = true)
        }
        return controller
    }

    @Test
    fun additionsPlayInOrderAndNextTrackMaintainsFifoPlacement() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = createController(scope)

        val x = track("x")
        val l1 = track("l1")
        val l2 = track("l2")
        controller.loadPlaylist(listOf(x, l1, l2), initialIndex = 0)

        assertEquals(listOf("x", "l1", "l2"), controller.state.value.queue.map { it.id })
        assertEquals("x", controller.state.value.currentTrack?.id)

        // 1. Enqueue a, b, c while x is playing -> [x, a, b, c, l1, l2]
        controller.enqueue(track("a"))
        controller.enqueue(track("b"))
        controller.enqueue(track("c"))
        assertEquals(listOf("x", "a", "b", "c", "l1", "l2"), controller.state.value.queue.map { it.id })

        // 2. Next track: now 'a' is playing. Enqueue d -> [x, a, b, c, d, l1, l2]
        controller.skipToNext()
        assertEquals("a", controller.state.value.currentTrack?.id)
        controller.enqueue(track("d"))
        assertEquals(listOf("x", "a", "b", "c", "d", "l1", "l2"), controller.state.value.queue.map { it.id })

        // 3. enqueueAll([e, f]) preserves order -> [x, a, b, c, d, e, f, l1, l2]
        controller.enqueueAll(listOf(track("e"), track("f")))
        assertEquals(listOf("x", "a", "b", "c", "d", "e", "f", "l1", "l2"), controller.state.value.queue.map { it.id })
    }

    @Test
    fun aSongQueuedTwiceRemainsFifoAcrossPlayback() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = createController(scope)

        val x = track("x")
        val l1 = track("l1")
        val l2 = track("l2")
        controller.loadPlaylist(listOf(x, l1, l2), initialIndex = 0)

        // Enqueue 'dup', then 'b', then 'dup' again
        controller.enqueue(track("dup"))
        controller.enqueue(track("b"))
        controller.enqueue(track("dup"))
        assertEquals(listOf("x", "dup", "b", "dup", "l1", "l2"), controller.state.value.queue.map { it.id })

        // Next: now first 'dup' is playing
        controller.skipToNext()
        assertEquals("dup", controller.state.value.currentTrack?.id)

        // Enqueueing 'c' while first 'dup' is playing must land behind the 2nd 'dup' and before 'l1'
        controller.enqueue(track("c"))
        assertEquals(listOf("x", "dup", "b", "dup", "c", "l1", "l2"), controller.state.value.queue.map { it.id })

        // Advance to 'b'
        controller.skipToNext()
        assertEquals("b", controller.state.value.currentTrack?.id)

        // Enqueueing 'd' while 'b' is playing must still land behind 2nd 'dup' and 'c', before 'l1'
        controller.enqueue(track("d"))
        assertEquals(listOf("x", "dup", "b", "dup", "c", "d", "l1", "l2"), controller.state.value.queue.map { it.id })
    }

    @Test
    fun replaceAutoQueueAfterCurrentClearsManualQueue() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = createController(scope)

        val x = track("x")
        val l1 = track("l1")
        controller.loadPlaylist(listOf(x, l1), initialIndex = 0)

        controller.enqueue(track("m1"))
        assertEquals(listOf("x", "m1", "l1"), controller.state.value.queue.map { it.id })

        // Auto queue replaces everything after current track
        controller.replaceAutoQueueAfterCurrent(listOf(track("auto1"), track("auto2")))
        assertEquals(listOf("x", "auto1", "auto2"), controller.state.value.queue.map { it.id })

        // Enqueueing after auto queue replacement starts a new manual block behind x
        controller.enqueue(track("m2"))
        assertEquals(listOf("x", "m2", "auto1", "auto2"), controller.state.value.queue.map { it.id })
    }
}
