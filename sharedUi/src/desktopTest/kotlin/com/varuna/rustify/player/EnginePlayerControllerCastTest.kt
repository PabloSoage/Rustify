// sharedUi/src/desktopTest/kotlin/com/varuna/rustify/player/EnginePlayerControllerCastTest.kt
package com.varuna.rustify.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.varuna.rustify.audio.DesktopAudioSources
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LocalStreamServer
import com.varuna.rustify.bridge.TrackRef
import com.varuna.rustify.cast.CastDiscovery
import com.varuna.rustify.cast.CastSession
import com.varuna.rustify.cast.DlnaController
import com.varuna.rustify.engine.EngineCommand
import com.varuna.rustify.engine.EngineEvent
import com.varuna.rustify.engine.PlayerBackend
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.ui.DesktopAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnginePlayerControllerCastTest {

    private lateinit var mockTvServer: HttpServer
    private var mockTvPort = 0
    private val setUriCalls = CopyOnWriteArrayList<String>()
    private val currentTransportState = AtomicReference("PLAYING")
    private val localPlayTriggered = AtomicBoolean(false)
    private val fetchedBytes = CopyOnWriteArrayList<ByteArray>()

    private class TestBackend(private val onPlayReceived: () -> Unit) : PlayerBackend {
        val incomingEvents = MutableSharedFlow<EngineEvent>()
        override suspend fun startEngine(): String = """{"success":true}"""
        override fun send(command: EngineCommand) {
            if (command is EngineCommand.Play) {
                onPlayReceived()
            }
        }
        override fun events(): Flow<EngineEvent> = incomingEvents
        override fun reduce(stateJson: String, actionJson: String): String {
            val action = Json.parseToJsonElement(actionJson).jsonObject
            val type = action["type"]?.jsonPrimitive?.content
            if (type == "play") {
                onPlayReceived()
            }
            if (type == "track_ended" || type == "next") {
                val state = Json.parseToJsonElement(stateJson).jsonObject
                val next = (state["index"]?.jsonPrimitive?.int ?: 0) + 1
                val queueSize = state["queue"]?.jsonArray?.size ?: 0
                if (next >= queueSize) return """{"state":$stateJson,"effects":[{"type":"queue_exhausted"}]}"""
                val moved = JsonObject(state + ("index" to JsonPrimitive(next)))
                return """{"state":$moved,"effects":[{"type":"play"}]}"""
            }
            val queue = action["tracks"]?.jsonArray ?: return """{"state":$stateJson,"effects":[]}"""
            val start = action["start"]?.jsonPrimitive?.int ?: 0
            return """{"state":{"queue":$queue,"index":$start,"position_ms":0,"repeat":"off","shuffle":false},"effects":[{"type":"play"}]}"""
        }
        override suspend fun resolveAudio(videoId: String, prefsJson: String) = """{"url":"https://audio.test/$videoId","success":true}"""
        override fun registerTrackMetadata(track: FullTrack) = Unit
        override suspend fun resolveYouTubeId(spotifyId: String) = ""
        override suspend fun recordPlay(eventJson: String) = """{"success":true}"""
        override suspend fun trackPlace(trackId: String) = -1L
        override suspend fun recordTrackPlace(trackId: String, positionMs: Long, durationMs: Long) = """{"success":true}"""
        override suspend fun forgetTrackPlace(trackId: String) = """{"success":true}"""
    }

    /** Every controller's work in a test, cancelled after it: a cast poll left running could cast the next song again after the session was stopped, and the next test began already casting. */
    private var testJob = SupervisorJob()

    @BeforeTest
    fun setUp() {
        testJob = SupervisorJob()
        CastSession.abandon()
        CastSession.testUrlFor = { handle ->
            LocalStreamServer.streamUrl(handle)
        }
        setUriCalls.clear()
        fetchedBytes.clear()
        localPlayTriggered.set(false)
        currentTransportState.set("PLAYING")

        mockTvServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        mockTvServer.createContext("/control/AVTransport") { exchange: HttpExchange ->
            val soapAction = exchange.requestHeaders.getFirst("SOAPAction").orEmpty().trim('"')
            val actionName = soapAction.substringAfterLast('#', "")
            val body = exchange.requestBody.readBytes().decodeToString()

            if (actionName == "SetAVTransportURI") {
                val uri = Regex("<CurrentURI>(.*?)</CurrentURI>").find(body)?.groupValues?.get(1)?.let {
                    it.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                }
                if (uri != null) {
                    setUriCalls.add(uri)
                    // Mock TV fetches the media URL
                    runCatching {
                        val conn = URL(uri).openConnection() as HttpURLConnection
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        if (conn.responseCode == 200) {
                            fetchedBytes.add(conn.inputStream.readBytes())
                        }
                    }
                }
                val resp = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:SetAVTransportURIResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body></s:Envelope>"""
                val b = resp.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "text/xml; charset=\"utf-8\"")
                exchange.sendResponseHeaders(200, b.size.toLong())
                exchange.responseBody.use { it.write(b) }
                return@createContext
            }

            if (actionName == "GetTransportInfo") {
                val state = currentTransportState.get()
                val resp = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed></u:GetTransportInfoResponse></s:Body></s:Envelope>"""
                val b = resp.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "text/xml; charset=\"utf-8\"")
                exchange.sendResponseHeaders(200, b.size.toLong())
                exchange.responseBody.use { it.write(b) }
                return@createContext
            }

            val resp = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:DefaultResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body></s:Envelope>"""
            val b = resp.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/xml; charset=\"utf-8\"")
            exchange.sendResponseHeaders(200, b.size.toLong())
            exchange.responseBody.use { it.write(b) }
        }
        mockTvServer.start()
        mockTvPort = mockTvServer.address.port
    }

    @AfterTest
    fun tearDown() = runBlocking {
        testJob.cancel()
        CastSession.testUrlFor = null
        runCatching { CastSession.stop() }
        mockTvServer.stop(0)
    }

    @Test
    fun startCastingRefusesTrackNotOnDiskWithoutStartingSession() = runBlocking {
        val platform = DesktopAppPlatform("ES")
        platform.prefs("rustify_settings").putBoolean("settings_casting_enabled", true)
        val backend = TestBackend { localPlayTriggered.set(true) }
        val scope = CoroutineScope(Dispatchers.Unconfined + testJob)
        val queuePlayer = QueuePlayer(scope, backend)
        val controller = EnginePlayerController(
            player = queuePlayer,
            scope = scope,
            platform = platform,
            sources = DesktopAudioSources.install(platform.prefs())
        )

        // Queue a remote Spotify track that is NOT on disk
        val remoteTrack = FullTrack(
            id = "4uLU6hMCjMI75M1A2tKUQC",
            name = "Never Gonna Give You Up",
            durationMs = 213_000
        )
        controller.loadAndPlay(remoteTrack)

        val device = CastDiscovery.Device(
            address = "127.0.0.1",
            friendlyName = "Living Room TV",
            controlUrl = "http://127.0.0.1:$mockTvPort/control/AVTransport"
        )

        val error = controller.startCastingTo(device)
        assertNotNull(error, "Casting a track not on disk must fail with a descriptive error")
        assertTrue(error.contains("Never Gonna Give You Up"), "Error message must name the track: $error")
        assertTrue(error.contains("dispositivo") || error.contains("device") || error.contains("phone"), "Error must state track is not on this device: $error")
        assertFalse(controller.isCasting, "Controller must not be in casting mode when track is missing from disk")
        assertEquals(0, setUriCalls.size, "No SetAVTransportURI should be sent to renderer if track is not on disk")
    }

    private fun isCoreAvailable(): Boolean = try {
        com.varuna.rustify.bridge.NativeLibrary.load()
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    @Test
    fun mockRendererFetchesFileBytesAndPlaysNextTrackSilentlyOnLocalEngine() = runBlocking {
        org.junit.Assume.assumeTrue("no desktop core in this build (-Prustify.desktop=true)", isCoreAvailable())
        val platform = DesktopAppPlatform("ES")
        platform.prefs("rustify_settings").putBoolean("settings_casting_enabled", true)
        val backend = TestBackend { localPlayTriggered.set(true) }
        val scope = CoroutineScope(Dispatchers.Unconfined + testJob)
        val queuePlayer = QueuePlayer(scope, backend)
        val controller = EnginePlayerController(
            player = queuePlayer,
            scope = scope,
            platform = platform,
            sources = DesktopAudioSources.install(platform.prefs())
        )

        val temp1 = File.createTempFile("song1_", ".mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            deleteOnExit()
        }
        val temp2 = File.createTempFile("song2_", ".mp3").apply {
            writeBytes(byteArrayOf(9, 8, 7, 6))
            deleteOnExit()
        }

        val track1 = FullTrack(
            id = TrackRef.Local(temp1.toURI().toString()).raw,
            name = "Song One",
            durationMs = 180_000
        )
        val track2 = FullTrack(
            id = TrackRef.Local(temp2.toURI().toString()).raw,
            name = "Song Two",
            durationMs = 200_000
        )

        controller.loadPlaylist(listOf(track1, track2), 0)
        localPlayTriggered.set(false)

        val device = CastDiscovery.Device(
            address = "127.0.0.1",
            friendlyName = "Living Room TV",
            controlUrl = "http://127.0.0.1:$mockTvPort/control/AVTransport"
        )

        // 1. Cast track1
        val error = controller.startCastingTo(device)
        assertEquals(null, error, "Starting cast with local file on disk must succeed")
        assertTrue(controller.isCasting, "Controller must report isCasting=true")
        assertEquals(1, setUriCalls.size, "One SetAVTransportURI must have been sent for track1")
        assertEquals(1, fetchedBytes.size, "Mock renderer must fetch bytes of track1")
        assertTrue(fetchedBytes[0].contentEquals(byteArrayOf(1, 2, 3, 4, 5)), "Fetched bytes must match file contents")

        // 2. TV transitions to STOPPED (end of track 1)
        localPlayTriggered.set(false)
        currentTransportState.set("STOPPED")

        // Next poll should detect track ended on TV and advance to track 2 via castTrack
        controller.skipToNext()

        val deadline = System.currentTimeMillis() + 4000L
        while (setUriCalls.size < 2 && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }

        assertEquals(2, setUriCalls.size, "Track 2 must be sent to TV in a second SetAVTransportURI")
        assertEquals(2, fetchedBytes.size, "Mock renderer must fetch bytes of track2")
        assertTrue(fetchedBytes[1].contentEquals(byteArrayOf(9, 8, 7, 6)), "Fetched bytes of track 2 must match")
        assertFalse(localPlayTriggered.get(), "Local audio engine must NOT receive play while casting next track")
    }
}
