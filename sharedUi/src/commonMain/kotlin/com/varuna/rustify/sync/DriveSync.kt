package com.varuna.rustify.sync

import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.platform.IO
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.currentTimeMillis
import com.varuna.rustify.platform.formUrlEncode
import com.varuna.rustify.platform.httpClient
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.util.parseObject
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Persistent sync preferences (in `rustify_settings`, the same store Settings uses), by the keys the
 * phone has always used.
 */
class DriveSyncPrefs(private val prefs: KeyValueStore) {
    /** Drive auth method: "play" (Play Services) by default, or "browser" (OAuth in the browser). */
    var authMethod: String
        get() = prefs.getString(K_METHOD) ?: "play"
        set(value) = prefs.putString(K_METHOD, value)

    var isLinked: Boolean
        get() = prefs.getBoolean(K_LINKED, false)
        set(value) = prefs.putBoolean(K_LINKED, value)

    var isAutoSync: Boolean
        get() = prefs.getBoolean(K_AUTO, false)
        set(value) = prefs.putBoolean(K_AUTO, value)

    /** Epoch millis of the last successful sync, or 0 if never. */
    var lastSyncMs: Long
        get() = prefs.getLong(K_LAST, 0L)
        set(value) = prefs.putLong(K_LAST, value)

    /** Name of the other device whose backup the last sync merged in, or null if it was this one's. */
    var pulledFrom: String?
        get() = prefs.getString(K_PULLED_FROM)
        set(value) = prefs.putString(K_PULLED_FROM, value)

    companion object {
        private const val K_LINKED = "drive_sync_linked"
        private const val K_AUTO = "drive_sync_auto"
        private const val K_LAST = "drive_sync_last_ms"
        private const val K_PULLED_FROM = "drive_sync_pulled_from"
        private const val K_METHOD = "drive_auth_method"

        fun of(platform: AppPlatform) = DriveSyncPrefs(platform.prefs())
    }
}

/**
 * Google Drive's REST v3 over `spaces=appDataFolder`: the one backup file, found, read, created or
 * replaced, with an access token from whichever sign-in the platform has. Token-agnostic on purpose.
 * [filesUrl] and [uploadUrl] are Drive's, and a test's fake Drive in their place.
 */
class DriveApi(
    private val filesUrl: String = "https://www.googleapis.com/drive/v3/files",
    private val uploadUrl: String = "https://www.googleapis.com/upload/drive/v3/files",
) {
    /** Twenty seconds to connect and thirty between bytes, OkHttp's timeouts before Ktor. */
    private val http by lazy {
        httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    /** Finds the backup file in appDataFolder. Returns its fileId or null. */
    suspend fun findBackupFileId(accessToken: String): String? {
        val q = formUrlEncode("name = '${RustifyBackup.DRIVE_FILE_NAME}'")
        val url = "$filesUrl?spaces=appDataFolder&q=$q&fields=${formUrlEncode("files(id,name,modifiedTime)")}&pageSize=10"
        val body = http.get(url) { bearer(accessToken) }.okText()
        val files = parseObject(body)["files"] as? JsonArray ?: return null
        val first = files.firstOrNull() as? JsonObject ?: return null
        return (first["id"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
    }

    /** Downloads and parses the container. Returns null if it does not exist in Drive. */
    suspend fun download(accessToken: String, fileId: String?): JsonObject? {
        val id = fileId ?: return null
        val body = http.get("$filesUrl/$id?alt=media") { bearer(accessToken) }.okText()
        return RustifyBackup.decode(body)
    }

    /**
     * Uploads the container: creates the file (multipart, with `parents:[appDataFolder]`) if it
     * does not exist, or updates it (PATCH media) if it already does. Returns the fileId.
     */
    suspend fun upload(accessToken: String, container: JsonObject, existingFileId: String?): String {
        val json = RustifyBackup.encode(container)
        if (existingFileId != null) {
            // UPDATE — PATCH media
            http.patch("$uploadUrl/$existingFileId?uploadType=media&fields=id") {
                bearer(accessToken)
                contentType(ContentType.Application.Json)
                setBody(json)
            }.okText()
            return existingFileId
        }
        // CREATE — multipart/related: metadata, then the media.
        val boundary = "rustify-${currentTimeMillis()}"
        val metadata = """{"name":"${RustifyBackup.DRIVE_FILE_NAME}","parents":["appDataFolder"]}"""
        val body = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=utf-8\r\n\r\n$metadata\r\n")
            append("--$boundary\r\nContent-Type: application/json; charset=utf-8\r\n\r\n$json\r\n")
            append("--$boundary--\r\n")
        }
        val answer = http.post("$uploadUrl?uploadType=multipart&fields=id") {
            bearer(accessToken)
            setBody(io.ktor.http.content.TextContent(body, ContentType.parse("multipart/related; boundary=$boundary")))
        }.okText()
        return (parseObject(answer)["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun HttpResponse.okText(): String {
        val text = bodyAsText()
        if (!status.isSuccess()) throw DriveException("Drive HTTP ${status.value}: ${text.take(500)}")
        return text
    }
}

class DriveException(message: String) : Exception(message)

/**
 * A full (bidirectional) sync against Drive:
 *   1. downloads the remote container (if it exists),
 *   2. builds the local container from disk,
 *   3. performs a **merge** (union + last-write-wins),
 *   4. applies the result to disk/repos (reloads in-memory state),
 *   5. uploads the merged container.
 *
 * Requires a valid access token with the `drive.appdata` scope (the platform's [DriveAuth]).
 */
class DriveSync(
    private val platform: AppPlatform,
    private val device: RustifyBackup.Device,
    private val spotifyRepo: SpotifyRepository?,
    private val ytmRepo: YtMusicRepository?,
    private val drive: DriveApi = DriveApi(),
) {
    /** Runs the sync. Throws (IO/parse) if anything fails; the caller turns it into an error state. */
    suspend fun syncNow(accessToken: String) = withContext(Dispatchers.IO) {
        val fileId = drive.findBackupFileId(accessToken)
        val remote: JsonObject? = fileId?.let { drive.download(accessToken, it) }
        val local: JsonObject = RustifyBackup.build(platform, device)

        val merged: JsonObject = if (remote != null) RustifyBackup.merge(local, remote) else local

        // Apply the result locally (writes files + reloads repos).
        RustifyBackup.apply(platform, merged, spotifyRepo, ytmRepo)

        // Upload the merged container (create or update).
        drive.upload(accessToken, merged, fileId)

        val prefs = DriveSyncPrefs.of(platform)
        prefs.lastSyncMs = currentTimeMillis()
        // Which device had written what this sync pulled in, so the screen can say that the
        // tablet's changes arrived. A container from before 4.7 has no name: say nothing then.
        prefs.pulledFrom = remote
            ?.takeIf { (it["device"] as? JsonPrimitive)?.contentOrNull != device.id }
            ?.let { (it["deviceName"] as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
    }
}
