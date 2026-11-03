// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/SpotifyEngineException.kt
package com.varuna.rustify.bridge

/**
 * A failure the Rust engine reported.
 *
 * [kind] is what the engine says the failure *was* — `auth`, `rateLimited`, `transient`,
 * `permanent` — and it is what `classifyError` reads. Null means the engine did not say, which
 * happens for the handful of refusals built by hand on that side; the message heuristic covers those.
 *
 * Moved out of `SpotifyRepository.kt` (which is Android) so `util/Retry.kt` could be shared.
 */
class SpotifyEngineException(
    message: String,
    val kind: String? = null
) : Exception(message)
