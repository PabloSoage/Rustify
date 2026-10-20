package com.varuna.rustify.player

import com.varuna.rustify.player.SourceLadder.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceLadderTest {

    private fun inputs(
        isLocal: Boolean = false,
        hint: Boolean = false,
        localMatch: String? = null,
        alternative: Boolean = false,
        expired: Boolean = false,
        streamCache: String? = null,
        persisted: String? = null,
        preResolved: String? = null,
    ) = SourceLadder.Inputs(
        isLocalTrack = isLocal,
        hasHint = hint,
        localFile = { "content://local" },
        localMatch = { localMatch },
        hasUserAlternative = { alternative },
        urlExpired = { expired },
        streamCache = { streamCache },
        persisted = { persisted },
        preResolved = { preResolved },
    )

    @Test
    fun `a local track plays its own file`() {
        assertEquals(Kind.LOCAL_FILE, kotlinx.coroutines.runBlocking { SourceLadder.decide(inputs(isLocal = true, streamCache = "x")) }.kind)
    }

    @Test
    fun `a matching file on the device beats every cache`() {
        val d = kotlinx.coroutines.runBlocking { SourceLadder.decide(inputs(localMatch = "file", streamCache = "c", persisted = "p", preResolved = "r")) }
        assertEquals(Kind.LOCAL_MATCH, d.kind)
        assertEquals("file", d.url)
    }

    @Test
    fun `a hint skips every cache - they hold the recording being replaced`() {
        val d = kotlinx.coroutines.runBlocking { SourceLadder.decide(inputs(hint = true, streamCache = "c", persisted = "p", preResolved = "r")) }
        assertEquals(Kind.NETWORK, d.kind)
    }

    @Test
    fun `a user alternative throws the caches away`() {
        val d = kotlinx.coroutines.runBlocking { SourceLadder.decide(inputs(alternative = true, streamCache = "c", persisted = "p")) }
        assertEquals(Kind.NETWORK, d.kind)
        assertTrue(d.invalidate)
    }

    @Test
    fun `an expired url throws the caches away`() {
        val d = kotlinx.coroutines.runBlocking { SourceLadder.decide(inputs(expired = true, persisted = "p")) }
        assertEquals(Kind.NETWORK, d.kind)
        assertTrue(d.invalidate)
    }

    @Test
    fun `cheapest first - stream cache, persisted, pre-resolved, network`() {
        assertEquals(Kind.STREAM_CACHE, kotlinx.coroutines.runBlocking { SourceLadder.decide(inputs(streamCache = "c", persisted = "p", preResolved = "r")) }.kind)
        assertEquals(Kind.PERSISTED, kotlinx.coroutines.runBlocking { SourceLadder.decide(inputs(persisted = "p", preResolved = "r")) }.kind)
        assertEquals(Kind.PRE_RESOLVED, kotlinx.coroutines.runBlocking { SourceLadder.decide(inputs(preResolved = "r")) }.kind)
        assertEquals(Kind.NETWORK, kotlinx.coroutines.runBlocking { SourceLadder.decide(inputs()) }.kind)
        assertEquals(Kind.NETWORK, kotlinx.coroutines.runBlocking { SourceLadder.decide(inputs(persisted = "")) }.kind)
    }

    @Test
    fun `nothing below the answer is asked - some steps read the disk`() {
        val d = kotlinx.coroutines.runBlocking { SourceLadder.decide(
            SourceLadder.Inputs(
                isLocalTrack = false, hasHint = false,
                localFile = { fail("asked"); null },
                localMatch = { "file" },
                hasUserAlternative = { fail("asked"); false },
                urlExpired = { fail("asked"); false },
                streamCache = { fail("asked"); null },
                persisted = { fail("asked"); null },
                preResolved = { fail("asked"); null },
            )
        ) }
        assertEquals(Kind.LOCAL_MATCH, d.kind)
    }
}
