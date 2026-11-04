package com.varuna.rustify.metrics

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.ui.AppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * ListenBrainz and Last.fm, for whichever player the platform has: told when a song starts and, when
 * it ends, how much of it was heard. Android's [ListeningTracker] and the desktop's queue player both
 * feed it, so the two scrobble by the same rule. Neither service is asked without its account.
 */
class Scrobbler(private val platform: AppPlatform, private val scope: CoroutineScope) {
    /** Parent of the submits still on their way; a child of [scope]'s job, so it ends with it. */
    private val submits = SupervisorJob(scope.coroutineContext[Job])

    fun started(track: FullTrack, startedAt: Long) {
        if (ListenBrainz.token(platform) != null) {
            scope.launch { ListenBrainz.playingNow(platform, ListenBrainz.listenOf(track, startedAt)) }
        }
        if (LastFm.credentials(platform) != null) {
            scope.launch { LastFm.nowPlaying(platform, ListenBrainz.listenOf(track, startedAt)) }
        }
    }

    fun finished(track: FullTrack, startedAt: Long, listenedMs: Long, errored: Boolean) {
        // ListenBrainz and Last.fm share their own, stricter rule (4 min or half); the core queues
        // each if offline.
        if (errored || !ListenBrainz.qualifies(listenedMs, track.durationMs.toLong())) return
        val listen = ListenBrainz.listenOf(track, startedAt)
        if (ListenBrainz.token(platform) != null) {
            scope.launch(submits) { runCatching { ListenBrainz.submit(platform, listen) } }
        }
        if (LastFm.credentials(platform) != null) {
            scope.launch(submits) { runCatching { LastFm.submit(platform, listen) } }
        }
    }

    /**
     * Returns once every submit [finished] launched has ended. For a process about to exit: a submit
     * that was only launched dies with it, and the last song goes unscrobbled.
     */
    suspend fun awaitSubmits() {
        submits.children.toList().joinAll()
    }
}
