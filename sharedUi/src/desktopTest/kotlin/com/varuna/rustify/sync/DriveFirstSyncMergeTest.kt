package com.varuna.rustify.sync

import com.sun.net.httpserver.HttpServer
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.metrics.ListeningTracker
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.MemoryStore
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.readAllText
import com.varuna.rustify.ui.writeTextAtomically
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DriveFirstSyncMergeTest {

    private val servers = mutableListOf<HttpServer>()
    private val tempDirs = mutableListOf<File>()

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

    private class TestPlatform(baseDir: File) : AppPlatform {
        override val kind = AppPlatform.Kind.DESKTOP
        override val appVersion = "5.0.0-test"
        override val systemCountry = "ES"
        private val stores = mutableMapOf<String, KeyValueStore>()
        override fun prefs(name: String): KeyValueStore = synchronized(stores) {
            stores.getOrPut(name) { MemoryStore() }
        }
        override val filesDir: File = File(baseDir, "files").apply { mkdirs() }
        override val cacheDir: File = File(baseDir, "cache").apply { mkdirs() }
        override fun toast(message: String, long: Boolean) = Unit
        override fun openUrl(url: String) = Unit
        override fun share(text: String, subject: String?): Boolean = true
        override fun shareImage(file: File, text: String, mimeType: String): Boolean = true
        override fun copyToClipboard(label: String, text: String) = Unit
        override fun readClipboard(): String? = null
        override fun isOnline(): Boolean = true
    }

    private val mobilePlaceId = "spotify:track:place_mobile_t27"
    private val desktopPlaceId = "spotify:track:place_desktop_t27"
    private val deletedPlaceId = "spotify:track:place_deleted_t27"

    @AfterTest
    fun tearDown() = runBlocking {
        servers.forEach { it.stop(0) }
        runCatching { NativeEngine.forgetTrackPlace(mobilePlaceId) }
        runCatching { NativeEngine.forgetTrackPlace(desktopPlaceId) }
        runCatching { NativeEngine.forgetTrackPlace(deletedPlaceId) }
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun updatingPhoneFirstSyncMergesBothDevicesWithoutOverwriting(): Unit = runBlocking {
        val tempDir = Files.createTempDirectory("rustify-test-first-sync-").toFile()
        tempDirs += tempDir
        val platform = TestPlatform(tempDir)
        val filesDir = platform.filesDir
        NativeEngine.initCacheDir(filesDir.absolutePath)

        // ── 1. Phone state before sync (offline for days, has unsynced data) ──
        // Local playlists: one unique to phone, one conflicting with desktop (phone has newer edit)
        val mobilePlaylists = buildJsonArray {
            add(buildJsonObject {
                put("id", "pl_conflict")
                put("name", "Mobile Conflict (Newer)")
                put("updatedAt", 2000L)
            })
            add(buildJsonObject {
                put("id", "pl_mobile_only")
                put("name", "Mobile Unique Playlist")
                put("updatedAt", 1500L)
            })
        }
        filesDir.child("local_playlists.json").writeTextAtomically(mobilePlaylists.toString())

        // Local favorites: phone has its own favorite
        val mobileFavorites = buildJsonArray {
            add(JsonPrimitive("fav_mobile"))
        }
        filesDir.child("local_favorites.json").writeTextAtomically(mobileFavorites.toString())

        // Mappings & User alternatives
        val mobileMappings = buildJsonObject {
            put("spotify:mobile_track", "yt_mobile_video")
        }
        filesDir.child("youtube_mappings.json").writeTextAtomically(mobileMappings.toString())
        filesDir.child("user_alternatives.json").writeTextAtomically(buildJsonArray { add(JsonPrimitive("spotify:mobile_track")) }.toString())

        // Metrics: phone has its own listening event
        ListeningTracker.importEvents(
            platform,
            """[{"trackId":"metric_mobile","trackName":"Mobile Track","startedAt":100,"listenedMs":200,"completed":true,"errored":false}]"""
        )

        // Places: phone has an active place in a long track (>= 20 min)
        NativeEngine.recordTrackPlace(mobilePlaceId, positionMs = 300_000L, durationMs = 1_800_000L)

        // Tombstone requirement: phone previously had deletedPlaceId, which the user dismissed/deleted.
        // This writes a tombstone to forgotten with timestamp 'now'.
        NativeEngine.recordTrackPlace(deletedPlaceId, positionMs = 250_000L, durationMs = 1_800_000L)
        NativeEngine.forgetTrackPlace(deletedPlaceId)

        // ── 2. Fake Drive server holding the Desktop's existing backup ──
        var uploadedBody: String? = null
        val remoteDesktopBackup = buildJsonObject {
            put("schema", RustifyBackup.SCHEMA)
            put("version", RustifyBackup.VERSION)
            put("updatedAt", 1000L)
            put("device", "desktop_dev_id")
            put("deviceName", "Desktop PC")
            putJsonObject("mappings") {
                put("spotify:desktop_track", "yt_desktop_video")
            }
            putJsonArray("userAlternatives") {
                add(JsonPrimitive("spotify:desktop_track"))
            }
            putJsonObject("local") {
                putJsonArray("playlists") {
                    add(buildJsonObject {
                        put("id", "pl_conflict")
                        put("name", "Desktop Conflict (Older)")
                        put("updatedAt", 500L) // Older than mobile's 2000L
                    })
                    add(buildJsonObject {
                        put("id", "pl_desktop_only")
                        put("name", "Desktop Unique Playlist")
                        put("updatedAt", 800L)
                    })
                }
                putJsonArray("favorites") {
                    add(JsonPrimitive("fav_desktop"))
                }
            }
            putJsonObject("ytm") {
                put("version", 1)
                putJsonArray("favorites") {}
                putJsonArray("playlists") {}
                putJsonArray("savedAlbums") {}
                putJsonArray("savedArtists") {}
            }
            putJsonArray("metrics") {
                add(buildJsonObject {
                    put("trackId", "metric_desktop")
                    put("trackName", "Desktop Track")
                    put("startedAt", 300L)
                    put("listenedMs", 400L)
                    put("completed", true)
                    put("errored", false)
                })
            }
            putJsonObject("places") {
                putJsonArray("sessions") {}
                putJsonArray("bookmarks") {
                    // Desktop has its own place
                    add(buildJsonObject {
                        putJsonObject("track") { put("id", desktopPlaceId) }
                        put("position_ms", 500_000L)
                        put("duration_ms", 2_000_000L)
                        put("updated_at_ms", 500L)
                    })
                    // Desktop also still has the deletedPlaceId bookmark from an older sync
                    add(buildJsonObject {
                        putJsonObject("track") { put("id", deletedPlaceId) }
                        put("position_ms", 250_000L)
                        put("duration_ms", 1_800_000L)
                        put("updated_at_ms", 500L)
                    })
                }
                putJsonArray("forgotten") {}
            }
        }

        val driveUrl = serve { method, path, _, body ->
            when {
                method == "GET" && path == "/files" -> {
                    200 to """{"files":[{"id":"f_backup_1","name":"rustify-backup.json"}]}"""
                }
                method == "GET" && path == "/files/f_backup_1" -> {
                    200 to remoteDesktopBackup.toString()
                }
                method == "PATCH" && path == "/upload/f_backup_1" -> {
                    uploadedBody = body
                    200 to """{"id":"f_backup_1"}"""
                }
                else -> 404 to "{}"
            }
        }

        // ── 3. Execute syncNow on the phone ──
        val driveApi = DriveApi(filesUrl = "$driveUrl/files", uploadUrl = "$driveUrl/upload")
        val sync = DriveSync(
            platform = platform,
            device = RustifyBackup.Device("phone_dev_id", "Pixel 8"),
            spotifyRepo = null,
            ytmRepo = null,
            drive = driveApi
        )
        sync.syncNow("test_token")

        // ── 4. VERIFY: Phone Disk after sync ──
        // (a) Local Playlists: union of both devices + conflict resolution by higher updatedAt
        val diskPlaylists = Json.parseToJsonElement(filesDir.child("local_playlists.json").readAllText()).jsonArray
        val playlistsById = diskPlaylists.associate {
            it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(3, playlistsById.size, "Disk must contain union of playlists (3 distinct IDs)")
        assertEquals("Mobile Conflict (Newer)", playlistsById["pl_conflict"], "Conflict winner must be higher updatedAt (mobile)")
        assertEquals("Mobile Unique Playlist", playlistsById["pl_mobile_only"])
        assertEquals("Desktop Unique Playlist", playlistsById["pl_desktop_only"])

        // (b) Local Favorites: set union (neither device's favorite lost)
        val diskFavorites = Json.parseToJsonElement(filesDir.child("local_favorites.json").readAllText()).jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("fav_mobile", "fav_desktop"), diskFavorites, "Favorites on disk must be union of both devices")

        // (c) Mappings & User alternatives: union preserved
        val diskMappings = Json.parseToJsonElement(filesDir.child("youtube_mappings.json").readAllText()).jsonObject
            .mapValues { it.value.jsonPrimitive.content }
        assertEquals(mapOf("spotify:mobile_track" to "yt_mobile_video", "spotify:desktop_track" to "yt_desktop_video"), diskMappings)

        val diskUserAlts = Json.parseToJsonElement(filesDir.child("user_alternatives.json").readAllText()).jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("spotify:mobile_track", "spotify:desktop_track"), diskUserAlts)

        // (d) Metrics: union deduplicated
        val diskMetricsJson = ListeningTracker.loadEventsJson(platform)
        assertTrue(diskMetricsJson.contains("metric_mobile"), "Disk metrics must contain mobile metric")
        assertTrue(diskMetricsJson.contains("metric_desktop"), "Disk metrics must contain desktop metric")

        // (e) Places: core must hold mobile place AND desktop place; tombstoned place must NOT reappear
        val diskPlaces = Json.parseToJsonElement(NativeEngine.placesExport()).jsonObject
        val diskBookmarkTrackIds = diskPlaces["bookmarks"]?.jsonArray?.mapNotNull {
            it.jsonObject["track"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        }?.toSet() ?: emptySet()

        assertTrue(diskBookmarkTrackIds.contains(mobilePlaceId), "Mobile place must be preserved on phone")
        assertTrue(diskBookmarkTrackIds.contains(desktopPlaceId), "Desktop place must be merged into phone")
        assertFalse(diskBookmarkTrackIds.contains(deletedPlaceId), "Tombstoned place must NOT reappear on phone")

        // ── 5. VERIFY: Container Uploaded to Drive ──
        assertNotNull(uploadedBody, "Container must have been uploaded to Drive via PATCH")
        val uploaded = Json.parseToJsonElement(uploadedBody).jsonObject

        // Playlists in uploaded container
        val uploadedPlaylists = uploaded["local"]!!.jsonObject["playlists"]!!.jsonArray.associate {
            it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(3, uploadedPlaylists.size)
        assertEquals("Mobile Conflict (Newer)", uploadedPlaylists["pl_conflict"])
        assertEquals("Mobile Unique Playlist", uploadedPlaylists["pl_mobile_only"])
        assertEquals("Desktop Unique Playlist", uploadedPlaylists["pl_desktop_only"])

        // Favorites in uploaded container
        val uploadedFavorites = uploaded["local"]!!.jsonObject["favorites"]!!.jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("fav_mobile", "fav_desktop"), uploadedFavorites)

        // Mappings in uploaded container
        val uploadedMappings = uploaded["mappings"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(mapOf("spotify:mobile_track" to "yt_mobile_video", "spotify:desktop_track" to "yt_desktop_video"), uploadedMappings)

        // Metrics in uploaded container
        val uploadedMetrics = uploaded["metrics"]!!.jsonArray.map { it.jsonObject["trackId"]!!.jsonPrimitive.content }.toSet()
        assertTrue(uploadedMetrics.contains("metric_mobile"))
        assertTrue(uploadedMetrics.contains("metric_desktop"))

        // Places in uploaded container: contains mobile place, desktop place, but NOT deleted place
        val uploadedBookmarks = uploaded["places"]!!.jsonObject["bookmarks"]!!.jsonArray.mapNotNull {
            it.jsonObject["track"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        }.toSet()
        assertTrue(uploadedBookmarks.contains(mobilePlaceId), "Uploaded container must have mobile place")
        assertTrue(uploadedBookmarks.contains(desktopPlaceId), "Uploaded container must have desktop place")
        assertFalse(uploadedBookmarks.contains(deletedPlaceId), "Uploaded container must NOT have tombstoned place")
    }
}
