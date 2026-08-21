package com.varuna.rustify.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL

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

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    /** The UPnP device type for "something that renders media". Speakers, TVs, receivers. */
    private const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** How long to listen for answers. SSDP replies trickle in; devices stagger them on purpose. */
    private const val LISTEN_MS = 3_000L

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
    suspend fun search(context: Context): List<Device> = withContext(Dispatchers.IO) {
        // A multicast lock is required on Android for the socket to see multicast traffic at all.
        // Without it, this silently finds nothing — which looks exactly like "no devices".
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("rustify-ssdp")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }
        try {
            val locations = withTimeoutOrNull(LISTEN_MS + 2_000L) { probe() } ?: emptySet()
            locations.mapNotNull { describe(it) }.distinctBy { it.address }
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed", e)
            emptyList()
        } finally {
            runCatching { lock?.release() }
        }
    }

    /** Sends the M-SEARCH and collects the `LOCATION` of everything that answers. */
    private suspend fun probe(): Set<String> = withContext(Dispatchers.IO) {
        val found = mutableSetOf<String>()
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            // Seconds a device may wait before answering, so a busy network does not reply all at
            // once. It also sets the floor for how long we have to listen.
            append("MX: 2\r\n")
            append("ST: $MEDIA_RENDERER\r\n")
            append("\r\n")
        }.toByteArray()

        DatagramSocket().use { socket ->
            socket.reuseAddress = true
            socket.soTimeout = 500
            val target = InetAddress.getByName(SSDP_HOST)
            // Sent more than once because UDP multicast is allowed to lose packets, and losing the
            // question means finding nothing rather than finding less.
            repeat(3) {
                runCatching {
                    socket.send(DatagramPacket(request, request.size, target, SSDP_PORT))
                }
            }

            val deadline = System.currentTimeMillis() + LISTEN_MS
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
                if (!ok) continue
                val text = String(packet.data, 0, packet.length)
                locationOf(text)?.let { found.add(it) }
            }
        }
        found
    }

    /** The `LOCATION:` header of an SSDP reply, case-insensitively. */
    private fun locationOf(reply: String): String? =
        reply.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.startsWith("http://", ignoreCase = true) }

    /**
     * Fetches a device's description and reads out what we need.
     *
     * The address comes from the description **URL**, not from anything inside the XML: a device
     * gets to say what it is called, and it does not get to say where it is. Believing a
     * self-declared address would hand the allow-list to whoever answered.
     */
    private fun describe(location: String): Device? = runCatching {
        val url = URL(location)
        val host = url.host ?: return null
        val xml = url.openConnection().apply {
            connectTimeout = 3_000
            readTimeout = 3_000
        }.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }

        val name = tagValue(xml, "friendlyName") ?: host
        val control = avTransportControlUrl(xml) ?: return null
        Device(
            address = host,
            friendlyName = name,
            controlUrl = absolute(url, control)
        )
    }.getOrElse {
        Log.d(TAG, "could not describe $location: ${it.message}")
        null
    }

    private fun tagValue(xml: String, tag: String): String? {
        val open = xml.indexOf("<$tag>", ignoreCase = true)
        if (open < 0) return null
        val start = open + tag.length + 2
        val end = xml.indexOf("</$tag>", startIndex = start, ignoreCase = true)
        if (end < 0) return null
        return xml.substring(start, end).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The `controlURL` belonging to the AVTransport service.
     *
     * A description lists several services and each has its own `controlURL`; taking the first one
     * in the document gives you RenderingControl about half the time, which then answers every
     * play request with a polite error.
     */
    private fun avTransportControlUrl(xml: String): String? {
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

    private fun absolute(base: URL, path: String): String =
        if (path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        ) {
            path
        } else {
            URL(base, path).toString()
        }

    /**
     * This phone's own address on the wifi — what the cast listener binds to.
     *
     * Returned as a specific address on purpose. The one thing this must never produce is
     * `0.0.0.0`: that is the mistake E11 was about, and the core refuses it, but the refusal should
     * never be the thing that catches it.
     *
     * Loopback, link-local and IPv6 are skipped: a cast device reaches us over the LAN on IPv4, and
     * offering it anything else is offering it an address it cannot use.
     */
    fun localAddressOn(interfacePrefixHint: String? = null): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.address.size == 4 }
            .mapNotNull { it.hostAddress }
            .firstOrNull { address ->
                interfacePrefixHint == null || address.startsWith(interfacePrefixHint)
            }
    }.getOrNull()

    /**
     * The address a device is on, as an [InetSocketAddress] would resolve it — used only to sanity
     * check that a device really is on the same network we are about to open onto.
     */
    fun sameNetwork(ours: String, theirs: String): Boolean {
        // A /24 comparison. Crude, and deliberately so: this is a guard against opening onto one
        // interface for a device reachable on another, not a substitute for the core's peer check.
        val a = ours.substringBeforeLast('.', "")
        val b = theirs.substringBeforeLast('.', "")
        return a.isNotEmpty() && a == b
    }

    /** Resolves a hostname to a literal address, for a description URL that used a name. */
    fun literalAddress(host: String): String? = runCatching {
        if (host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }) host
        else InetSocketAddress(host, 80).address?.hostAddress
    }.getOrNull()
}
