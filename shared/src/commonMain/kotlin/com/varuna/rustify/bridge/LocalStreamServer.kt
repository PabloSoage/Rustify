// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/LocalStreamServer.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.platform.Log
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Kotlin face of the loopback streaming server in `core_engine/src/server/`.
 *
 * The point of the server is that Media3 can play a plain `http://127.0.0.1:…` URL, so the disk
 * cache and Deezer's decryption live behind an ordinary HTTP response instead of a custom
 * `DataSource`. What decides *when* that happens is [com.varuna.rustify.audio.StreamRouting].
 *
 * The one rule worth keeping in mind: **this server only ever serves files that already exist.**
 * [registerReady] hands back a URL when the bytes are on disk and says "not cached" when they are
 * not, so nothing can arrive at the socket and have to wait for a download. A player that is told
 * "not cached" plays the upstream URL, which is exactly what it did before this server existed.
 *
 * Safety, because a local server is a real attack surface: it binds `127.0.0.1` and only that (the
 * server this replaces was deleted in E11 precisely because its `0.0.0.0` fallback put an endpoint
 * on the LAN), the port is ephemeral, and every request must carry a per-process random token. On
 * Android any installed app can reach localhost, so binding to loopback is necessary and nowhere
 * near sufficient.
 */
object LocalStreamServer {

    private const val TAG = "LocalStreamServer"

    @Volatile private var port: Int = 0
    @Volatile private var token: String = ""
    @Volatile private var running: Boolean = false

    /** Whether the server came up. When false, callers play upstream URLs directly. */
    val isRunning: Boolean get() = running

    /** The bound loopback port, or 0 if the server is not running. Diagnostics only. */
    val boundPort: Int get() = port

    /** Loopback URL for an already registered stream handle, for testing or loopback playback. */
    fun streamUrl(handle: String): String? {
        if (!running || port == 0 || token.isBlank() || handle.isBlank()) return null
        return "http://127.0.0.1:$port/stream/$handle?t=$token"
    }

    /**
     * Starts the server if it is not already up. Idempotent.
     *
     * `suspend` rather than `@Synchronized`: it crosses JNI, and the whole of point N is that
     * crossing JNI on the main thread is how this app produces an ANR. Concurrent callers may both
     * reach the bridge, which is harmless — `server::start` is itself idempotent and the loser
     * drops its listener.
     */
    suspend fun ensureStarted(): Boolean {
        if (running) return true
        val answer = runCatching { NativeEngine.startLocalServer() }
            .onFailure { Log.e(TAG, "startLocalServer threw", it) }
            .getOrNull() ?: return false

        return runCatching {
            val json = parseJsonObject(answer) ?: error("not a JSON object")
            if (!json.optBoolean("success", true)) {
                Log.w(TAG, "server refused to start: ${json.optString("error")}")
                return false
            }
            port = json.optInt("port", 0)
            token = json.optString("token")
            running = port != 0
            if (running) Log.i(TAG, "local server on 127.0.0.1:$port")
            running
        }.getOrElse {
            Log.e(TAG, "could not read the server's answer: $answer", it)
            false
        }
    }

