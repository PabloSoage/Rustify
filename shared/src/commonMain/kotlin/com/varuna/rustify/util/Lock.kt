// shared/src/commonMain/kotlin/com/varuna/rustify/util/Lock.kt
package com.varuna.rustify.util

/**
 * A reentrant lock for the stores' paths that do not suspend: `synchronized` on Android and the
 * desktop, which is what each of them used before it moved to common code. Code that suspends while
 * holding one uses a coroutine `Mutex` instead.
 */
expect class Lock()

/** Runs [block] holding the lock: `synchronized(lock) { … }`. */
expect inline fun <T> Lock.locked(block: () -> T): T
