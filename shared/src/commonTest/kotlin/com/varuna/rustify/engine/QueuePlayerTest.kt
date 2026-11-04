package com.varuna.rustify.engine

import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class QueuePlayerTest {

    /** Answers `set_queue` the way `player::reduce` does; enough for where a queue starts. */
    private class FakeBackend : PlayerBackend {
        val sent = mutableListOf<EngineCommand>()
        override suspend fun startEngine() = """{"success":true}"""
        override fun send(command: EngineCommand) { sent += command }
        override fun events(): Flow<EngineEvent> = emptyFlow()
        override fun reduce(stateJson: String, actionJson: String): String {
            val action = Json.parseToJsonElement(actionJson).jsonObject
            val queue = action["tracks"]?.jsonArray ?: return """{"state":$stateJson,"effects":[]}"""
            val start = action["start"]?.jsonPrimitive?.int ?: 0
            return """{"state":{"queue":$queue,"index":$start,"position_ms":0,"repeat":"off","shuffle":false},""" +
                """"effects":[{"type":"play"}]}"""
        }
        override suspend fun resolveAudio(videoId: String, prefsJson: String) =
            """{"url":"https://audio.test/$videoId","success":true}"""
        override fun registerTrackMetadata(track: FullTrack) = Unit
        override suspend fun resolveYouTubeId(spotifyId: String) = ""
        override suspend fun recordPlay(eventJson: String) = """{"success":true}"""
    }

    private fun ytm(video: String, name: String) = FullTrack(id = "ytm:$video", name = name)

    @Test
    fun `starting at the second copy of a song starts there and not at the first`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val a = ytm("aaaaaaaaaaa", "A")
        val b = ytm("bbbbbbbbbbb", "B")
        val c = ytm("ccccccccccc", "C")
        // A track with no id is dropped, which shifts every index after it down by one.
        val list = listOf(FullTrack(name = "local file"), a, b, a, c)

        player.playAll(list, start = 3)

        assertEquals(2, player.ui.value.index, "the second A is the third playable track")
        assertEquals(4, player.ui.value.queue.size)
    }

    @Test
    fun `editing the queue does not load the playing song again`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val a = ytm("aaaaaaaaaaa", "A")
        val b = ytm("bbbbbbbbbbb", "B")
        val c = ytm("ccccccccccc", "C")
        player.playAll(listOf(a, b), start = 1)
        val loads = backend.sent.count { it is EngineCommand.Load }

        // B, the song playing, moves to the top and C is added after it.
        player.editQueue(listOf(b, a, c), currentIndex = 0)

        assertEquals(loads, backend.sent.count { it is EngineCommand.Load }, "no second Load for the same song")
        assertEquals(0, player.ui.value.index)
        assertEquals(listOf("B", "A", "C"), player.ui.value.queue.map { it.name })
    }

    @Test
    fun `starting on a track that cannot play starts at the top`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val list = listOf(ytm("aaaaaaaaaaa", "A"), FullTrack(name = "local file"), ytm("bbbbbbbbbbb", "B"))

        player.playAll(list, start = 1)

        assertEquals(0, player.ui.value.index)
    }
}
