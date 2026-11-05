// shared/src/iosMain/kotlin/com/varuna/rustify/jam/IosJamCore.kt
package com.varuna.rustify.jam

/**
 * [JamCore] on iOS: [JvmJamCore], which is common code over `NativeEngine` and so reaches the core
 * through the C ABI (`jam*`) here. Kept under this name for the iOS call sites.
 */
object IosJamCore : JamCore by JvmJamCore