    /**
     * Asks the server for a URL to play, and starts a background download when there is nothing to
     * play yet.
     *
     * @param cacheKey stable per track. Sanitised on the Rust side before it touches the filesystem.
     * @param cacheRoot where the stream cache lives.
     * @param upstreamUrl where to fetch the bytes from if they are not cached. **Empty asks without
     *   fetching**: answer if it is on disk, say nothing if it is not, start nothing either way.
     * @param deezerSngId set when the bytes are Deezer-encrypted, so the download decrypts them.
     *
     * @return the loopback URL when the track is on disk, or null — which means *play what you
     *   already had*, not that anything went wrong.
     */
    suspend fun registerReady(
        cacheKey: String,
        cacheRoot: String,
        upstreamUrl: String = "",
        mime: String? = null,
        deezerSngId: String? = null
    ): String? {
        if (cacheKey.isBlank() || cacheRoot.isBlank()) return null

        val request = buildJsonObject {
            put("upstreamUrl", upstreamUrl)
            put("cacheKey", cacheKey)
            put("cacheRoot", cacheRoot)
            mime?.let { put("mime", it) }
            deezerSngId?.let { put("deezerSngId", it) }
        }

        val raw = runCatching { NativeEngine.registerLocalStream(request.toString()) }
            .onFailure { Log.w(TAG, "registerLocalStream threw", it) }
            .getOrNull() ?: return null

        return runCatching {
            val json = parseJsonObject(raw) ?: error("not a JSON object")
            when (json.optString("status")) {
                "ready" -> json.optString("url").takeIf { it.isNotBlank() }
                "refused" -> {
                    // A refusal is a programming error, not a user-facing one: the only ways to get
                    // here are a local URI (deliberately never routed) or a missing cache key.
                    Log.d(TAG, "not routed: ${json.optString("reason")}")
                    null
                }
                else -> null
            }
        }.getOrElse {
            Log.w(TAG, "could not read the registration answer: $raw", it)
            null
        }
    }

    /**
     * Registers an encrypted Deezer track to be **streamed and decrypted through the server**, and
     * returns the URL to play.
     *
     * The other half of [registerReady], and the answer to a different question. That one asks "is
     * this already on disk?" and says nothing if it is not. This one says "play this now, from the
     * CDN" — nothing has to be downloaded first, because the decryption happens to the bytes as they
     * pass through.
     *
     * @return the loopback URL, or null when the server is not running — in which case the caller
     *   plays the track the way it did before this existed.
     */
    suspend fun registerDeezerProxy(
        upstreamUrl: String,
        sngId: String,
        mime: String? = null,
        cacheKey: String? = null,
        cacheRoot: String? = null
    ): String? {
        if (upstreamUrl.isBlank() || sngId.isBlank()) return null
        if (!ensureStarted()) return null

        val request = buildJsonObject {
            put("mode", "proxy")
            put("upstreamUrl", upstreamUrl)
            put("deezerSngId", sngId)
            mime?.let { put("mime", it) }
            // Both, or neither: the copy is written as the track plays, so the server needs to know
            // where before it starts. Leaving them out means "play it, do not keep it".
            if (!cacheKey.isNullOrBlank() && !cacheRoot.isNullOrBlank()) {
                put("cacheKey", cacheKey)
                put("cacheRoot", cacheRoot)
            }
        }

        val raw = runCatching { NativeEngine.registerLocalStream(request.toString()) }
            .onFailure { Log.w(TAG, "registerLocalStream (proxy) threw", it) }
            .getOrNull() ?: return null

        return runCatching {
            (parseJsonObject(raw) ?: error("not a JSON object")).takeIf { it.optString("status") == "ready" }
                ?.optString("url")
                ?.takeIf { it.isNotBlank() }
        }.getOrElse {
            Log.w(TAG, "could not read the proxy registration answer: $raw", it)
            null
        }
    }

    /**
     * Registers an existing file on this device with the streaming server.
     *
     * @return the stream handle, or null if the server is not running or the file does not exist.
     */
    suspend fun registerFile(path: String, mime: String? = null): String? {
        if (!ensureStarted()) return null
        val raw = runCatching { NativeEngine.registerLocalFileNative(path, mime.orEmpty()) }
            .onFailure { Log.w(TAG, "registerLocalFile threw", it) }
            .getOrNull() ?: return null

        return runCatching {
            val json = parseJsonObject(raw) ?: error("not a JSON object")
            json.optString("handle").takeIf { it.isNotBlank() }
        }.getOrElse {
            Log.w(TAG, "could not read registerLocalFile answer: $raw", it)
            null
        }
    }

    /** Drops a registration. The handle is the last path segment of the URL [registerReady] gave. */
    fun forget(streamUrl: String) {
        val handle = streamUrl.substringAfterLast("/stream/", "").substringBefore('?')
        if (handle.isBlank()) return
        runCatching { NativeEngine.forgetLocalStreamNative(handle) }
    }
}
