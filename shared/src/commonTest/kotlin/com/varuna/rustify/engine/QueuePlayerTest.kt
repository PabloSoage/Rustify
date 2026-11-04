package com.varuna.rustify.engine

import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertTrue

class QueuePlayerTest {

    /**
     * Answers `set_queue` the way `player::reduce` does, and `track_ended` by moving to the next song;
     * enough for where a queue starts and what follows a song.
     */
    private class FakeBackend : PlayerBackend {
        val sent = mutableListOf<EngineCommand>()
        val incoming = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        override suspend fun startEngine() = """{"success":true}"""
        override fun send(command: EngineCommand) { sent += command }
        override fun events(): Flow<EngineEvent> = incoming
        override fun reduce(stateJson: String, actionJson: String): String {
            val action = Json.parseToJsonElement(actionJson).jsonObject
            if (action["type"]?.jsonPrimitive?.content == "track_ended") {
                val state = Json.parseToJsonElement(stateJson).jsonObject
                val next = (state["index"]?.jsonPrimitive?.int ?: 0) + 1
                if (next >= (state["queue"]?.jsonArray?.size ?: 0)) return """{"state":$stateJson,"effects":[]}"""
                val moved = JsonObject(state + ("index" to JsonPrimitive(next)))
                return """{"state":$moved,"effects":[{"type":"play"}]}"""
            }
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

    /** A player whose first song of A, B is loaded and playing, with the engine's events wired. */
    private fun playingFirstOfTwo(backend: FakeBackend): Pair<QueuePlayer, Long> {
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        player.start()
        player.playAll(listOf(ytm("aaaaaaaaaaa", "A"), ytm("bbbbbbbbbbb", "B")), start = 0)
        val token = backend.sent.filterIsInstance<EngineCommand.Load>().last().token
        backend.incoming.tryEmit(EngineEvent.Ready(token))
        return player to token
    }

    @Test
    fun `the end of a last song stops there and preloads nothing behind the stop`() {
        val backend = FakeBackend()
        val (player, token) = playingFirstOfTwo(backend)
        player.stopAtTrackEnd = true
        // As the desktop's sleep timer does: the count reaches zero and turns the flag off.
        player.onTrackEnded = { player.stopAtTrackEnd = false }
        backend.sent.clear()

        backend.incoming.tryEmit(EngineEvent.Ended(token))

        assertTrue(EngineCommand.Stop in backend.sent, "the song was the last")
        assertTrue(backend.sent.none { it is EngineCommand.Preload }, "nothing queued after the stop")
        assertEquals(false, player.stopAtTrackEnd)
    }

    @Test
    fun `turning the last song off mid-song preloads what comes next again`() {
        val backend = FakeBackend()
        val (player, _) = playingFirstOfTwo(backend)
        player.stopAtTrackEnd = true
        backend.sent.clear()

        player.stopAtTrackEnd = false

        assertEquals(1, backend.sent.filterIsInstance<EngineCommand.Preload>().size)
    }
}
