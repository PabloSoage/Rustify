package com.varuna.rustify.sync

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Handing the app's Spotify session to the person's own rustify-server, against a fake one shaped like it. */
class ServerLinkTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stop() { server?.stop(0) }

    @Test
    fun theCookieTravelsInClearOnlyOnThePersonsOwnNetwork() {
        for (ok in listOf("https://music.example.org", "http://192.168.1.20:8484", "http://10.0.0.5", "http://172.20.1.1",
            "http://127.0.0.1:8484", "http://localhost:8484", "http://nas.local", "http://100.101.102.103", "http://[fd00::1]:80")) {
            assertTrue(ServerLink.mayCarryCookie(ServerLink.normalise(ok)!!), ok)
        }
        for (no in listOf("http://music.example.org", "http://8.8.8.8", "http://172.32.0.1", "http://[2001:db8::1]")) {
            assertFalse(ServerLink.mayCarryCookie(ServerLink.normalise(no)!!), no)
        }
    }

    @Test
    fun anAddressIsAServerAndNothingMore() {
        assertEquals("http://192.168.1.20:8484", ServerLink.normalise(" 192.168.1.20:8484/ "))
        assertEquals("https://x.org", ServerLink.normalise("https://x.org"))
        for (bad in listOf("", "ftp://x", "http://x/path", "http://user@x", "http://x?a=b")) assertNull(ServerLink.normalise(bad), bad)
    }

    /** /login takes the token for a session cookie, as server/src/auth.rs; /api/call/loginSpotify needs it. */
    private fun fakeServer(spDcSeen: MutableList<String>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/login") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            if (body == "token=the-token") {
                ex.responseHeaders.add("Set-Cookie", "rustify_session=s3ss10n; HttpOnly; SameSite=Strict; Path=/")
                ex.responseHeaders.add("Location", "/")
                ex.sendResponseHeaders(303, -1)
            } else {
                ex.responseHeaders.add("Location", "/login?wrong")
                ex.sendResponseHeaders(303, -1)
            }
            ex.close()
        }
        s.createContext("/api/call/loginSpotify") { ex ->
            val cookie = ex.requestHeaders.getFirst("Cookie").orEmpty()
            val body = ex.requestBody.readBytes().decodeToString()
            val answer = if (cookie != "rustify_session=s3ss10n") {
                ex.sendResponseHeaders(401, -1); ex.close(); return@createContext
            } else {
                spDcSeen += body
                if ("good-cookie" in body) """{"success":true}""" else """{"success":false,"error":"invalid sp_dc"}"""
            }
            val bytes = answer.encodeToByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test
    fun theSessionReachesTheServersCoreWithTheTokensSession(): Unit = runBlocking {
        val seen = mutableListOf<String>()
        val base = fakeServer(seen)
        assertEquals(ServerLink.Outcome.Linked, ServerLink.link(base, " the-token ", "good-cookie"))
        assertEquals(listOf("""{"spDc":"good-cookie"}"""), seen)
        assertEquals(ServerLink.Outcome.WrongToken, ServerLink.link(base, "other", "good-cookie"))
        assertEquals(ServerLink.Outcome.Refused("invalid sp_dc"), ServerLink.link(base, "the-token", "stale"))
        assertEquals(ServerLink.Outcome.Insecure, ServerLink.link("http://8.8.8.8", "the-token", "good-cookie"))
        assertEquals(2, seen.size, "nothing sent to an address it may not go to, or with a wrong token")
    }
}
