// shared/src/jvmCommonMain/kotlin/com/varuna/rustify/util/Lock.jvm.kt
package com.varuna.rustify.util

actual class Lock actual constructor()

actual inline fun <T> Lock.locked(block: () -> T): T = synchronized(this, block)
