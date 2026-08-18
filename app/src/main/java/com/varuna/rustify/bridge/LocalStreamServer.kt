package com.varuna.rustify.bridge

import android.util.Log
import org.json.JSONObject

/**
 * Kotlin face of the loopback streaming server in `core_engine/src/server/`.
 *
 * The point of the server is that Media3 can play a plain `http://127.0.0.1:…` URL, so the disk
 * cache and any decryption live behind an ordinary HTTP response instead of a custom `DataSource`.
 *
 * **Nothing is routed through it yet.** It starts, answers `/health`, and holds an empty handle
 * table. Moving the Deezer path and the stream cache onto it is a change to how audio actually
 * reaches the player, and that is not something to land in the same build as the server itself —
 * see `docs/stremio-core/PLAN-3.x.md` §4.3 (C6).
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
    @Volatile private var running: Boolean = false

    /** Whether the server came up. When false, callers fall back to playing upstream URLs directly. */
    val isRunning: Boolean get() = running

    /** The bound loopback port, or 0 if the server is not running. Diagnostics only. */
    val boundPort: Int get() = port

    /**
     * Starts the server if it is not already up. Idempotent, and safe to call from any thread —
     * but note it crosses JNI, so it blocks the caller; do not call it from the main thread.
     */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (running) return true
        val answer = runCatching { NativeEngine.startLocalServerNative() }
            .onFailure { Log.e(TAG, "startLocalServerNative threw", it) }
            .getOrNull() ?: return false

        return runCatching {
            val json = JSONObject(answer)
            if (json.optBoolean("success", true).not()) {
                Log.w(TAG, "server refused to start: ${json.optString("error")}")
                return false
            }
            port = json.optInt("port", 0)
            running = port != 0
            if (running) Log.i(TAG, "local server on 127.0.0.1:$port")
            running
        }.getOrElse {
            Log.e(TAG, "could not read the server's answer: $answer", it)
            false
        }
    }

    /**
     * Registers [source] and returns the URL to hand to the player.
     *
     * Returns null when the server is not running or refused the registration, which callers should
     * treat as "play [source] directly" rather than as an error: that is what happened before this
     * server existed and it still works.
     *
     * @param source a path on this device, or an `http(s)` URL fetched on first request.
     * @param cachePath where an upstream is stored. An upstream with no cache path is refused —
     *   there would be nowhere to put the bytes.
     * @param expiresAtMs epoch millis after which the registration is dropped; 0 never expires.
     */
    fun urlFor(
        source: String,
        mime: String? = null,
        cachePath: String? = null,
        expiresAtMs: Long = 0L
    ): String? {
        if (!running) return null
        val url = runCatching {
            NativeEngine.registerLocalStreamNative(source, mime ?: "", cachePath ?: "", expiresAtMs)
        }.getOrNull()
        return url?.takeIf { it.isNotBlank() }
    }

    /** Drops a registration. The handle is the last path segment of the URL [urlFor] returned. */
    fun forget(streamUrl: String) {
        val handle = streamUrl.substringAfterLast("/stream/", "").substringBefore('?')
        if (handle.isBlank()) return
        runCatching { NativeEngine.forgetLocalStreamNative(handle) }
    }
}
