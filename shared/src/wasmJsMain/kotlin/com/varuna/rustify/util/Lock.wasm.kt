// shared/src/wasmJsMain/kotlin/com/varuna/rustify/util/Lock.wasm.kt
package com.varuna.rustify.util

/** The browser has one thread: there is nobody to wait for. */
actual class Lock actual constructor()

actual inline fun <T> Lock.locked(block: () -> T): T = block()
