// shared/src/commonMain/kotlin/com/varuna/rustify/cast/CastSession.kt
package com.varuna.rustify.cast

import com.varuna.rustify.bridge.NativeEngine
import kotlin.concurrent.Volatile
import com.varuna.rustify.platform.IO
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A cast session, start to finish — E16.
 *
 * This is the file that decides **when** the local server stops being loopback-only, so the order of
 * operations here is the security boundary, not a style choice:
 *
 *  1. The feature is off unless the user turned it on ([isEnabled]).
 *  2. A device has to have been picked, which means its address is known.
 *  3. The server opens **to one interface**, answering **only that address** — the core refuses
 *     anything else.
 *  4. It closes on stop, on failure, and on anything that invalidates the address.
 *
 * Step 3 is enforced in Rust (`server/lan.rs`) rather than trusted here. This side deciding
 * correctly is not a guarantee; the core refusing incorrect input is.
 */
object CastSession {

    private const val TAG = "CastSession"

    /**
     * The user's switch. **Off by default**, like Deezer.
     *
     * With it off, nothing here runs: no discovery, no listener, no button. Turning it on is the act
     * that says "I accept that this app opens a port on my network while I am casting".
     */
    const val ENABLED_KEY = "settings_casting_enabled"

    fun isEnabled(store: KeyValueStore): Boolean =
        store.getBoolean(ENABLED_KEY, false)

    fun setEnabled(store: KeyValueStore, enabled: Boolean) {
        store.putBoolean(ENABLED_KEY, enabled)
        if (!enabled) {
            // Turning the switch off has to end whatever is running, or the switch is a label.
            abandon()
        }
    }

    /** The device currently being cast to, or null. */
    @Volatile
    var device: CastDiscovery.Device? = null
        private set

    /** Is a session open, as the **core** sees it — not as this object remembers it. */
    fun isActive(): Boolean = runCatching {
        val raw = NativeEngine.castSessionNative()
        Json.parseToJsonElement(raw).jsonObject["active"]?.jsonPrimitive?.booleanOrNull ?: false
    }.getOrDefault(false)

    /**
     * Starts casting [handle] to [target].
     *
     * [handle] is what `LocalStreamServer` already registered for the track — the same opaque
     * identifier the host's own player would use. Nothing new is registered and nothing extra is
     * exposed: the only difference is which host is in the URL.
     *
     * @return null on success, or a message saying why not.
     */
    suspend fun start(
        store: KeyValueStore,
        target: CastDiscovery.Device,
        handle: String,
        title: String,
        artist: String,
        durationMs: Long
    ): String? = withContext(Dispatchers.IO) {
        if (!isEnabled(store)) return@withContext "casting is switched off"

        val deviceAddress = CastDiscovery.literalAddress(target.address)
            ?: return@withContext "could not work out ${target.friendlyName}'s address"

        val ours = CastDiscovery.localAddressOn()
            ?: return@withContext "this device has no address on a local network"

        // A guard, not the guard: the core checks every connection's peer regardless. This is here
        // so that opening onto the wrong interface fails loudly instead of quietly not working.
        if (!CastDiscovery.sameNetwork(ours, deviceAddress)) {
            return@withContext "${target.friendlyName} is not on the same network as this device"
        }

        val opened = runCatching {
            val raw = NativeEngine.openCastListenerNative(ours, deviceAddress)
            Json.parseToJsonElement(raw).jsonObject
        }.getOrElse {
            Log.e(TAG, "could not open the cast listener", it)
            return@withContext "could not open the connection"
        }

        val success = opened["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!success) {
            val error = opened["error"]?.jsonPrimitive?.content ?: "could not open the connection"
            return@withContext error
        }

        val url = runCatching {
            val raw = NativeEngine.castUrlForNative(handle)
            Json.parseToJsonElement(raw).jsonObject["url"]?.jsonPrimitive?.content.orEmpty()
        }.getOrNull().orEmpty()

        if (url.isBlank()) {
            stop()
            return@withContext "the track is not being served, so there is nothing to send"
        }

        val played = DlnaController.play(target.controlUrl, url, title, artist, durationMs)
        if (!played) {
            stop()
            return@withContext "${target.friendlyName} did not accept the track"
        }

        device = target
        Log.i(TAG, "casting to ${target.friendlyName} at $deviceAddress")
        null
    }

    /** Ends the session and closes the port. Safe to call when nothing is running. */
    suspend fun stop() = withContext(Dispatchers.IO) {
        val current = device
        device = null
        if (current != null) runCatching { DlnaController.stop(current.controlUrl) }
        runCatching { NativeEngine.closeCastListenerNative() }
        Unit
    }

    /**
     * Closes the port without telling the device — for teardown, where there is no time to wait on a
     * SOAP round trip.
     */
    fun abandon() {
        device = null
        runCatching { NativeEngine.closeCastListenerNative() }
    }

    suspend fun pause(): Boolean {
        val current = device ?: return false
        return DlnaController.pause(current.controlUrl)
    }

    suspend fun resume(): Boolean {
        val current = device ?: return false
        return DlnaController.resume(current.controlUrl)
    }

    suspend fun seekTo(positionMs: Long): Boolean {
        val current = device ?: return false
        return DlnaController.seek(current.controlUrl, positionMs)
    }

    /** Where the device says it is. The device owns the clock once it is playing. */
    suspend fun positionMs(): Long? {
        val current = device ?: return null
        return DlnaController.position(current.controlUrl)
    }
}
