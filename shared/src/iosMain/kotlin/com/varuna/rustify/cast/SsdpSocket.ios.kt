// shared/src/iosMain/kotlin/com/varuna/rustify/cast/SsdpSocket.ios.kt
package com.varuna.rustify.cast

internal actual object SsdpSocket {
    actual suspend fun probe(listenMs: Long): Set<String> = emptySet()
    actual fun localAddressOn(interfacePrefixHint: String?): String? = null
    actual fun literalAddress(host: String): String? = host
}
