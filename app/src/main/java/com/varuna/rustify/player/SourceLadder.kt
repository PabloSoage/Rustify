package com.varuna.rustify.player

/**
 * Where a track's audio comes from, decided in one place (4.7, debt C10).
 *
 * This order used to live inline in `AudioPlayerService.playTrack`, three hundred lines long, with
 * the rules that make it right spread through its comments. They are written down here, once, with
 * a test each (`SourceLadderTest`):
 *
 * 1. A `local:` track plays its own file.
 * 2. A file already on the device that matches the song beats every cache and every backend.
 * 3. A hint — "play this specific video" — skips every cache: the caches are keyed by the *song*,
 *    so anything in them is the recording being replaced. Only the network can honour a hint.
 * 4. Without a hint: a user-chosen alternative, or an expired URL, throws the caches away
 *    ([Decision.invalidate]) and goes to the network.
 * 5. Otherwise the cheapest first: the stream cache (bytes on disk), the persisted URL, the URL
 *    resolved ahead of time by the prefetch, and only then the network.
 *
 * Each step is a lambda so that nothing below the answer is asked: some of them read the disk.
 */
internal object SourceLadder {

    enum class Kind { LOCAL_FILE, LOCAL_MATCH, STREAM_CACHE, PERSISTED, PRE_RESOLVED, NETWORK }

    /**
     * [url] is null only for [Kind.NETWORK]. [invalidate] asks the caller to drop every cached copy
     * of the track before resolving (rule 4).
     */
    data class Decision(val kind: Kind, val url: String? = null, val invalidate: Boolean = false)

    class Inputs(
        val isLocalTrack: Boolean,
        val hasHint: Boolean,
        val localFile: suspend () -> String?,
        val localMatch: suspend () -> String?,
        val hasUserAlternative: suspend () -> Boolean,
        val urlExpired: suspend () -> Boolean,
        val streamCache: suspend () -> String?,
        val persisted: suspend () -> String?,
        val preResolved: suspend () -> String?,
    )

    suspend fun decide(i: Inputs): Decision {
        if (i.isLocalTrack) return Decision(Kind.LOCAL_FILE, i.localFile())
        i.localMatch()?.let { return Decision(Kind.LOCAL_MATCH, it) }
        if (i.hasHint) return Decision(Kind.NETWORK)
        if (i.hasUserAlternative() || i.urlExpired()) return Decision(Kind.NETWORK, invalidate = true)
        i.streamCache()?.takeIf { it.isNotBlank() }?.let { return Decision(Kind.STREAM_CACHE, it) }
        i.persisted()?.takeIf { it.isNotBlank() }?.let { return Decision(Kind.PERSISTED, it) }
        i.preResolved()?.takeIf { it.isNotBlank() }?.let { return Decision(Kind.PRE_RESOLVED, it) }
        return Decision(Kind.NETWORK)
    }
}
