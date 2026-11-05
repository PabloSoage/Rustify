package com.varuna.rustify.engine

import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.CompletableDeferred
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
        val actions = mutableListOf<JsonObject>()
        override fun reduce(stateJson: String, actionJson: String): String {
            val action = Json.parseToJsonElement(actionJson).jsonObject
            actions += action
            val type = action["type"]?.jsonPrimitive?.content
            if (type == "track_ended" || type == "next") {
                val state = Json.parseToJsonElement(stateJson).jsonObject
                val next = (state["index"]?.jsonPrimitive?.int ?: 0) + 1
                if (next >= (state["queue"]?.jsonArray?.size ?: 0)) return """{"state":$stateJson,"effects":[{"type":"queue_exhausted"}]}"""
                val moved = JsonObject(state + ("index" to JsonPrimitive(next)))
                return """{"state":$moved,"effects":[{"type":"play"}]}"""
            }
            if (type == "seek") {
                val ms = action["ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                return """{"state":$stateJson,"effects":[{"type":"seek","positionMs":$ms}]}"""
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

        val places = mutableMapOf<String, Long>()
        var placeGate: CompletableDeferred<Unit>? = null
        override suspend fun trackPlace(trackId: String): Long {
            placeGate?.await()
            return places[trackId] ?: -1L
        }
        override suspend fun recordTrackPlace(trackId: String, positionMs: Long, durationMs: Long): String {
            places[trackId] = positionMs
            return """{"success":true}"""
        }
        override suspend fun forgetTrackPlace(trackId: String): String {
            places.remove(trackId)
            return """{"success":true}"""
        }
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

    @Test
    fun `a local file plays from its own path`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val uri = "file:///home/me/Music/a%20b.flac"

        player.playAll(listOf(FullTrack(id = "local:$uri", name = "A")), start = 0)

        assertEquals(uri, backend.sent.filterIsInstance<EngineCommand.Load>().single().uri)
    }

    @Test
    fun `a phone content uri has nothing to open here`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)

        player.playAll(listOf(FullTrack(id = "local:content://media/document/1", name = "A")), start = 0)

        assertTrue(backend.sent.none { it is EngineCommand.Load })
    }

    @Test
    fun `a spotify track with a local copy plays the copy`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        player.localCopy = { "file:///home/me/Music/copy.mp3" }

        player.playAll(listOf(FullTrack(id = "4uLU6hMCjMI75M1A2tKUQC", name = "A")), start = 0)

        assertEquals("file:///home/me/Music/copy.mp3", backend.sent.filterIsInstance<EngineCommand.Load>().single().uri)
    }

    @Test
    fun `moving on steps over what is blocked and a song chosen by hand still plays`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val a = ytm("aaaaaaaaaaa", "A")
        val b = ytm("bbbbbbbbbbb", "B")
        val c = ytm("ccccccccccc", "C")
        player.blocked = { it.name == "B" }
        player.playAll(listOf(a, b, c), start = 0)

        player.next()
        assertEquals(2, player.ui.value.index, "B is stepped over")

        player.jumpTo(1)
        assertEquals(1, player.ui.value.index, "chosen by hand")
    }

    @Test
    fun `with everything ahead blocked the queue stays where it was`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        player.blocked = { it.name != "A" }
        player.playAll(listOf(ytm("aaaaaaaaaaa", "A"), ytm("bbbbbbbbbbb", "B")), start = 0)
        val loads = backend.sent.count { it is EngineCommand.Load }

        player.next()

        assertEquals(0, player.ui.value.index)
        assertEquals(loads, backend.sent.count { it is EngineCommand.Load })
    }

    @Test
    fun `previous says always when that is the setting`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        player.playAll(listOf(ytm("aaaaaaaaaaa", "A")), start = 0)

        player.previous()
        assertEquals(null, backend.actions.last()["always"])

        player.previousAlways = { true }
        player.previous()
        assertEquals(JsonPrimitive(true), backend.actions.last()["always"])
    }

    @Test
    fun `at the end of the queue the radio is added and played without what is there or blocked`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val a = ytm("aaaaaaaaaaa", "A")
        val b = ytm("bbbbbbbbbbb", "B")
        val c = ytm("ccccccccccc", "C")
        var added = emptyList<FullTrack>()
        player.blocked = { it.name == "B" }
        player.radio = { listOf(a, b, c) }
        player.onRadioAdded = { added = it }
        player.playAll(listOf(a), start = 0)

        player.next()

        assertEquals(listOf("C"), added.map { it.name })
        assertEquals(listOf("A", "C"), player.ui.value.queue.map { it.name })
        assertEquals(1, player.ui.value.index, "it moved on into what was added")
    }

    @Test
    fun `a radio with nothing new leaves the queue alone and can be asked again`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val a = ytm("aaaaaaaaaaa", "A")
        var asked = 0
        player.radio = { asked++; listOf(a) }
        player.playAll(listOf(a), start = 0)

        player.next()
        player.next()

        assertEquals(2, asked)
        assertEquals(listOf("A"), player.ui.value.queue.map { it.name })
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
    fun `effects set before the engine starts reach it once it runs`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val slower = EffectsConfig(speed = 0.75f)

        player.setEffects(slower)
        assertTrue(backend.sent.none { it is EngineCommand.SetEffects }, "no engine to send them to yet")
        player.start()
        assertEquals(listOf(EngineCommand.SetEffects(slower)), backend.sent.filterIsInstance<EngineCommand.SetEffects>())

        player.setEffects(slower.copy(skipSilence = true))
        assertEquals(EngineCommand.SetEffects(slower.copy(skipSilence = true)), backend.sent.last(), "and straight away after")
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

    /** A chain of sources that answers the video it was asked for, and remembers each hint. */
    private class FakeSources : StreamResolver.Sources {
        val hints = mutableListOf<String?>()
        override suspend fun resolve(track: FullTrack, hint: String?): StreamResolver.Stream {
            hints += hint
            return StreamResolver.Stream(url = "https://audio.test/${hint ?: "match"}")
        }
    }

    private fun lastLoad(backend: FakeBackend) = backend.sent.filterIsInstance<EngineCommand.Load>().last()

    @Test
    fun `a chosen recording reaches the sources once and skips the local copy`() {
        val backend = FakeBackend()
        val sources = FakeSources()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        player.sources = sources
        player.localCopy = { "file:///home/me/Music/copy.mp3" }
        player.playAll(listOf(FullTrack(id = "4uLU6hMCjMI75M1A2tKUQC", name = "A")), start = 0)
        assertEquals("file:///home/me/Music/copy.mp3", lastLoad(backend).uri, "no hint: the local copy")

        player.jumpTo(0, youtubeId = "ccccccccccc")
        assertEquals("https://audio.test/ccccccccccc", lastLoad(backend).uri, "the video asked for")
        assertEquals(listOf<String?>("ccccccccccc"), sources.hints)

        player.jumpTo(0)
        assertEquals("file:///home/me/Music/copy.mp3", lastLoad(backend).uri, "the hint is used once")
    }

    @Test
    fun `a ytm track asks the sources with its own video`() {
        val backend = FakeBackend()
        val sources = FakeSources()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        player.sources = sources

        player.playAll(listOf(ytm("aaaaaaaaaaa", "A")), start = 0)

        assertEquals(listOf<String?>("aaaaaaaaaaa"), sources.hints)
    }

    @Test
    fun `an audition plays the video and leaves the queue where it was`() {
        val backend = FakeBackend()
        val (player, token) = playingFirstOfTwo(backend)
        backend.incoming.tryEmit(EngineEvent.Position(token, 30_000))
        backend.sent.clear()

        // A Spotify track: a ytm one is its own video, which wins over a hint, as on the phone.
        player.audition(FullTrack(id = "4uLU6hMCjMI75M1A2tKUQC", name = "A elsewhere"), "ddddddddddd")
        val audition = lastLoad(backend)
        assertEquals("https://audio.test/ddddddddddd", audition.uri)
        assertTrue(backend.sent.none { it is EngineCommand.Preload }, "nothing behind an audition")
        backend.incoming.tryEmit(EngineEvent.Ready(audition.token))
        backend.incoming.tryEmit(EngineEvent.Position(audition.token, 5_000))
        assertEquals(0, player.ui.value.index, "still the queue's first song")

        // It ends: playback stops there, and play resumes the queue's song where it was.
        backend.incoming.tryEmit(EngineEvent.Ended(audition.token))
        assertEquals(0, player.ui.value.index, "the queue did not move on")
        assertEquals(30_000L, player.ui.value.positionMs)
        backend.sent.clear()
        player.togglePause()
        val resumed = lastLoad(backend)
        assertEquals("https://audio.test/aaaaaaaaaaa", resumed.uri)
        assertEquals(30_000L, resumed.startMs)
    }

    @Test
    fun `a long track with a saved place resumes where it was left`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val longTrack = FullTrack(id = "ytm:long1", name = "Podcast Episode", durationMs = 25 * 60_000)
        backend.places["ytm:long1"] = 600_000L

        player.playAll(listOf(longTrack), 0)

        val load = backend.sent.filterIsInstance<EngineCommand.Load>().lastOrNull()
        assertEquals(600_000L, load?.startMs)
        assertEquals("ytm:long1" to 600_000L, player.resumedAt.value)
    }

    @Test
    fun `a long track does not resume when startMs is explicitly provided`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val longTrack = FullTrack(id = "ytm:long2", name = "Podcast Episode", durationMs = 25 * 60_000)
        backend.places["ytm:long2"] = 600_000L

        player.playAll(listOf(longTrack), 0, startMs = 120_000L)

        val load = backend.sent.filterIsInstance<EngineCommand.Load>().lastOrNull()
        assertEquals(120_000L, load?.startMs)
        assertEquals(null, player.resumedAt.value)
    }

    @Test
    fun `a long track does not resume on auto-retry`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val longTrack = FullTrack(id = "ytm:long3", name = "Podcast Episode", durationMs = 25 * 60_000)
        backend.places["ytm:long3"] = 600_000L
        player.playAll(listOf(longTrack), 0, startMs = 100L)

        player.jumpTo(0, startMs = 0L, isAutoRetry = true)

        val load = backend.sent.filterIsInstance<EngineCommand.Load>().lastOrNull()
        assertEquals(0L, load?.startMs)
        assertEquals(null, player.resumedAt.value)
    }

    @Test
    fun `a short song does not resume from a bookmark`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val shortTrack = FullTrack(id = "ytm:short", name = "Short Song", durationMs = 3 * 60_000)
        backend.places["ytm:short"] = 60_000L

        player.playAll(listOf(shortTrack), 0)

        val load = backend.sent.filterIsInstance<EngineCommand.Load>().lastOrNull()
        assertEquals(0L, load?.startMs)
        assertEquals(null, player.resumedAt.value)
    }

    @Test
    fun `an audition does not resume from a bookmark`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val longTrack = FullTrack(id = "ytm:long4", name = "Long Set", durationMs = 30 * 60_000)
        backend.places["ytm:long4"] = 500_000L

        player.audition(longTrack, "sample_vid")

        val load = backend.sent.filterIsInstance<EngineCommand.Load>().lastOrNull()
        assertEquals(0L, load?.startMs)
        assertEquals(null, player.resumedAt.value)
    }

    @Test
    fun `resumedAt is cleared when moving to another track`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val longTrack = FullTrack(id = "ytm:long5", name = "Long Set", durationMs = 30 * 60_000)
        val otherTrack = FullTrack(id = "ytm:other", name = "Other Song", durationMs = 3 * 60_000)
        backend.places["ytm:long5"] = 500_000L

        player.playAll(listOf(longTrack, otherTrack), 0)
        assertEquals("ytm:long5" to 500_000L, player.resumedAt.value)

        player.next()
        assertEquals(null, player.resumedAt.value)
    }

    @Test
    fun `startOver clears resumedAt seeks to zero and forgets the saved place`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val longTrack = FullTrack(id = "ytm:long6", name = "Long Set", durationMs = 30 * 60_000)
        backend.places["ytm:long6"] = 500_000L

        player.playAll(listOf(longTrack), 0)
        assertEquals("ytm:long6" to 500_000L, player.resumedAt.value)

        player.startOver()

        assertEquals(null, player.resumedAt.value)
        val seek = backend.sent.filterIsInstance<EngineCommand.Seek>().lastOrNull()
        assertEquals(0L, seek?.positionMs)
        assertEquals(-1L, backend.places["ytm:long6"] ?: -1L)
    }

    @Test
    fun `dismissResumed clears resumedAt without forgetting the saved place`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        val longTrack = FullTrack(id = "ytm:long7", name = "Long Set", durationMs = 30 * 60_000)
        backend.places["ytm:long7"] = 500_000L

        player.playAll(listOf(longTrack), 0)
        assertEquals("ytm:long7" to 500_000L, player.resumedAt.value)

        player.dismissResumed()

        assertEquals(null, player.resumedAt.value)
        assertEquals(500_000L, backend.places["ytm:long7"])
    }

    private val longSet = FullTrack(id = "ytm:longset", name = "Long Set", durationMs = 30 * 60_000)
    private val song = FullTrack(id = "ytm:song", name = "Song", durationMs = 3 * 60_000)

    /** A player resuming [longSet] at 500 s, its engine ready and not yet at the first position. */
    private fun resuming(backend: FakeBackend, vararg queue: FullTrack = arrayOf(longSet, song)): Pair<QueuePlayer, Long> {
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        player.start()
        backend.places.getOrPut(longSet.id!!) { 500_000L }
        player.playAll(queue.toList(), 0)
        val token = backend.sent.filterIsInstance<EngineCommand.Load>().last().token
        backend.incoming.tryEmit(EngineEvent.Ready(token))
        backend.incoming.tryEmit(EngineEvent.State(playing = true))
        return player to token
    }

    @Test
    fun `leaving before the first position keeps the place being resumed`() {
        val backend = FakeBackend()
        val (player, _) = resuming(backend)

        player.next()

        assertEquals(500_000L, backend.places[longSet.id])
    }

    @Test
    fun `a failure before the first position keeps the place being resumed`() {
        val backend = FakeBackend()
        val (_, token) = resuming(backend)

        backend.incoming.tryEmit(EngineEvent.Error(token, "the connection dropped"))

        assertEquals(500_000L, backend.places[longSet.id])
    }

    @Test
    fun `pausing before the first position keeps the place being resumed`() {
        val backend = FakeBackend()
        val (player, _) = resuming(backend)

        player.pause()

        assertEquals(500_000L, backend.places[longSet.id])
    }

    @Test
    fun `pausing and leaving store the place reached`() {
        val backend = FakeBackend()
        val (player, token) = resuming(backend)

        backend.incoming.tryEmit(EngineEvent.Position(token, 510_000L))
        player.pause()
        assertEquals(510_000L, backend.places[longSet.id], "paused")

        backend.incoming.tryEmit(EngineEvent.Position(token, 515_000L))
        player.next()
        assertEquals(515_000L, backend.places[longSet.id], "left")
    }

    @Test
    fun `pausing an audition writes nothing over the long track of the queue`() {
        val backend = FakeBackend()
        val (player, _) = resuming(backend)
        player.audition(song, "sample_vid")
        val audition = backend.sent.filterIsInstance<EngineCommand.Load>().last().token
        backend.incoming.tryEmit(EngineEvent.Ready(audition))
        backend.incoming.tryEmit(EngineEvent.State(playing = true))
        backend.incoming.tryEmit(EngineEvent.Position(audition, 120_000L))

        player.pause()

        assertEquals(500_000L, backend.places[longSet.id])
    }

    @Test
    fun `a long track with a place is not preloaded and resumes when the queue reaches it`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        player.start()
        backend.places[longSet.id!!] = 500_000L
        player.playAll(listOf(song, longSet), 0)
        val token = backend.sent.filterIsInstance<EngineCommand.Load>().last().token

        backend.incoming.tryEmit(EngineEvent.Ready(token))
        assertTrue(backend.sent.none { it is EngineCommand.Preload })

        backend.incoming.tryEmit(EngineEvent.Ended(token))
        assertEquals(500_000L, backend.sent.filterIsInstance<EngineCommand.Load>().last().startMs)
        assertEquals(longSet.id to 500_000L, player.resumedAt.value)
    }

    @Test
    fun `a long track with no place is still preloaded`() {
        val backend = FakeBackend()
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)
        player.start()
        player.playAll(listOf(song, longSet), 0)

        backend.incoming.tryEmit(EngineEvent.Ready(backend.sent.filterIsInstance<EngineCommand.Load>().last().token))

        assertEquals(1, backend.sent.count { it is EngineCommand.Preload })
    }

    @Test
    fun `skipping while the place is being read leaves no notice of the track left`() {
        val backend = FakeBackend()
        val gate = CompletableDeferred<Unit>()
        backend.placeGate = gate
        backend.places[longSet.id!!] = 500_000L
        val player = QueuePlayer(CoroutineScope(Dispatchers.Unconfined), backend)

        player.playAll(listOf(longSet, song), 0)
        backend.placeGate = null
        player.next()
        gate.complete(Unit)

        assertEquals(null, player.resumedAt.value)
        assertEquals(0L, player.ui.value.positionMs)
        assertEquals(1, backend.sent.count { it is EngineCommand.Load }, "only the song is loaded")
    }
}
