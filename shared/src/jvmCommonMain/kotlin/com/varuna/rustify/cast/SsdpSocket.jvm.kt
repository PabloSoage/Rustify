// shared/src/jvmCommonMain/kotlin/com/varuna/rustify/cast/SsdpSocket.jvm.kt
package com.varuna.rustify.cast

import com.varuna.rustify.platform.IO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

internal actual object SsdpSocket {

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    actual suspend fun probe(listenMs: Long): Set<String> = withContext(Dispatchers.IO) {
        val found = mutableSetOf<String>()
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: $MEDIA_RENDERER\r\n")
            append("\r\n")
        }.toByteArray()

        runCatching {
            DatagramSocket().use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = 500
                val target = InetAddress.getByName(SSDP_HOST)
                repeat(3) {
                    runCatching {
                        socket.send(DatagramPacket(request, request.size, target, SSDP_PORT))
                    }
                }

                val deadline = System.currentTimeMillis() + listenMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!ok) continue
                    val text = String(packet.data, 0, packet.length)
                    locationOf(text)?.let { found.add(it) }
                }
            }
        }
        found
    }

    private fun locationOf(reply: String): String? =
        reply.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.startsWith("http://", ignoreCase = true) }

    actual fun localAddressOn(interfacePrefixHint: String?): String? = runCatching {
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

    actual fun literalAddress(host: String): String? = runCatching {
        if (host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }) host
        else InetSocketAddress(host, 80).address?.hostAddress
    }.getOrNull()
}
