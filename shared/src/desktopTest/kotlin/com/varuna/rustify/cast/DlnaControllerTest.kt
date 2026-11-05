// shared/src/desktopTest/kotlin/com/varuna/rustify/cast/DlnaControllerTest.kt
package com.varuna.rustify.cast

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Verifying DLNA AVTransport control over HTTP and SOAP against a mock renderer on loopback.
 *
 * Checks SetAVTransportURI and Play sequencing, metadata DIDL envelope, transport commands
 * (pause, resume, seek, stop), and device polling (position, transport state).
 */
class DlnaControllerTest {

    private lateinit var server: HttpServer
    private var controlUrl = ""
    private val recordedActions = CopyOnWriteArrayList<String>()
    private val recordedBodies = CopyOnWriteArrayList<String>()
    private var returnErrorOnSetUri = false

    @BeforeTest
    fun setUp() {
        recordedActions.clear()
        recordedBodies.clear()
        returnErrorOnSetUri = false

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/control/AVTransport") { exchange: HttpExchange ->
            val soapAction = exchange.requestHeaders.getFirst("SOAPAction").orEmpty().trim('"')
            val actionName = soapAction.substringAfterLast('#', "")
            val body = exchange.requestBody.readBytes().decodeToString()
            recordedActions.add(actionName)
            recordedBodies.add(body)

            if (returnErrorOnSetUri && actionName == "SetAVTransportURI") {
                val err = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring></s:Fault></s:Body></s:Envelope>"""
                val bytes = err.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "text/xml; charset=\"utf-8\"")
                exchange.sendResponseHeaders(500, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }

            val responseXml = when (actionName) {
                "SetAVTransportURI" -> """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body><u:SetAVTransportURIResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""

                "Play" -> """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body><u:PlayResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""

                "Pause" -> """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body><u:PauseResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""

                "Stop" -> """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body><u:StopResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""

                "Seek" -> """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body><u:SeekResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""

                "GetPositionInfo" -> """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <Track>1</Track>
      <TrackDuration>0:03:45</TrackDuration>
      <RelTime>0:01:23</RelTime>
    </u:GetPositionInfoResponse>
  </s:Body>
</s:Envelope>"""

                "GetTransportInfo" -> """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <CurrentTransportState>PLAYING</CurrentTransportState>
      <CurrentTransportStatus>OK</CurrentTransportStatus>
      <CurrentSpeed>1</CurrentSpeed>
    </u:GetTransportInfoResponse>
  </s:Body>
</s:Envelope>"""

                else -> """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body><u:DefaultResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""
            }

            val bytes = responseXml.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/xml; charset=\"utf-8\"")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        controlUrl = "http://127.0.0.1:${server.address.port}/control/AVTransport"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun playSendsSetAVTransportUriFollowedByPlayAction() = runBlocking {
        val mediaUrl = "http://192.168.1.10:8080/stream/song123?t=secret"
        val success = DlnaController.play(
            controlUrl = controlUrl,
            mediaUrl = mediaUrl,
            title = "Bohemian Rhapsody",
            artist = "Queen",
            durationMs = 354_000L
        )

        assertTrue(success, "DlnaController.play should return true on 200 OK responses")
        assertEquals(listOf("SetAVTransportURI", "Play"), recordedActions.toList())

        // Validate SetAVTransportURI body contents
        val setUriBody = recordedBodies[0]
        assertTrue(setUriBody.contains("<CurrentURI>${DlnaController.escape(mediaUrl)}</CurrentURI>"), "SetAVTransportURI must contain escaped mediaUrl")
        assertTrue(setUriBody.contains("Bohemian Rhapsody"), "Metadata must contain title")
        assertTrue(setUriBody.contains("Queen"), "Metadata must contain artist")
        assertTrue(setUriBody.contains("0:05:54"), "Duration should be formatted in H:MM:SS")

        // Validate Play body contents
        val playBody = recordedBodies[1]
        assertTrue(playBody.contains("<Speed>1</Speed>"), "Play body must specify speed 1")
    }

    @Test
    fun playReturnsFalseWhenSetAVTransportUriFails() = runBlocking {
        returnErrorOnSetUri = true
        val success = DlnaController.play(
            controlUrl = controlUrl,
            mediaUrl = "http://192.168.1.10:8080/stream/x",
            title = "Test",
            artist = "Artist",
            durationMs = 120_000L
        )

        assertFalse(success, "DlnaController.play must return false when SetAVTransportURI fails")
        assertEquals(listOf("SetAVTransportURI"), recordedActions.toList(), "Play should not be sent if SetAVTransportURI failed")
    }

    @Test
    fun transportControlsSendProperSoapActions() = runBlocking {
        assertTrue(DlnaController.pause(controlUrl))
        assertEquals("Pause", recordedActions.last())

        assertTrue(DlnaController.resume(controlUrl))
        assertEquals("Play", recordedActions.last())

        assertTrue(DlnaController.seek(controlUrl, 83_000L))
        assertEquals("Seek", recordedActions.last())
        assertTrue(recordedBodies.last().contains("<Target>0:01:23</Target>"), "Seek must target REL_TIME clock")

        assertTrue(DlnaController.stop(controlUrl))
        assertEquals("Stop", recordedActions.last())
    }

    @Test
    fun queriesPositionAndTransportState() = runBlocking {
        val position = DlnaController.position(controlUrl)
        assertEquals(83_000L, position, "Position should be parsed from RelTime (0:01:23 = 83s)")

        val state = DlnaController.transportState(controlUrl)
        assertEquals("PLAYING", state, "Transport state should match CurrentTransportState")
    }

    @Test
    fun timeFormattingAndParsing() {
        assertEquals("0:00:00", DlnaController.clock(0L))
        assertEquals("0:01:23", DlnaController.clock(83_000L))
        assertEquals("1:01:05", DlnaController.clock(3665_000L))

        assertEquals(0L, DlnaController.millis("0:00:00"))
        assertEquals(83_000L, DlnaController.millis("0:01:23"))
        assertEquals(3665_000L, DlnaController.millis("1:01:05"))
        assertEquals(83_000L, DlnaController.millis("0:01:23.456"))
        assertNull(DlnaController.millis("invalid"))
    }

    @Test
    fun xmlEscaping() {
        assertEquals("&amp;&lt;&gt;&quot;test", DlnaController.escape("&<>\"test"))
    }
}
