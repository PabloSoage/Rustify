package com.varuna.rustify.cast

import android.content.Context
import android.util.Log
import com.varuna.rustify.bridge.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    private const val PREFS = "rustify_settings"

    /**
     * The user's switch. **Off by default**, like Deezer.
     *
     * With it off, nothing here runs: no discovery, no listener, no button. Turning it on is the act
     * that says "I accept that this app opens a port on my network while I am casting".
     */
    const val ENABLED_KEY = "settings_casting_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED_KEY, enabled).apply()
        if (!enabled) {
            // Turning the switch off has to end whatever is running, or the switch is a label. Via
            // [abandon] rather than the bridge alone: closing the port while [device] still held a
            // device left everything that asks "am I casting?" answering yes to a session with no
            // port behind it.
            abandon()
        }
    }

    /** The device currently being cast to, or null. */
    @Volatile
    var device: CastDiscovery.Device? = null
        private set

    /** Is a session open, as the **core** sees it — not as this object remembers it. */
    fun isActive(): Boolean = runCatching {
        JSONObject(NativeEngine.castSessionNative()).optBoolean("active", false)
    }.getOrDefault(false)

    /**
     * Starts casting [handle] to [target].
     *
     * [handle] is what `LocalStreamServer` already registered for the track — the same opaque
     * identifier the phone's own player would use. Nothing new is registered and nothing extra is
     * exposed: the only difference is which host is in the URL.
     *
     * @return null on success, or a message saying why not.
     */
    suspend fun start(
        context: Context,
        target: CastDiscovery.Device,
        handle: String,
        title: String,
        artist: String,
        durationMs: Long
    ): String? = withContext(Dispatchers.IO) {
        if (!isEnabled(context)) return@withContext "casting is switched off"

        val deviceAddress = CastDiscovery.literalAddress(target.address)
            ?: return@withContext "could not work out ${target.friendlyName}'s address"

        val ours = CastDiscovery.localAddressOn()
            ?: return@withContext "this phone has no address on a local network"

        // A guard, not the guard: the core checks every connection's peer regardless. This is here
        // so that opening onto the wrong interface fails loudly instead of quietly not working.
        if (!CastDiscovery.sameNetwork(ours, deviceAddress)) {
            return@withContext "${target.friendlyName} is not on the same network as this phone"
        }

        val opened = runCatching {
            JSONObject(NativeEngine.openCastListenerNative(ours, deviceAddress))
        }.getOrElse {
            Log.e(TAG, "could not open the cast listener", it)
            return@withContext "could not open the connection"
        }
        if (!opened.optBoolean("success", false)) {
            return@withContext opened.optString("error").ifBlank { "could not open the connection" }
        }

        val url = runCatching {
            JSONObject(NativeEngine.castUrlForNative(handle)).optString("url")
        }.getOrNull().orEmpty()
        if (url.isBlank()) {
            stop()
            return@withContext "the track is not being served, so there is nothing to send"
        }

        val played = DlnaController.play(target.controlUrl, url, title, artist, durationMs)
        if (!played) {
            // Failing to play must not leave the port open. This is the case that would otherwise
            // leave the phone serving to the LAN with nothing listening.
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
     *
     * The device is left holding a URL it can no longer reach, and that is the right way round: the
     * port closes first and the device finds out by failing, rather than staying open while a polite
     * `Stop` is in flight. Blocking and argument-free, so it can run on the way out.
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

    /** Where the device says it is. The device owns the clock once it is playing, not the phone. */
    suspend fun positionMs(): Long? {
        val current = device ?: return null
        return DlnaController.position(current.controlUrl)
    }
}
