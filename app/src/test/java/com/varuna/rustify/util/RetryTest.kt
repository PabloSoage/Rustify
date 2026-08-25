package com.varuna.rustify.util

import com.varuna.rustify.bridge.SpotifyEngineException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * The Kotlin half of the error-kind contract, checked against exactly the same cases as
 * `core_engine/src/errors.rs`. If the two ever disagree, one of the two test files goes red — which
 * is the only reason it is safe to have the classification named in two places.
 *
 * The contract is four strings on the wire. What makes them worth pinning is what happens when they
 * stop matching: `classifyError` falls through to the message heuristic, every auth failure becomes
 * permanent, and the symptom is the heart button quietly not working an hour into the session — with
 * nothing thrown and nothing logged. That is the bug 3.6 shipped a fix for, and this is the test that
 * stops the fix being undone by a rename.
 */
class RetryTest {

    /** The four names `errors.rs` writes, and the four this side answers with. */
    private val theWireContract = mapOf(
        "auth" to ErrorKind.AUTH,
        "rateLimited" to ErrorKind.RATE_LIMITED,
        "transient" to ErrorKind.TRANSIENT,
        "permanent" to ErrorKind.PERMANENT
    )

    @Test
    fun `every kind the engine states is understood`() {
        for ((wire, expected) in theWireContract) {
            assertEquals(
                "the engine said \"$wire\"",
                expected,
                classifyError(SpotifyEngineException("anything at all", wire))
            )
        }
    }

    @Test
    fun `what the engine says beats what the message looks like`() {
        // The message says one thing and the engine says another. Before the kind travelled, the
        // message was the only evidence there was; now it is not evidence at all.
        assertEquals(
            ErrorKind.PERMANENT,
            classifyError(SpotifyEngineException("API error 401: not authenticated", "permanent"))
        )
        assertEquals(
            ErrorKind.AUTH,
            classifyError(SpotifyEngineException("something bland with no clues", "auth"))
        )
    }

    @Test
    fun `an unknown kind falls back rather than guessing wrong`() {
        // A name from a newer engine than this build. Falling through to the heuristic is right;
        // answering PERMANENT because the string did not match would silently stop retrying.
        assertEquals(
            ErrorKind.AUTH,
            classifyError(SpotifyEngineException("Access token expired", "somethingNewer"))
        )
    }

    @Test
    fun `the heuristic still covers what has no stated kind`() {
        // Refusals built by hand on the Rust side, and exceptions raised on this side of the
        // boundary, arrive with kind = null. These are the cases it used to cover on its own.
        assertEquals(ErrorKind.AUTH, classifyError(SpotifyEngineException("Access token expired")))
        assertEquals(ErrorKind.AUTH, classifyError(SpotifyEngineException("User not authenticated")))
        assertEquals(ErrorKind.AUTH, classifyError(SpotifyEngineException("API error 401: nope")))
        assertEquals(
            ErrorKind.RATE_LIMITED,
            classifyError(SpotifyEngineException("API error 429: slow down"))
        )
        assertEquals(
            ErrorKind.TRANSIENT,
            classifyError(SpotifyEngineException("API error 503: unavailable"))
        )
        assertEquals(ErrorKind.TRANSIENT, classifyError(IOException("connection reset")))
        assertEquals(ErrorKind.PERMANENT, classifyError(SpotifyEngineException("not found")))
    }

    @Test
    fun `a failure reported as a value is read without fabricating an exception`() {
        // What `restoreSession` does, and the answer decides whether the user's credentials get
        // deleted. The stated kind wins; the message is the fallback.
        assertEquals(ErrorKind.TRANSIENT, errorKindOf("transient", "anything"))
        assertEquals(ErrorKind.TRANSIENT, errorKindOf(null, "Network error: timed out"))
        assertEquals(ErrorKind.PERMANENT, errorKindOf(null, null))
    }
}
