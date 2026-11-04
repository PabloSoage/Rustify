// sharedUi/src/iosMain/kotlin/com/varuna/rustify/util/Lock.ios.kt
package com.varuna.rustify.util

import platform.Foundation.NSRecursiveLock

/** Foundation's recursive lock: reentrant, as `synchronized` is on the JVM. */
actual class Lock actual constructor() {
    @PublishedApi
    internal val lock = NSRecursiveLock()
}

actual inline fun <T> Lock.locked(block: () -> T): T {
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
