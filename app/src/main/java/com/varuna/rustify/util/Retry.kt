package com.varuna.rustify.util

import com.varuna.rustify.bridge.SpotifyEngineException
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Kind of error produced by the engine / network layer, used to decide whether to retry.
 */
enum class ErrorKind { TRANSIENT, PERMANENT, AUTH, RATE_LIMITED }

/**
 * The four names the engine uses on the wire, mapped to the four here.
 *
 * They are matched by name and not by ordinal on purpose: an enum's order is an implementation
 * detail on both sides, and this is a contract across a JNI boundary. The strings are pinned by
 * `errors.rs`'s own test, so a rename over there fails a test rather than silently classifying every
 * auth failure as permanent — which is the failure where the heart button stops working and nothing
 * says so.
 */
private fun kindFromEngine(name: String?): ErrorKind? = when (name) {
    "auth" -> ErrorKind.AUTH
    "rateLimited" -> ErrorKind.RATE_LIMITED
    "transient" -> ErrorKind.TRANSIENT
    "permanent" -> ErrorKind.PERMANENT
    else -> null
}

/**
 * Classify an exception thrown by the engine or the network layer.
 *
 * **The engine says which kind it is, and that answer wins.** `SpotifyError` has held the shape of
 * the failure all along — `TokenExpired` is a variant, `ApiError(401, …)` carries the status as a
 * number — and every bridge now sends it across as a `kind` field. See `core_engine/src/errors.rs`.
 *
 * What follows is the fallback, kept for the two cases that have no stated kind: a refusal built by
 * hand on the Rust side, and an exception raised on this side of the boundary (an `IOException` from
 * the network layer, a timeout). It is the heuristic that used to do the whole job:
 *
 * - AUTH: 401 / token expired — recoverable via session refresh.
 * - TRANSIENT: network IO, 5xx, timeouts — recoverable via backoff.
 * - RATE_LIMITED: 429. Transient in nature, but already retried where it belongs (see [retrying]).
 * - PERMANENT: everything else (e.g. "not found in YouTube Music") — do not insist.
 *
 * Conservative by construction: unknown ⇒ PERMANENT, so nothing retries forever.
 */
fun classifyError(t: Throwable): ErrorKind {
    (t as? SpotifyEngineException)?.let { engine ->
        kindFromEngine(engine.kind)?.let { return it }
    }
    val msg = (t.message ?: "").lowercase()
    return when {
        t is SpotifyEngineException && (msg.contains("401") || msg.contains("expired") || msg.contains("not authenticated")) -> ErrorKind.AUTH
        t is SpotifyEngineException && msg.contains("429") -> ErrorKind.RATE_LIMITED
        t is SpotifyEngineException && (Regex("""\b5\d\d\b""").containsMatchIn(msg) ||
            msg.contains("network") || msg.contains("timed out") || msg.contains("timeout")) -> ErrorKind.TRANSIENT
        t is java.io.IOException -> ErrorKind.TRANSIENT
        else -> ErrorKind.PERMANENT
    }
}

/**
 * The kind of a failure the engine reported **as a value** rather than by throwing.
 *
 * The write bridges answer `{"success":false,…}` instead of raising, so there is no exception to
 * classify at the point the decision is made. Rather than fabricate one just to ask — which is what
 * `restoreSession` used to do, and it decides whether to wipe the user's credentials — this takes the
 * two fields directly.
 */
fun errorKindOf(kind: String?, message: String?): ErrorKind =
    kindFromEngine(kind) ?: classifyError(SpotifyEngineException(message ?: ""))

/**
 * Run [block] with automatic retries, exponential backoff + jitter.
 *
 * - [maxAttempts]: total attempts (including the first one).
 * - [baseDelayMs] / [maxDelayMs]: exponential backoff capped at [maxDelayMs].
 * - [onAuthError]: invoked once on an AUTH error; if it returns true the session was
 *   recovered and the call is retried; if false the error is rethrown.
 *
 * PERMANENT errors are rethrown immediately. TRANSIENT errors exhaust [maxAttempts].
 *
 * RATE_LIMITED is rethrown immediately too, and that is deliberate. The Rust client already retries
 * a 429 three times, honouring `Retry-After`; retrying here as well made the two layers multiply
 * into **nine** requests for a single tap, each one telling Spotify to throttle harder. Whoever is
 * closest to the wire owns rate-limit backoff, and that is the layer holding the header.
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
                ErrorKind.PERMANENT, ErrorKind.RATE_LIMITED -> throw t
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
