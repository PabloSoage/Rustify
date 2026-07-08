package com.varuna.rustify.util

import com.varuna.rustify.bridge.SpotifyEngineException
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Kind of error produced by the engine / network layer, used to decide whether to retry.
 */
enum class ErrorKind { TRANSIENT, PERMANENT, AUTH }

/**
 * Classify an exception thrown by the engine or the network layer.
 *
 * Heuristics (conservative: unknown ⇒ PERMANENT so we never retry forever):
 * - AUTH: 401 / token expired — recoverable via session refresh.
 * - TRANSIENT: network IO, 429 (rate limit), 5xx, timeouts — recoverable via backoff.
 * - PERMANENT: everything else (e.g. "not found in YouTube Music") — do not insist.
 */
fun classifyError(t: Throwable): ErrorKind {
    val msg = (t.message ?: "").lowercase()
    return when {
        t is SpotifyEngineException && (msg.contains("401") || msg.contains("expired") || msg.contains("not authenticated")) -> ErrorKind.AUTH
        t is SpotifyEngineException && (msg.contains("429") || Regex("""\b5\d\d\b""").containsMatchIn(msg) ||
            msg.contains("network") || msg.contains("timed out") || msg.contains("timeout")) -> ErrorKind.TRANSIENT
        t is java.io.IOException -> ErrorKind.TRANSIENT
        else -> ErrorKind.PERMANENT
    }
}

/**
 * Run [block] with automatic retries, exponential backoff + jitter.
 *
 * - [maxAttempts]: total attempts (including the first one).
 * - [baseDelayMs] / [maxDelayMs]: exponential backoff capped at [maxDelayMs].
 * - [onAuthError]: invoked once on an AUTH error; if it returns true the session was
 *   recovered and the call is retried; if false the error is rethrown.
 *
 * PERMANENT errors are rethrown immediately. TRANSIENT errors exhaust [maxAttempts].
 */
suspend fun <T> retrying(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 500,
    maxDelayMs: Long = 8_000,
    onAuthError: (suspend () -> Boolean)? = null,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (t: Throwable) {
            attempt++
            when (classifyError(t)) {
                ErrorKind.PERMANENT -> throw t
                ErrorKind.AUTH -> {
                    val recovered = onAuthError?.invoke() ?: false
                    if (!recovered || attempt >= maxAttempts) throw t
                }
                ErrorKind.TRANSIENT -> if (attempt >= maxAttempts) throw t
            }
            val exp = min(baseDelayMs shl (attempt - 1), maxDelayMs)
            val jitter = (0..(exp / 2).toInt()).random().toLong()
            delay(exp + jitter)
        }
    }
}