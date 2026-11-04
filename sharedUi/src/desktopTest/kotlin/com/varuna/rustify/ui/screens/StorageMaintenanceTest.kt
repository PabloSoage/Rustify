package com.varuna.rustify.ui.screens

import coil3.PlatformContext
import com.varuna.rustify.bridge.LocalPlaylist
import com.varuna.rustify.bridge.MatchStore
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.platform.MemoryStore
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.newRustifyImageLoader
import com.varuna.rustify.ui.readAllText
import com.varuna.rustify.ui.writeAllText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageMaintenanceTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("storage_maintenance_test").toFile()
    }

    @AfterTest
    fun tearDown() {
        MatchStore.onReloadForTesting = null
        tempDir.deleteRecursively()
    }

    @Test
    fun testValidateMappingsRejectsNonStrings() {
        assertNull(MatchStore.validateMappings("""{"a": 1}"""))
        assertNull(MatchStore.validateMappings("""{"a": true}"""))
        assertNull(MatchStore.validateMappings("""{"a": null}"""))
        assertNull(MatchStore.validateMappings("""{"a": ["arr"]}"""))
        assertNull(MatchStore.validateMappings("""[1, 2, 3]"""))
        assertNull(MatchStore.validateMappings("not json"))

        val valid = MatchStore.validateMappings("""{"track1": "yt1", "track2": "yt2"}""")
        assertNotNull(valid)
        assertEquals(mapOf("track1" to "yt1", "track2" to "yt2"), valid)
    }

    @Test
    fun testImportMappingsProtectsExistingFileOnInvalidJson() = runBlocking {
        val mappingsFile = tempDir.resolve("youtube_mappings.json")
        val initialContent = """{"good_track":"good_yt"}"""
        mappingsFile.writeText(initialContent)

        var reloadCalled = false
        MatchStore.onReloadForTesting = { reloadCalled = true }

        // Attempt invalid import with number value
        val success = MatchStore.importAll(tempDir, """{"a": 1}""")
        assertFalse(success)
        assertFalse(reloadCalled)
        assertEquals(initialContent, mappingsFile.readText())

        // Attempt valid import
        val validSuccess = MatchStore.importAll(tempDir, """{"new_track": "new_yt"}""")
        assertTrue(validSuccess)
        assertTrue(reloadCalled)
        assertEquals(mapOf("new_track" to "new_yt"), MatchStore.readAll(tempDir))
    }

    @Test
    fun testImportLocalUserDataProtectsExistingFilesOnInvalidData() {
        val playlistsFile = tempDir.resolve("local_playlists.json")
        val favoritesFile = tempDir.resolve("local_favorites.json")

        val initialPlaylist = listOf(LocalPlaylist(id = "p1", name = "Initial Playlist"))
        val initialPlaylistsJson = RustifyJson.encodeToString(initialPlaylist)
        val initialFavoritesJson = """["fav_1","fav_2"]"""

        playlistsFile.writeText(initialPlaylistsJson)
        favoritesFile.writeText(initialFavoritesJson)

        var reloaded = false
        val reloadHook = { reloaded = true }

        // 1. Invalid playlists format: integers instead of LocalPlaylist objects
        val invalidPlaylistsPayload = """
            {
              "schema": "rustify-local-user-data",
              "version": 1,
              "exportedAt": 1000,
              "playlists": [1, 2, 3],
              "favorites": ["fav_1"]
            }
        """.trimIndent()

        val r1 = LocalDataBackup.importLocalUserData(tempDir, invalidPlaylistsPayload, reloadHook)
        assertFalse(r1)
        assertFalse(reloaded)
        assertEquals(initialPlaylistsJson, playlistsFile.readText())
        assertEquals(initialFavoritesJson, favoritesFile.readText())

        // 2. Invalid favorites format: numbers instead of string IDs
        val invalidFavoritesPayload = """
            {
              "schema": "rustify-local-user-data",
              "version": 1,
              "exportedAt": 1000,
              "playlists": [],
              "favorites": [123, 456]
            }
        """.trimIndent()

        val r2 = LocalDataBackup.importLocalUserData(tempDir, invalidFavoritesPayload, reloadHook)
        assertFalse(r2)
        assertFalse(reloaded)
        assertEquals(initialPlaylistsJson, playlistsFile.readText())
        assertEquals(initialFavoritesJson, favoritesFile.readText())

        // 3. Invalid schema
        val wrongSchemaPayload = """
            {
              "schema": "unknown-schema",
              "version": 1,
              "exportedAt": 1000,
              "playlists": [],
              "favorites": []
            }
        """.trimIndent()

        val r3 = LocalDataBackup.importLocalUserData(tempDir, wrongSchemaPayload, reloadHook)
        assertFalse(r3)
        assertFalse(reloaded)
        assertEquals(initialPlaylistsJson, playlistsFile.readText())
        assertEquals(initialFavoritesJson, favoritesFile.readText())
    }

    @Test
    fun testExportAndImportValidLocalUserData() {
        val initialPlaylists = listOf(LocalPlaylist(id = "p1", name = "Test Playlist"))
        tempDir.resolve("local_playlists.json").writeText(RustifyJson.encodeToString(initialPlaylists))
        tempDir.resolve("local_favorites.json").writeText("""["fav_a","fav_b"]""")

        val exported = LocalDataBackup.exportLocalUserData(tempDir)
        assertNotNull(exported)
        assertTrue(exported.contains("rustify-local-user-data"))

        // Delete files
        tempDir.resolve("local_playlists.json").delete()
        tempDir.resolve("local_favorites.json").delete()

        var reloaded = false
        val success = LocalDataBackup.importLocalUserData(tempDir, exported) { reloaded = true }
        assertTrue(success)
        assertTrue(reloaded)
        assertTrue(tempDir.resolve("local_playlists.json").exists())
        assertTrue(tempDir.resolve("local_favorites.json").exists())
    }

    @Test
    fun testDesktopImageLoaderConfiguresPersistentDiskCache() {
        val prefs = MemoryStore()
        prefs.putInt("cache_max_mb", 350)

        val loader = newRustifyImageLoader(PlatformContext.INSTANCE, tempDir, prefs)
        val diskCache = loader.diskCache
        assertNotNull(diskCache)

        val expectedDir = tempDir.resolve("image_cache").toPath().toAbsolutePath().normalize()
        val actualDir = diskCache.directory.toNioPath().toAbsolutePath().normalize()
        assertEquals(expectedDir, actualDir)
        assertEquals(350L * 1024 * 1024, diskCache.maxSize)
    }

    @Test
    fun testClearingTheImageCacheEmptiesWhatTheLoaderHolds() {
        val diskCache = assertNotNull(newRustifyImageLoader(PlatformContext.INSTANCE, tempDir, MemoryStore()).diskCache)
        val editor = assertNotNull(diskCache.openEditor("cover"))
        diskCache.fileSystem.write(editor.data) { writeUtf8("a cover") }
        editor.commitAndOpenSnapshot()?.close()
        assertTrue(diskCache.size > 0, "the entry is on disk")

        diskCache.clear()

        assertEquals(0L, diskCache.size)
        assertNull(diskCache.openSnapshot("cover"))
    }
}
