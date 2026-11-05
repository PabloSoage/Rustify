// shared/src/commonMain/kotlin/com/varuna/rustify/cast/CastDiscovery.kt
package com.varuna.rustify.cast

import com.varuna.rustify.platform.IO
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finding things on the network that can play audio — E16, step one.
 *
 * ## Why SSDP and not the Cast SDK
 *
 * The design in `docs/16-casting.md` rests on a layer the Cast SDK may not be able to support: the
 * server answers **only** the address of the device being cast to, and for that the address has to
 * be known. SSDP hands it over by construction — a device answers from its own address, and its
 * description lives at a URL whose host *is* that address.
 *
 * That is the whole reason DLNA comes first. It is not a preference; it is the path on which the
 * layer that makes casting defensible is known to be buildable.
 *
 * ## What this does not do
 *
 * It does not open anything. Discovery is a multicast question and a unicast answer; no listener is
 * bound, nothing of ours is reachable, and the local server stays on loopback. That is deliberate:
 * this whole file can ship and be tested before anything is exposed.
 */
object CastDiscovery {

    private const val TAG = "CastDiscovery"
    private const val LISTEN_MS = 3_000L

    private val client: HttpClient by lazy {
        httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000L
                requestTimeoutMillis = 3_000L
                socketTimeoutMillis = 3_000L
            }
        }
    }

    /** One thing found on the network. */
    data class Device(
        /** Its address. This is what the server will be told to answer, and nothing else. */
        val address: String,
        val friendlyName: String,
        /** Absolute URL of the AVTransport service — where play/pause/seek are sent. */
        val controlUrl: String
    )

    /**
     * Searches the local network.
     *
     * Never throws and never blocks forever: a device list that fails is an empty list, because a
     * picker showing nothing is a normal outcome on a network with nothing on it.
     */
    suspend fun search(acquireLock: (() -> AutoCloseable?)? = null): List<Device> = withContext(Dispatchers.IO) {
        val lock = runCatching { acquireLock?.invoke() }.getOrNull()
        try {
            val locations = withTimeoutOrNull(LISTEN_MS + 2_000L) {
                SsdpSocket.probe(LISTEN_MS)
            } ?: emptySet()
            locations.mapNotNull { describe(it) }.distinctBy { it.address }
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed", e)
            emptyList()
        } finally {
            runCatching { lock?.close() }
        }
    }

    /**
     * Fetches a device's description and reads out what we need.
     *
     * The address comes from the description **URL**, not from anything inside the XML: a device
     * gets to say what it is called, and it does not get to say where it is. Believing a
     * self-declared address would hand the allow-list to whoever answered.
     */
    suspend fun describe(location: String): Device? = runCatching {
        val host = extractHost(location) ?: return null
        val xml = client.get(location).bodyAsText()

        val name = tagValue(xml, "friendlyName") ?: host
        val control = avTransportControlUrl(xml) ?: return null
        Device(
            address = host,
            friendlyName = name,
            controlUrl = absoluteUrl(location, control)
        )
    }.getOrElse {
        Log.d(TAG, "could not describe $location: ${it.message}")
        null
    }

    internal fun extractHost(urlStr: String): String? {
        val noScheme = urlStr.substringAfter("://", "")
        if (noScheme.isBlank()) return null
        val hostPort = noScheme.substringBefore('/')
        return hostPort.substringBefore(':').takeIf { it.isNotBlank() }
    }

    internal fun tagValue(xml: String, tag: String): String? {
        val open = xml.indexOf("<$tag>", ignoreCase = true)
        if (open < 0) return null
        val start = open + tag.length + 2
        val end = xml.indexOf("</$tag>", startIndex = start, ignoreCase = true)
        if (end < 0) return null
        return xml.substring(start, end).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The `controlURL` belonging to the AVTransport service.
     */
    internal fun avTransportControlUrl(xml: String): String? {
        var from = 0
        while (true) {
            val open = xml.indexOf("<service", from, ignoreCase = true)
            if (open < 0) return null
            val close = xml.indexOf("</service>", open, ignoreCase = true)
            if (close < 0) return null
            val block = xml.substring(open, close)
            if (block.contains("AVTransport", ignoreCase = true)) {
                return tagValue(block, "controlURL")
            }
            from = close + 1
        }
    }

    internal fun absoluteUrl(base: String, path: String): String {
        if (path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        ) {
            return path
        }
        val baseUrl = if (base.contains("://")) {
            val scheme = base.substringBefore("://")
            val rest = base.substringAfter("://")
            val hostPort = rest.substringBefore('/')
            "$scheme://$hostPort"
        } else base
        return if (path.startsWith("/")) "$baseUrl$path" else "$baseUrl/$path"
    }

    fun localAddressOn(interfacePrefixHint: String? = null): String? =
        SsdpSocket.localAddressOn(interfacePrefixHint)

    fun literalAddress(host: String): String? =
        SsdpSocket.literalAddress(host)

    fun sameNetwork(ours: String, theirs: String): Boolean {
        val a = ours.substringBeforeLast('.', "")
        val b = theirs.substringBeforeLast('.', "")
        return a.isNotEmpty() && a == b
    }
}
