package com.varuna.rustify.sync

import com.sun.net.httpserver.HttpServer
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.MemoryStore
import com.varuna.rustify.ui.AppPlatform
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drive's sync for every platform: the sign-in in the browser against a fake Google, the desktop's
 * loopback port taking the redirect page's request, Drive's REST against a fake Drive, and the
 * container's merge, whose rules the phone has always had.
 */
class DriveSyncTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    /** A loopback server answering each path with [handle] (method, path, query, body) → (status, body). */
    private fun serve(handle: (String, String, String, String) -> Pair<Int, String>): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val (status, answer) = handle(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery.orEmpty(), body)
            val bytes = answer.encodeToByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun form(body: String): Map<String, String> = body.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test
    fun sha256AndThePkceChallengeAreTheStandardOnes() {
        val hex = Sha256.digest("abc".encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
        val long = Sha256.digest(ByteArray(1_000) { 'a'.code.toByte() })
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(ByteArray(1_000) { 'a'.code.toByte() }).toList(), long.toList())
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", DriveOAuth.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun aRedirectsQueryIsDecoded() {
        assertEquals(
            mapOf("code" to "4/0Ab cd+é", "state" to "rustify-ios-x"),
            DriveOAuth.queryOf("rustify://oauth2redirect?code=4%2F0Ab+cd%2B%C3%A9&state=rustify-ios-x")
        )
        assertEquals(mapOf("error" to "access_denied"), DriveOAuth.queryOf("/?error=access_denied"))
    }

    @Test
    fun theRandomIsUrlSafeAndNotRepeated() {
        val a = DriveOAuth.randomUrlSafe(48)
        assertEquals(64, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' }, a)
        assertTrue(a != DriveOAuth.randomUrlSafe(48))
    }

    @Test
    fun signingInExchangesTheCodeWithItsVerifierAndKeepsTheRefreshToken(): Unit = runBlocking {
        val asked = mutableListOf<Map<String, String>>()
        var expiresIn = 3600
        val google = serve { _, _, _, body ->
            val f = form(body)
            asked += f
            when (f["grant_type"]) {
                "authorization_code" -> 200 to """{"access_token":"at-1","expires_in":$expiresIn,"refresh_token":"rt-1"}"""
                "refresh_token" -> if (f["refresh_token"] == "rt-1") 200 to """{"access_token":"at-2","expires_in":3600}""" else 400 to "{}"
                else -> 400 to "{}"
            }
        }
        var authUrl = ""
        val browser = DriveOAuth.Browser {
            object : DriveOAuth.Session {
                override val statePrefix = "rustify-test-"
                override suspend fun await(url: String): Map<String, String> {
                    authUrl = url
                    val state = DriveOAuth.queryOf(url)["state"]!!
                    return mapOf("code" to "the-code", "state" to state)
                }
            }
        }
        val prefs: KeyValueStore = MemoryStore()
        val oauth = DriveOAuth(prefs, browser, "client-1", "secret-1", "$google/auth", "$google/token")

        expiresIn = 30 // shorter than the minute kept in reserve: the next silent token refreshes.
        assertEquals("at-1", oauth.signIn())
        val sent = DriveOAuth.queryOf(authUrl)
        assertEquals(DriveOAuth.REDIRECT_URI, sent["redirect_uri"])
        assertEquals("S256", sent["code_challenge_method"])
        assertTrue(sent["state"]!!.startsWith("rustify-test-"))
        assertEquals("offline", sent["access_type"])
        assertTrue(sent["scope"]!!.contains(DriveAuth.SCOPE_APPDATA))
        val exchange = asked.single()
        assertEquals("the-code", exchange["code"])
        assertEquals("client-1", exchange["client_id"])
        assertEquals("secret-1", exchange["client_secret"])
        assertEquals(sent["code_challenge"], DriveOAuth.challengeOf(exchange["code_verifier"]!!), "the verifier is the challenge's")

        assertEquals("at-2", oauth.silentToken(), "refreshed, the access token having nearly run out")
        assertEquals("at-2", oauth.silentToken(), "and kept while it lasts")
        assertEquals(2, asked.size)
    }

    @Test
    fun anAnswerForAnotherSignInIsRefused(): Unit = runBlocking {
        val browser = DriveOAuth.Browser {
            object : DriveOAuth.Session {
                override val statePrefix = "rustify-test-"
                override suspend fun await(url: String) = mapOf("code" to "x", "state" to "rustify-test-someone-else")
            }
        }
        val oauth = DriveOAuth(MemoryStore(), browser, "client-1", "", "http://127.0.0.1:9/auth", "http://127.0.0.1:9/token")
        assertFailsWith<DriveException> { oauth.signIn() }
    }

    @Test
    fun aRefreshGoogleNoLongerHonoursSignsOut(): Unit = runBlocking {
        val google = serve { _, _, _, _ -> 400 to """{"error":"invalid_grant"}""" }
        val prefs = MemoryStore().apply { putString("drive_oauth_refresh", "revoked") }
        val oauth = DriveOAuth(prefs, { error("no browser") }, "client-1", "", "$google/auth", "$google/token")
        assertTrue(oauth.isAuthorized)
        assertFailsWith<DriveException> { oauth.silentToken() }
        assertTrue(!oauth.isAuthorized, "the next sync asks to sign in again")
        assertNull(oauth.silentToken())
    }

    @Test
    fun theDesktopsPortTakesTheRedirectPagesRequest(): Unit = runBlocking {
        var opened = ""
        val browser = LoopbackBrowser(
            platform = NoPlatform,
            openUrl = { url ->
                opened = url
                // The redirect page, as a browser would follow it.
                Thread {
                    URI(url).toURL().openStream().close()
                }.start()
            }
        )
        val session = browser.open()
        val port = session.statePrefix.removePrefix(LoopbackBrowser.STATE_PREFIX).removeSuffix("-").toInt()
        val answer = session.await("http://127.0.0.1:$port/?code=c%2F1&state=${session.statePrefix}abc")
        assertEquals(mapOf("code" to "c/1", "state" to "${session.statePrefix}abc"), answer)
        assertTrue(opened.startsWith("http://127.0.0.1:$port/"))
    }

    @Test
    fun driveCreatesTheFileOnceThenReplacesIt(): Unit = runBlocking {
        var stored: String? = null
        val requests = mutableListOf<String>()
        val drive = serve { method, path, query, body ->
            requests += "$method $path ${query.substringBefore('&')}"
            when {
                method == "GET" && path == "/files" ->
                    200 to (if (stored == null) """{"files":[]}""" else """{"files":[{"id":"f1","name":"rustify-backup.json"}]}""")
                method == "GET" && path == "/files/f1" -> 200 to stored!!
                method == "POST" && path == "/upload" -> {
                    // multipart/related: the metadata, then the container.
                    assertTrue(body.contains(""""parents":["appDataFolder"]"""), body)
                    stored = body.split("\r\n\r\n")[2].substringBefore("\r\n--")
                    200 to """{"id":"f1"}"""
                }
                method == "PATCH" && path == "/upload/f1" -> { stored = body; 200 to """{"id":"f1"}""" }
                else -> 404 to "{}"
            }
        }
        val api = DriveApi("$drive/files", "$drive/upload")
        assertNull(api.findBackupFileId("t"))
        val first = buildJsonObject { put("schema", RustifyBackup.SCHEMA); put("updatedAt", 1) }
        assertEquals("f1", api.upload("t", first, null))
        assertEquals("f1", api.findBackupFileId("t"))
        assertEquals(first, api.download("t", "f1"))
        val second = buildJsonObject { put("schema", RustifyBackup.SCHEMA); put("updatedAt", 2) }
        api.upload("t", second, "f1")
        assertEquals(second, api.download("t", "f1"))
        assertTrue(requests.any { it.startsWith("POST /upload uploadType=multipart") })
        assertTrue(requests.any { it.startsWith("PATCH /upload/f1 uploadType=media") })
    }

    @Test
    fun driveSaysWhatWentWrong(): Unit = runBlocking {
        val drive = serve { _, _, _, _ -> 401 to """{"error":{"message":"Invalid Credentials"}}""" }
        val e = assertFailsWith<DriveException> { DriveApi("$drive/files", "$drive/upload").findBackupFileId("old") }
        assertTrue(e.message!!.startsWith("Drive HTTP 401"), e.message)
    }

    @Test
    fun mergingKeepsThePhonesRules() {
        val tablet = buildJsonObject {
            put("schema", RustifyBackup.SCHEMA); put("updatedAt", 200); put("device", "tab"); put("deviceName", "Tab S8")
            putJsonObject("mappings") { put("a", "yt-new"); put("c", "yt-c") }
            putJsonArray("userAlternatives") { add(JsonPrimitive("a")) }
            putJsonObject("local") {
                putJsonArray("playlists") { add(buildJsonObject { put("id", "p1"); put("name", "Old name"); put("updatedAt", 50) }) }
                putJsonArray("favorites") { add(JsonPrimitive("x")) }
            }
            putJsonArray("metrics") { add(buildJsonObject { put("trackId", "t"); put("startedAt", 1); put("listenedMs", 9) }) }
        }
        val phone = buildJsonObject {
            put("schema", RustifyBackup.SCHEMA); put("updatedAt", 100); put("device", "phone"); put("deviceName", "Pixel")
            putJsonObject("mappings") { put("a", "yt-old"); put("b", "yt-b") }
            putJsonArray("userAlternatives") { add(JsonPrimitive("b")) }
            putJsonObject("local") {
                putJsonArray("playlists") { add(buildJsonObject { put("id", "p1"); put("name", "New name"); put("updatedAt", 90) }) }
                putJsonArray("favorites") { add(JsonPrimitive("y")) }
            }
            putJsonArray("metrics") {
                add(buildJsonObject { put("trackId", "t"); put("startedAt", 1); put("listenedMs", 9) })
                add(buildJsonObject { put("trackId", "u"); put("startedAt", 2); put("listenedMs", 9) })
            }
        }
        val merged = RustifyBackup.merge(phone, tablet)
        assertEquals("tab", merged["device"]!!.jsonPrimitive.content, "the newer container's device")
        assertEquals(200L, merged["updatedAt"]!!.jsonPrimitive.content.toLong())
        assertEquals(
            mapOf("a" to "yt-new", "b" to "yt-b", "c" to "yt-c"),
            merged["mappings"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
            "mappings: union, the newer container winning"
        )
        assertEquals(setOf("a", "b"), merged["userAlternatives"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val local = merged["local"]!!.jsonObject
        assertEquals("New name", local["playlists"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content, "a playlist: its own newer edit")
        assertEquals(setOf("x", "y"), local["favorites"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(2, merged["metrics"]!!.jsonArray.size, "metrics: union, a listen counted once")
        assertTrue(merged["places"] is JsonObject, "and the places block, even from containers without one")
    }

    private object NoPlatform : AppPlatform by unusedPlatform()
}

private fun unusedPlatform(): AppPlatform =
    java.lang.reflect.Proxy.newProxyInstance(AppPlatform::class.java.classLoader, arrayOf(AppPlatform::class.java)) { _, method, _ ->
        error("the test does not use ${method.name}")
    } as AppPlatform
