// shared/src/commonMain/kotlin/com/varuna/rustify/cast/SsdpSocket.kt
package com.varuna.rustify.cast

internal expect object SsdpSocket {
    suspend fun probe(listenMs: Long = 3_000L): Set<String>
    fun localAddressOn(interfacePrefixHint: String? = null): String?
    fun literalAddress(host: String): String?
}
