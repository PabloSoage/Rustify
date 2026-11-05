// shared/src/iosMain/kotlin/com/varuna/rustify/spotify/IosSpotifyCore.kt
package com.varuna.rustify.spotify

/**
 * [SpotifyCore] on iOS: [JvmSpotifyCore], which is common code over `NativeEngine` and so reaches
 * the core through the C ABI here. Kept under this name for the iOS call sites.
 */
object IosSpotifyCore : SpotifyCore by JvmSpotifyCore
