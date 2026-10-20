package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Chained fallback engine. Walks the providers by priority (already ordered by
 * [AudioBackendSettings]) with [perProviderTimeoutMs], skipping to the next one on
 * failure/timeout, and caches which provider worked per `trackId` so it is tried
 * first next time.
 *
 * There are two independent orders (stream vs download): two distinct chains are
 * instantiated from [AudioSourceRegistry].
 */
class AudioSourceChain(
    private val providers: List<AudioSourceProvider>,
    private val perProviderTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** How long the provider in front gets before the next one starts alongside it. */
    private val hedgeAfterMs: Long = DEFAULT_HEDGE_MS,
    /** trackId -> provider.id that served the stream last time. Shared between chains so the
     *  cache persists across calls (the registry keeps it). */
    private val lastGood: ConcurrentHashMap<String, String> = ConcurrentHashMap()
) {

    /**
     * Resolves a playable URL; returns (providerId, StreamInfo) or a failure with all the errors.
     *
     * ## When there is a [hint], the chain is a different chain
     *
     * A hint is a YouTube video id: the alternative the user picked, or previewed. Only a provider
     * that fetches from YouTube can honour one — see
     * [AudioSourceCapabilities.honoursYoutubeHint] — so the others are not asked at all, and
     * [lastGood] is ignored.
     *
     * Both halves matter, and each on its own leaves the hole open:
     *
     *  - **Filtering**, because Deezer and an add-on resolve from the track's own metadata and
     *    ignore the hint entirely. Asked, they answer *successfully* with the recording the user is
     *    trying to replace, and a successful answer ends the chain — so with either of them first,
     *    every alternative you pick plays the same thing.
     *  - **Ignoring [lastGood]**, because it remembers who served this track *without* a hint. It is
     *    the right answer to "play this track" and the wrong answer to "play this video", and it
     *    would put the provider that cannot honour the hint back at the front.
     *
     * With nothing left to ask, this fails rather than falling back: playing the wrong recording is
     * worse than not playing, because it is the failure that looks like success.
     *
     * Not to be confused with the 3.7.2 preview bug, which had the same symptom one layer up and a
     * different cause — the chain resolved the right video and the track-keyed stream cache then
     * overrode it. See `AudioPlayerService.playTrack`. This is the same mistake made about
     * providers instead of about caches: a hint is about a recording, and everything keyed by the
     * track is not.
     */
    suspend fun resolveStreamUrl(track: FullTrack, hint: String? = null): Result<Pair<String, StreamInfo>> {
        val trackId = track.id ?: return Result.failure(IllegalStateException("track has no id"))
        val hinted = !hint.isNullOrBlank()
        val ordered = if (hinted) {
            providers.filter { it.capabilities.honoursYoutubeHint }
        } else {
            reorderPreferred(providers, lastGood[trackId])
        }
        if (hinted && ordered.isEmpty()) {
            return Result.failure(
                AudioSourceChainException(
                    listOf(IllegalStateException("no enabled backend can play a chosen YouTube alternative"))
                )
            )
        }
        val streamers = ordered.filter { it.capabilities.canStream }
        val errors = mutableListOf<Throwable>()
        if (streamers.isEmpty()) return Result.failure(AudioSourceChainException(errors))

        // ## Hedged, not sequential (4.2)
        //
        // One provider at a time meant that a provider which hung spent its whole budget before the
        // next one started: up to 40 s of silence for yt-dlp alone. Now the next provider starts as
        // soon as the previous one *fails*, or after [hedgeAfterMs] if it has not answered — without
        // cancelling it — and the first success wins. The user's order still decides who starts
        // first; what changes is that a slow provider no longer blocks a fast one behind it.
        // Providers run in a scope of their own, which is cancelled but **not awaited** on the way out.
        // A losing yt-dlp is a blocking process that ignores cancellation until it returns: under
        // `coroutineScope` the winner waited for it (measured: 20.9 s for an answer ready at 3 s).
        val racing = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            val results = Channel<Pair<AudioSourceProvider, Result<StreamInfo?>>>(Channel.UNLIMITED)
            var next = 0
            var running = 0

            fun launchNext() {
                val p = streamers[next++]
                running++
                racing.launch {
                    // isAvailableFor() must run INSIDE the timeout. It can do network I/O (e.g.
                    // Invidious fetching its instance directory); if it ran outside, a stalled
                    // directory fetch would hang the whole resolution with no upper bound. A null
                    // result means "not available for this track" -> skip quietly (no error).
                    val budget = p.capabilities.resolveTimeoutMs ?: perProviderTimeoutMs
                    val startedAt = System.currentTimeMillis()
                    val r = runCatching {
                        withTimeout(budget) {
                            if (!p.isAvailableFor(track)) null
                            else p.resolveStreamUrl(track, hint).getOrThrow()
                        }
                    }
                    // Logged whatever happens, because "it timed out" on its own says nothing about
                    // whether the ceiling was too low or the provider was hung.
                    android.util.Log.d(
                        TAG,
                        "${p.capabilities.id}: ${System.currentTimeMillis() - startedAt} ms of $budget " +
                            (if (r.isSuccess) "ok" else "-> ${r.exceptionOrNull()?.message}")
                    )
                    results.send(p to r)
                }
            }

            launchNext()
            var winner: Pair<String, StreamInfo>? = null
            while (winner == null && (running > 0 || next < streamers.size)) {
                val got = if (running > 0 && next < streamers.size) {
                    withTimeoutOrNull(hedgeAfterMs) { results.receive() }
                } else if (running > 0) {
                    results.receive()
                } else {
                    null
                }
                if (got == null) {
                    // Nobody answered in time (or nothing is running): bring in the next one.
                    launchNext()
                    continue
                }
                running--
                val (p, r) = got
                val info = r.getOrNull()
                when {
                    r.isSuccess && info != null -> {
                        lastGood[trackId] = p.capabilities.id
                        winner = p.capabilities.id to info
                    }
                    r.isSuccess -> Unit // unavailable for this track: skip without an error
                    else -> r.onFailure { e ->
                        // Tagged with the provider id so the aggregated message names who failed.
                        errors += labelError(p.capabilities.id, e)
                        if (lastGood[trackId] == p.capabilities.id) lastGood.remove(trackId)
                    }
                }
                // A provider that is done (failed or unavailable) frees its turn immediately.
                if (winner == null && running == 0 && next < streamers.size) launchNext()
            }
            winner?.let { Result.success(it) } ?: Result.failure(AudioSourceChainException(errors))
        } finally {
            racing.cancel()
        }
    }

    /** Downloads to [dst]; same fallback pattern, filtering by capabilities.canDownload. */
    suspend fun downloadTo(
        track: FullTrack,
        dst: File,
        onProgress: (Int) -> Unit = {}
    ): Result<Pair<String, File>> {
        val trackId = track.id ?: return Result.failure(IllegalStateException("track has no id"))
        val ordered = reorderPreferred(providers, lastGood[trackId])
        val errors = mutableListOf<Throwable>()
        for (p in ordered) {
            if (!p.capabilities.canDownload) continue
            try {
                if (!p.isAvailableFor(track)) continue
            } catch (e: Exception) { errors += labelError(p.capabilities.id, e); continue }
            // A full download (yt-dlp -x mp3 320K) routinely exceeds the resolve timeout; use a much
            // larger ceiling so real downloads aren't cancelled mid-transfer.
            val r = runCatching {
                withTimeout(DOWNLOAD_TIMEOUT_MS) { p.downloadTo(track, dst, onProgress).getOrThrow() }
            }
            if (r.isSuccess) {
                lastGood[trackId] = p.capabilities.id
                return Result.success(p.capabilities.id to r.getOrNull()!!)
            }
            r.onFailure { e ->
                errors += labelError(p.capabilities.id, e)
                if (lastGood[trackId] == p.capabilities.id) lastGood.remove(trackId)
            }
        }
        return Result.failure(AudioSourceChainException(errors))
    }

    /**
     * Wraps a provider failure with its id so [AudioSourceChainException]'s aggregated message reads
     * e.g. "ytdlp: resolver returned empty YouTube id" instead of an anonymous cause. A timeout is
     * reported explicitly (it arrives here as a [kotlinx.coroutines.TimeoutCancellationException]).
     */
    private fun labelError(providerId: String, e: Throwable): Throwable {
        val reason = when (e) {
            is kotlinx.coroutines.TimeoutCancellationException -> "timed out"
            else -> e.message ?: e::class.simpleName.orEmpty()
        }
        return Exception("$providerId: $reason", e)
    }

    private fun reorderPreferred(
        all: List<AudioSourceProvider>,
        preferredId: String?
    ): List<AudioSourceProvider> {
        if (preferredId.isNullOrBlank()) return all
        val pref = all.firstOrNull { it.capabilities.id == preferredId } ?: return all
        return listOf(pref) + all.filter { it.capabilities.id != preferredId }
    }

    companion object {
        private const val TAG = "AudioSourceChain"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        /** Long enough for InnerTube to find the video and answer (2–3 s when unmapped). */
        const val DEFAULT_HEDGE_MS = 4_000L
        /** Downloads are full file transfers (can take minutes); resolve-sized timeouts would abort them. */
        const val DOWNLOAD_TIMEOUT_MS = 20 * 60_000L
    }
}