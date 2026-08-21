@file:Suppress("SpellCheckingInspection")
@file:SuppressLint("StaticFieldLeak", "UseKtx")

package com.varuna.rustify.player

import com.varuna.rustify.bridge.TrackRef
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LocalStreamServer
import com.varuna.rustify.bridge.LyricsRepository
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.largest
import com.varuna.rustify.metrics.ListeningTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a queue came from, so "continue listening" can offer it back.
 *
 * Supplied by whoever started playback, because only they know: a list of tracks does not say
 * whether it is an album, a playlist, or three songs someone queued by hand. Absent means "not
 * something to come back to", which is the right answer for most queues.
 *
 * @param id stable per context — `"album:ID"`, `"playlist:ID"`, `"radio:ID"`. Coming back to the
 *   same one updates the entry rather than adding another.
 */
data class PlaybackContext(
    val id: String,
    val label: String,
    val subtitle: String = "",
    val imageUrl: String = ""
)

data class AudioPlayerState(
    val currentTrack: FullTrack? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,  // true while resolving OR while ExoPlayer is buffering
    val isError: Boolean = false,       // true when stream could not be resolved/played
    val errorMessage: String = "",
    val queue: List<FullTrack> = emptyList(),
    val originalQueue: List<FullTrack> = emptyList(),
    val isShuffle: Boolean = false,
    val isRepeat: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferPercent: Int = 0,
    val isVideoMode: Boolean = false,
    val videoSizeRatio: Float? = null,
    // true when the current track resolved to a local file (local match or a "local:" track).
    // The UI uses it to turn the alternatives button green and indicate the current match.
    val isLocalSource: Boolean = false
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @SuppressLint("StaticFieldLeak")
class AudioPlayerService private constructor(private val context: Context) {
    private val _state = MutableStateFlow(AudioPlayerState())
    val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    private val listenerTracker = ListeningTracker(context.applicationContext)

    // Preloaded lyrics for the current track
    private val _preloadedLyrics = MutableStateFlow<com.varuna.rustify.bridge.LyricsResult?>(null)
    val preloadedLyrics: StateFlow<com.varuna.rustify.bridge.LyricsResult?> = _preloadedLyrics.asStateFlow()
    @Volatile
    var preloadedLyricsTrackId: String? = null
        private set

    companion object {
        // "Past ten seconds, previous restarts the track" used to live here. It is now
        // `PREVIOUS_RESTART_THRESHOLD_MS` in `core_engine/src/player/mod.rs`, with the rest of the
        // queue rules, and deleting the copy rather than leaving it is the point: two constants
        // agreeing today is what F was about.

        /**
         * How a URL served by our own loopback server is recognised.
         *
         * Three decisions hang off it — which `DataSource` to use, whether the URL may be persisted
         * across sessions, and whether a failure means the cached copy is bad — so it is one
         * constant rather than three string literals that could drift apart.
         */
        private const val LOOPBACK_PREFIX = "http://127.0.0.1:"

        @Volatile
        private var downloadCache: SimpleCache? = null

        @Volatile
        var exoPlayerInstance: ExoPlayer? = null

        @Volatile
        var instance: AudioPlayerService? = null

        val resolvedStreamUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun getInstance(context: Context): AudioPlayerService {
            return instance ?: synchronized(this) {
                instance ?: AudioPlayerService(context.applicationContext).also { instance = it }
            }
        }

        fun getCache(context: Context): SimpleCache {
            return downloadCache ?: synchronized(this) {
                downloadCache ?: run {
                    val cacheDir = java.io.File(context.cacheDir, "audio_cache")
                    // Maximum size configurable in Settings (pref cache_max_mb, default 500 MB).
                    val maxMb = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
                        .getInt("cache_max_mb", 500).coerceIn(100, 8192)
                    val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(maxMb.toLong() * 1024 * 1024)
                    val databaseProvider = StandaloneDatabaseProvider(context)
                    SimpleCache(cacheDir, evictor, databaseProvider).also { downloadCache = it }
                }
            }
        }

    }

    private val preResolvedUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val persistedUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Per-track absolute expiry (epoch ms) for cached stream URLs, fed by StreamInfo.expiresAtMs.
    // A googlevideo URL past this instant is dropped before it reaches ExoPlayer (avoids a guaranteed 403).
    private val urlExpiryCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Last human-readable reason the audio chain failed to resolve, shown to the user on give-up.
    @Volatile private var lastResolveError: String? = null
    private var preBufferingJob: kotlinx.coroutines.Job? = null

    /** The in-flight autoplay-radio fetch, so two of them cannot append the same tracks twice. */
    private var radioJob: kotlinx.coroutines.Job? = null

    /**
     * The seed the radio was last fetched for.
     *
     * `preBufferNextTrack` runs on every track change and on retries, so without this the prefetch
     * would fire again each time the last track re-entered it — a round trip per pass for a queue
     * that already has its radio.
     */
    private var radioSeedInFlight: String? = null
    private var playJob: kotlinx.coroutines.Job? = null
    @Volatile private var isResolving = false
    // Id of the track whose media is actually loaded into ExoPlayer (set right after prepare()).
    // Between a track switch and the new media being prepared, the player still holds the PREVIOUS
    // track's timeline, so exoPlayer.currentPosition belongs to that old track. Reading it as if it
    // were the current track's position made a failed resolution resume the NEW track at the OLD
    // track's offset (a fresh song starting mid-way). Guard every live-position read with this.
    @Volatile private var preparedTrackId: String? = null
    @Volatile private var isRetrying = false
    // Generation token so a stale playJob's finally won't clobber isResolving.
    private val resolveGen = AtomicLong(0)
    private val userQueue = mutableListOf<FullTrack>()

    /**
     * Index of the current track inside `_state.queue`.
     *
     * The queue can legitimately hold the same track id more than once: a playlist that repeats a
     * song, or a manually queued track that also appears in the list being played. Locating the
     * current track by id alone therefore resolved to the FIRST occurrence, so advancing from a later
     * duplicate jumped *backwards* — playback looped over the same block, never reached the queued
     * tracks (which then piled up in [userQueue] and got re-injected into the next list), and
     * re-resolved a stream on every bounce, which is what made track changes crawl.
     *
     * Kept here and validated before use, with the id lookup as fallback.
     */
    @Volatile private var currentQueueIndex: Int = -1

    /**
     * Position of the current track inside [st]'s queue, for UI that needs to slice the queue (e.g.
     * "Next Up"). Derived from the passed state so Compose recomposes with it.
     */
    fun currentQueuePosition(st: AudioPlayerState): Int = currentIndexIn(st)

    // -------------------------------------------------------------------
    // Web player mode (experimental) — audio comes from the Spotify web page in a WebView while
    // Rustify supplies the UI. Nothing is captured or re-streamed; the transport commands below are
    // forwarded into the page and the page's own state is mirrored back into _state so the
    // miniplayer, track screen and notification controls keep working.
    //
    // It is a *preferred* engine rather than a second mode: every track is offered to the page
    // first, and anything it cannot serve falls back to the provider chain. That is why there are
    // two flags — the preference (webPlayerMode) and what is actually playing (webServingCurrentTrack).
    // Queue navigation stays with Rustify: opening a track URL leaves Spotify's own context holding
    // that single song, so the page has nothing to advance to.
    // -------------------------------------------------------------------

    @Volatile private var webPlayerMode = false

    /**
     * True while the *preference* is on. It only says the web player gets first refusal on each
     * track — not that it is the thing currently making sound, which is [isWebServing].
     */
    val isWebPlayerMode: Boolean get() = webPlayerMode

    /**
     * True while the page — not ExoPlayer — is producing the audio for the track playing right now.
     *
     * The two flags are separate because the web player is a *preferred* engine, not an exclusive
     * one: a local file, a YouTube Music track, or a Spotify track the page refuses to start all
     * fall through to ExoPlayer while the preference stays on. Transport, state mirroring and the
     * MediaSession facade have to follow whichever engine actually holds the track, otherwise
     * pressing pause on a local file would go to the (idle) web page and leave ExoPlayer playing.
     */
    @Volatile private var webServingCurrentTrack = false

    /** Set once the page has actually been observed playing the track it was asked to open. */
    @Volatile private var webStartConfirmed = false

    /** Last track whose end was already acted on, so the 1s poll advances the queue only once. */
    @Volatile private var lastWebEndedTrackId: String? = null

    val isWebServing: Boolean get() = webPlayerMode && webServingCurrentTrack

    /** How long the page gets to start a track before it is handed to the normal engine. */
    private val webStartTimeoutMs = 12_000L

    /**
     * Consecutive tracks the page failed to start. Falling back costs [webStartTimeoutMs] of silence,
     * which is fine once but intolerable before *every* song when the web player simply doesn't work
     * on this device — not signed in, DRM unavailable, Spotify changed its markup. After a couple of
     * failures it stops being offered for the rest of the session; toggling the setting off and on
     * (or a track that does start) re-arms it.
     */
    @Volatile private var webConsecutiveFailures = 0

    private val webFailuresBeforeDisarm = 2

    private var webStartJob: kotlinx.coroutines.Job? = null

    /**
     * Enters or leaves web-player mode. Entering stops ExoPlayer so the two never overlap; leaving
     * stops the page for the same reason.
     */
    fun setWebPlayerMode(enabled: Boolean) {
        if (webPlayerMode == enabled) return
        webPlayerMode = enabled
        if (enabled) {
            // Turning the preference on is the user saying "try again", so forget past failures.
            webConsecutiveFailures = 0
            runCatching { exoPlayer.pause() }
            // Build the WebView and compile the filter lists now, so the first track does not spend
            // its whole watchdog window waiting for a cold start.
            mainScope.launch {
                runCatching {
                    com.varuna.rustify.webplayer.WebPlayerController.getOrCreate(context)
                    com.varuna.rustify.webplayer.WebPlayerController.loadHomeIfNeeded()
                }
                runCatching { com.varuna.rustify.webplayer.AdblockFilters.ensureLoaded(context) }
            }
            startWebStatePolling()
        } else {
            webStartJob?.cancel()
            webStartJob = null
            webServingCurrentTrack = false
            webStartConfirmed = false
            webPollJob?.cancel()
            webPollJob = null
            com.varuna.rustify.webplayer.WebPlayerController.pause()
        }
        // Toggling the mode changes what the session should advertise, and no poll has run yet.
        com.varuna.rustify.webplayer.WebPlayerController.onStateChanged?.invoke()
    }

    private var webPollJob: kotlinx.coroutines.Job? = null

    /** Mirrors the page's playback state into [_state] once a second while web mode is on. */
    private fun startWebStatePolling() {
        webPollJob?.cancel()
        webPollJob = mainScope.launch {
            val controller = com.varuna.rustify.webplayer.WebPlayerController
            while (webPlayerMode) {
                // Only mirror the page while it is the engine holding the current track, and only
                // once it has confirmed playing. Otherwise two things stomp the state: after a
                // fallback ExoPlayer owns playback and the page's stale "paused" would overwrite it,
                // and during a track change the outgoing page state briefly still describes the
                // previous song.
                if (!webServingCurrentTrack || !webStartConfirmed) {
                    delay(1000.milliseconds)
                    continue
                }
                controller.refreshState()
                val web = controller.state.value
                if (web.available) {
                    // Only the clock is mirrored, never the identity of the track. Rustify decides
                    // what plays and published its metadata when it opened the page; replacing
                    // currentTrack with one synthesised from the page's title would hand the queue a
                    // track whose id isn't in it — and a current track that can't be located in the
                    // queue is exactly what made playback loop before v2.11.9.
                    _state.value = _state.value.copy(
                        isPlaying = web.isPlaying,
                        isBuffering = false,
                        positionMs = web.positionMs,
                        durationMs = if (web.durationMs > 0) web.durationMs else _state.value.durationMs
                    )
                }
                // End of track. Opening a track URL leaves Spotify's context holding that one song,
                // so the page just stops — nothing there advances. Rustify's queue does, which is
                // the same role ExoPlayer's STATE_ENDED plays in normal mode. Guarded by id so the
                // one-second poll can only fire it once per track.
                val ended = web.available && !web.isPlaying &&
                    web.durationMs > 0 && web.positionMs >= web.durationMs - 1_500
                val playingId = _state.value.currentTrack?.id
                if (ended && playingId != null && playingId != lastWebEndedTrackId) {
                    lastWebEndedTrackId = playingId
                    skipToNext()
                }
                delay(1000.milliseconds)
            }
        }
    }

    /**
     * Pushes [track]'s metadata into ExoPlayer **without preparing it**, so the notification, the
     * lockscreen and Android Auto show the right title/artist/artwork while the audio actually comes
     * from the web page. The MediaSession reads its metadata from the player, not from [_state], so
     * without this it would keep displaying whatever was played last.
     */
    private fun publishWebMetadata(
        mediaId: String,
        title: String,
        artist: String,
        album: String?,
        artworkUrl: String?
    ) {
        runCatching {
            val metadata = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .apply {
                    if (!artworkUrl.isNullOrBlank()) setArtworkUri(artworkUrl.toUri())
                }
                .build()
            // No prepare(): the item exists only to carry metadata to the session.
            exoPlayer.setMediaItem(
                MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
            )
        }
    }

    /** Publishes a Rustify track's own (Spotify-sourced) metadata. */
    private fun publishWebMetadata(track: FullTrack) = publishWebMetadata(
        mediaId = track.id ?: "web",
        title = track.name,
        artist = track.artists.joinToString(", ") { it.name },
        album = track.album?.name,
        // The lockscreen and Android Auto render this large, so ask for the biggest source rather
        // than whichever one Spotify happened to list first.
        artworkUrl = track.album?.images?.largest()?.url
    )

    /** Position of the current track in [st]'s queue: the cached index if still valid, else by id. */
    private fun currentIndexIn(st: AudioPlayerState): Int {
        val cached = currentQueueIndex
        if (cached in st.queue.indices && st.queue[cached].id == st.currentTrack?.id) return cached
        return st.queue.indexOfFirst { it.id == st.currentTrack?.id }
    }

    // Max age for cached stream URLs (YouTube URLs last ~6h)
    private val maxUrlCacheAge = 6 * 60 * 60 * 1000L

    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("com.google.android.youtube/17.36.4 (Linux; U; Android 12; GB) gzip")
        .setAllowCrossProtocolRedirects(true)

    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(getCache(context))
        .setUpstreamDataSourceFactory(dataSourceFactory)
        .setEventListener(object : CacheDataSource.EventListener {
            override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                android.util.Log.d("AudioCache", "Read from cache: $cachedBytesRead bytes")
            }
            override fun onCacheIgnored(reason: Int) {
                android.util.Log.d("AudioCache", "Cache ignored, reason: $reason")
            }
        })

    private val retryCountMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var autoRetryJob: kotlinx.coroutines.Job? = null

    // Hoisted so refreshAudioFocus() can re-apply the exact same attributes when resetting the focus
    // manager (identical attributes => no audio sink reinit, no glitch).
    private val mediaAudioAttributes = androidx.media3.common.AudioAttributes.Builder()
        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
        // Decoder fallback matters for the Video tab. The default picks the single best-matching
        // decoder and gives up if it fails to initialise; with fallback on, media3 tries the next
        // one (typically the software decoder) instead. Vendor video decoders do fail on unusual
        // profiles, and when they do it is per-video and perfectly reproducible.
        .setRenderersFactory(
            androidx.media3.exoplayer.DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
        )
        // Keep the CPU and the Wi-Fi radio alive *while playing*. Without this the player defaults to
        // WAKE_MODE_NONE: with the screen off the device suspends, and the work that follows
        // STATE_ENDED — resolving the next track's URL and buffering it, both of which need the
        // network — simply doesn't run, so playback stops at a track boundary and only resumes when
        // the screen is woken. Media3's WakeLockManager acquires the lock only while the player is
        // playing and releases it on pause/idle/end, so this does not drain the battery when stopped.
        .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        .build().apply {
            setAudioAttributes(mediaAudioAttributes, true)
        }

    init {
        exoPlayerInstance = exoPlayer
        instance = this
    }

    /**
     * What the current queue came *from* — an album, a playlist, a radio.
     *
     * Null when the queue has no context, which is the common case (a single track, a search
     * result, the user's own queue) and is why "continue listening" is not simply the last thing
     * that played. Only something you can be *in the middle of* is worth offering back.
     */
    @Volatile
    private var playbackContext: PlaybackContext? = null

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var mediaControllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    // Single coalescing save queue — serializes all state writes, eliminating the
    // race where two concurrent `saveState` launches wrote the file out of order.
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        // Initialize the YouTube resolver cache + mappings.
        // NOTE: must be filesDir (persistent), NOT cacheDir: youtube_mappings.json holds the user's
        // confirmed YouTube alternatives and Settings/export read it from filesDir. Using cacheDir made
        // the confirmed matchings invisible ("0 matchings") and wiped when the OS cleared the cache.
        val cachePath = context.filesDir.absolutePath

        // Both of these cross JNI, and a JNI call blocks the thread that makes it. This runs in a
        // service constructor, i.e. on the main thread, so both belong off it — `initCacheDir`
        // reads the match file and used to do that on the UI thread at every start-up (point N).
        mainScope.launch(Dispatchers.IO) {
            NativeEngine.initCacheDir(cachePath)
            // The loopback streaming server. From 3.1 tracks really are served through it — see
            // StreamRouting — so it wants to be up before the first play rather than eventually.
            //
            // But only if the user has the stream cache on. With it off nothing would ever ask the
            // server anything, and a listener nobody talks to is still a listener: the switch turns
            // the whole thing off rather than merely stopping it being used. Turning it back on does
            // not need a restart — StreamRouting starts it on the next play.
            if (com.varuna.rustify.audio.StreamRouting.isEnabled(context)) {
                LocalStreamServer.ensureStarted()
            }
        }

        loadUrlCache()
        loadState()

        // Single consumer: debounced, serialized state persistence.
        mainScope.launch(Dispatchers.IO) {
            for (unused in saveRequests) {
                delay(400) // debounce / coalesce bursts
                while (saveRequests.tryReceive().isSuccess) { /* drain */ }
                writeStateAtomically(_state.value)
            }
        }

        // ExoPlayer state listeners
        exoPlayer.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    _state.value = _state.value.copy(
                        videoSizeRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    )
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            /**
             * Keeps a pause the user asked for from being undone by audio focus.
             *
             * Reported as: with the music paused, the notification for "USB debugging connected"
             * appears and playback resumes on its own. The notification's alert tone takes transient
             * audio focus and hands it straight back, and media3's `AudioFocusManager` responds to
             * the regain by restoring `playWhenReady` — it cannot tell that the pause it is undoing
             * was never its own. Anything that plays a sound would do it; the ADB notice is just the
             * one that happens often enough to notice.
             *
             * `reason` is what separates the two. Every deliberate pause — the app's button, the
             * notification, a headset, Android Auto — funnels through `Player.pause()` and arrives
             * as `USER_REQUEST`; a focus-driven resume arrives as `AUDIO_FOCUS_LOSS`. So a
             * `playWhenReady` that turns true while [userPaused] holds is, by elimination, not the
             * user's doing, and gets put back.
             */
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                when {
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ->
                        userPaused = !playWhenReady
                    playWhenReady && userPaused -> {
                        android.util.Log.d(
                            "AudioPlayerService",
                            "Ignoring an unrequested resume (reason $reason) — the user had paused"
                        )
                        exoPlayer.pause()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _state.value = _state.value.copy(isBuffering = true, isError = false)
                    }
                    Player.STATE_READY -> {
                        _state.value = _state.value.copy(isBuffering = false, isError = false)
                        _state.value.currentTrack?.id?.let { retryCountMap.remove(it) }
                        // Safety net against silent playback: once the audio is ready, restore full
                        // volume unless the DJ is actively ducking. Prevents a stuck ducking/zero volume
                        // from leaving the track playing muted.
                        if (!isDucking) runCatching { exoPlayer.volume = 1.0f }
                        requestSave()
                    }
                    Player.STATE_ENDED -> {
                        _state.value = _state.value.copy(isBuffering = false)
                        listenerTracker.onEnded()
                        onTrackEnded()
                    }
                    Player.STATE_IDLE -> {
                        if (!_state.value.isError && !isResolving) {
                            _state.value = _state.value.copy(isBuffering = false)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listenerTracker.onError()
                android.util.Log.e("AudioPlayerService", "ExoPlayer error: ${error.message}")

                _state.value = _state.value.copy(
                    isBuffering = false,
                    isPlaying = false,
                    isError = true,
                    errorMessage = error.message ?: "Playback error"
                )

                val currentTrackId = _state.value.currentTrack?.id

                var isExpiredUrl = false
                // Clear cached URL if we get 403 Forbidden or 410 Gone (URL expired)
                val cause = error.cause
                if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    // 404 from the loopback server means the stream cache was swept between being
                    // handed out and being read. Same recovery as an expired URL — re-resolve and
                    // play — and it has to be listed here or that case retries with a backoff and
                    // then gives up, on a track that plays perfectly well from its backend.
                    if (cause.responseCode == 404 &&
                        cause.dataSpec.uri.toString().startsWith(LOOPBACK_PREFIX)
                    ) {
                        isExpiredUrl = true
                        currentTrackId?.let {
                            com.varuna.rustify.audio.AudioSourceRegistry.invalidateLastGood(it)
                        }
                        android.util.Log.d("AudioPlayerService", "Stream cache entry gone for $currentTrackId; re-resolving")
                    }
                    if (cause.responseCode == 403 || cause.responseCode == 410) {
                        isExpiredUrl = true
                        if (currentTrackId != null) {
                            // Purge the actual persisted URL cache (map + json + legacy prefs + expiry),
                            // not just the legacy SharedPreferences key — otherwise getCachedStreamUrl would
                            // re-serve the same expired googlevideo URL on the next play.
                            removeCachedStreamUrl(currentTrackId)
                            // Also invalidate the "provider that worked" cache so the next resolution goes
                            // through the whole chain (does not credit the expired one).
                            com.varuna.rustify.audio.AudioSourceRegistry.invalidateLastGood(currentTrackId)
                        }
                        android.util.Log.d("AudioPlayerService", "Stream URL expired (HTTP ${cause.responseCode}), cleared cache for $currentTrackId")
                    }
                }

                if (currentTrackId != null) {
                    resolvedStreamUrls.remove(currentTrackId)
                    preResolvedUrls.remove(currentTrackId)
                    val retries = retryCountMap[currentTrackId] ?: 0
                    if (retries < 2) {
                        retryCountMap[currentTrackId] = retries + 1
                        isRetrying = true
                        // Exponential backoff + jitter for transient errors; fast re-resolve for expired URLs.
                        val delayMs = if (isExpiredUrl) 100L else (500L shl retries).coerceAtMost(8000L) + Random.nextLong(0, 500)
                        android.util.Log.d("AudioPlayerService", "Auto-retrying track $currentTrackId (attempt ${retries + 1}/2) in ${delayMs}ms")
                        autoRetryJob?.cancel()
                        autoRetryJob = mainScope.launch {
                            delay(delayMs.milliseconds)
                            if (_state.value.isError && _state.value.currentTrack?.id == currentTrackId) {
                                retryCurrentTrack(isAutoRetry = true)   // keep onPlayerError's own counter
                            } else {
                                android.util.Log.d("AudioPlayerService", "Auto-retry skipped: state changed for $currentTrackId")
                            }
                            isRetrying = false
                        }
                    } else {
                        android.util.Log.e("AudioPlayerService", "Track $currentTrackId failed after 2 retries, skipping.")
                        retryCountMap.remove(currentTrackId)
                        mainScope.launch {
                            delay(2000.milliseconds)
                            skipToNext()
                        }
                    }
                }
            }
        })

        // Periodic position / buffer update
        mainScope.launch {
            var lastSaveTime = 0L
            var lastSavedPosition = 0L
            var lastContextRecord = 0L
            while (true) {
                // Only trust the player's clock while it actually holds the current logical track.
                // During a switch (and while an extraction retry is backing off, when isBuffering is
                // true but nothing new is prepared yet) the player still reports the PREVIOUS track's
                // position, which used to leak into _state.positionMs and into listening metrics.
                val holdsCurrentTrack = preparedTrackId != null &&
                    preparedTrackId == _state.value.currentTrack?.id
                if (!isResolving && holdsCurrentTrack && (exoPlayer.isPlaying || _state.value.isBuffering)) {
                    val pos = exoPlayer.currentPosition
                    _state.value = _state.value.copy(
                        positionMs = pos,
                        durationMs = if (exoPlayer.duration > 0) exoPlayer.duration
                                     else _state.value.durationMs,
                        bufferPercent = exoPlayer.bufferedPercentage.coerceIn(0, 100)
                    )
                    listenerTracker.onProgress(pos)
                }
                val now = System.currentTimeMillis()
                // Only request a save when position advanced meaningfully (>3s) to reduce I/O.
                if (now - lastSaveTime > 5000) {
                    if (!_state.value.isError && !isRetrying &&
                        kotlin.math.abs(_state.value.positionMs - lastSavedPosition) > 3000) {
                        requestSave()
                        lastSavedPosition = _state.value.positionMs
                    }
                    lastSaveTime = now
                }
                // "Continue listening" — a separate, much slower clock. This one crosses JNI and
                // writes a file, so it runs every twenty seconds rather than every half-second: the
                // cost of being twenty seconds behind is resuming twenty seconds early, and nobody
                // notices that. What people do notice is a phone that never sleeps.
                if (now - lastContextRecord > com.varuna.rustify.bridge.ContinueListening.RECORD_INTERVAL_MS) {
                    lastContextRecord = now
                    recordListeningContext()
                }
                delay(500.milliseconds)
            }
        }

        // Observe network availability so playback auto-recovers after a VPN tunnel comes up or
        // Wi-Fi reconnects.
        registerNetworkCallback()
    }

    // -----------------------------------------------------------------------
    // Network resilience
    // -----------------------------------------------------------------------

    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastRetryForNetwork = 0L

    private fun registerNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { maybeRetryOnReconnect() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        maybeRetryOnReconnect()
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            android.util.Log.w("AudioPlayerService", "NetworkCallback registration failed: ${e.message}")
        }
    }

    private fun maybeRetryOnReconnect() {
        val st = _state.value
        if (!st.isError || isRetrying) return
        val now = System.currentTimeMillis()
        if (now - lastRetryForNetwork < 2000) return // debounce
        lastRetryForNetwork = now
        android.util.Log.d("AudioPlayerService", "Network recovered, retrying current track")
        mainScope.launch { retryCurrentTrack() }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) { }
        networkCallback = null
    }

    // -----------------------------------------------------------------------
    // Core: resolve YouTube stream URL then hand it to ExoPlayer
    // -----------------------------------------------------------------------

    // Explicit, deterministic foreground-service bind, not inside the cancelable playJob.
    private fun ensureForegroundServiceBound() {
        // Don't early-return just because we bound once. If the OS killed the foreground
        // service (notification vanishes, background playback dies until cache clear), the completed
        // future is still non-null but its controller is disconnected → we must re-arm, not give up.
        val existing = mediaControllerFuture
        if (existing != null) {
            if (!existing.isDone) return // bind in progress, leave it
            val controller = runCatching { existing.get() }.getOrNull()
            if (controller != null && controller.isConnected) return // healthy, nothing to do
            controller?.let { runCatching { it.release() } } // dead/disconnected → release & re-arm
            mediaControllerFuture = null
        }
        val startIntent = Intent(context, RustifyForegroundService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(context, startIntent)
        val token = androidx.media3.session.SessionToken(
            context, android.content.ComponentName(context, RustifyForegroundService::class.java)
        )
        val future = androidx.media3.session.MediaController.Builder(context, token).buildAsync()
        mediaControllerFuture = future
        future.addListener({
            try {
                future.get()
            } catch (e: Exception) {
                android.util.Log.e("AudioPlayerService", "MediaController bind failed", e)
                mediaControllerFuture = null // allow retry on next play
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    /**
     * Covers the one window ExoPlayer's own wake lock cannot.
     *
     * `setWakeMode(WAKE_MODE_NETWORK)` keeps the device awake *while the player is playing*, but
     * media3 drops the lock on `STATE_ENDED` and `STATE_IDLE` — and the gap between a track ending
     * and the next one being prepared is exactly where the work lives: resolving the next stream URL
     * over the network can take seconds, and it all happens with the player ended and nothing holding
     * the CPU. With the screen off long enough for Doze to bite, that coroutine simply stops until
     * something wakes the device, which is the "music stopped, turning the screen on resumed it"
     * symptom.
     *
     * Not reference counted and always acquired with a timeout, so overlapping [playTrack] calls just
     * extend it and a path that never reaches the `finally` still cannot leak it.
     */
    private val resolutionWakeLock: android.os.PowerManager.WakeLock by lazy {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Rustify:track-resolution")
            .apply { setReferenceCounted(false) }
    }

    /** Upper bound for the lock, far beyond any real resolution; the `finally` is what normally frees it. */
    private val resolutionWakeLockTimeoutMs = 90_000L

    private fun playTrack(track: FullTrack, youtubeId: String? = null, isAutoRetry: Boolean = false) {
        val trackId = track.id ?: return
        ensureAudioRouteWatcher()
        // A queued track is consumed once it actually starts playing.
        consumeFromUserQueue(trackId)

        // Snapshot the intended start position now, synchronously, before the (possibly slow)
        // resolution below. The periodic position-ticker keeps copying exoPlayer.currentPosition into
        // _state.positionMs while the previous track is still loaded; reading _state.positionMs after
        // resolution would seek the fresh track to that stale advanced position. Callers set
        // positionMs=0 for a new play and to the saved offset for a resume, and this line runs before
        // any suspension, so it's accurate.
        val desiredStartMs = _state.value.positionMs

        // Cancel any pending auto-retry from a previous track to prevent notification/playback race conditions
        autoRetryJob?.cancel()
        autoRetryJob = null
        // Only reset the retry counter on a genuine new play, never on an auto-retry re-entry,
        // otherwise scheduleExtractionRetry/onPlayerError can never reach the give-up threshold.
        if (!isAutoRetry) retryCountMap.remove(trackId)
        isRetrying = false

        // Flush the outgoing listening session here (deterministically, at the moment of switch), not
        // after the new track resolves. Doing it after resolution meant a manual skip / a
        // slow-or-failing next track never flushed the previous session, so only tracks that reached
        // STATE_ENDED were counted. onTrackStarted no-ops if it's the same track (retry/alternative).
        listenerTracker.onTrackStarted(track)

        // Register metadata in Rust so the resolver can match the track (only if not local)
        if (!TrackRef.isLocal(trackId) && !TrackRef.isYtm(trackId)) {
            val artistsJson = "[" + track.artists.joinToString(",") {
                "\"" + it.name.replace("\"", "\\\"") + "\""
            } + "]"
            NativeEngine.registerTrackMetadataNative(
                trackId, track.name, artistsJson, track.durationMs, track.isrc
            )
        }

        // Preload lyrics asynchronously so they're cached when user opens TrackScreen.
        // Skip lyrics for ytm: (no Spotify track id).
        if (!TrackRef.isYtm(trackId)) { preloadLyrics(track) }

        // Show buffering spinner immediately (resolver can take a few seconds).
        // Extract videoId from the ytm: prefix (bypasses the Spotify resolver).
        var effectiveYoutubeId = youtubeId
        TrackRef.youtubeVideoIdOf(trackId)?.let { effectiveYoutubeId = it }

        val myGen = resolveGen.incrementAndGet()
        isResolving = true
        _state.value = _state.value.copy(
            isBuffering = true, isError = false, errorMessage = "",
            // Cleared for the incoming track instead of being left over from the outgoing one. It is
            // only ever *set* after a resolution succeeds, so a network track following a local one
            // inherited the green "playing from a local file" badge — and kept it while it buffered,
            // and even while it failed with "yt-dlp returned no url". A `local:` id is local by
            // definition; anything else has to earn the badge below.
            isLocalSource = TrackRef.isLocal(trackId)
        )

        playJob?.cancel()
        // Hold the CPU from here until the player is prepared: everything in between (resolution,
        // network) runs while ExoPlayer is ended or idle and therefore holds nothing itself.
        runCatching { resolutionWakeLock.acquire(resolutionWakeLockTimeoutMs) }
        playJob = mainScope.launch {
            try {
                // Do NOT set a dummy MediaItem / pause the player here. The notification keeps the
                // previous item until the real one is prepared, instead of freezing on a fake
                // "loading" item while paused. The UI spinner is driven by _state.isBuffering.
                val artworkUrl = track.album?.images?.firstOrNull()?.url ?: track.externalUri ?: ""
                val metadata = androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artists.joinToString(", ") { it.name })
                    .setArtworkUri(if (artworkUrl.isNotBlank()) android.net.Uri.parse(artworkUrl) else null)
                    .build()

                var streamUrl: String? = null
                // Set when the track plays through the local streaming server instead of from its
                // upstream URL. Kept apart from `streamUrl` on purpose: `streamUrl` is what the
                // *backend* produced and is what gets persisted and expiry-checked, while this is a
                // loopback URL good only for this process. Persisting one would hand a later
                // session a dead port.
                var routedUrl: String? = null

                if (TrackRef.isLocal(trackId)) {
                    streamUrl = TrackRef.localUriOf(trackId) ?: trackId
                    android.util.Log.d("AudioPlayerService", "Playing local track: $streamUrl")
                } else {
                    // "Match local first": a file already on the device beats every cache and every
                    // network backend below, which is why this runs before any of them.
                    streamUrl = localStreamFor(track, effectiveYoutubeId)

                    // Only use the cached/pre-resolved URL when NO explicit youtubeId hint is given.
                    // An explicit hint means "force a fresh resolution" (e.g. picking a YouTube alternative),
                    // so the stale cached URL must not shadow the new source.
                    if (streamUrl == null && effectiveYoutubeId.isNullOrBlank()) {
                        // A confirmed user alternative must beat EVERY cached URL — including the one
                        // preBufferNextTrack produced for this track, possibly before the user made the
                        // choice. Checking it only inside the persisted-cache branch (as this used to)
                        // meant the alternative applied when the track was started by hand (which clears
                        // the pre-buffer) but was silently ignored when the queue reached it on its own.
                        val mappedId = NativeEngine.getAlternativeTrackNative(trackId)
                        val hasUserMapping = mappedId.isNotBlank() &&
                            com.varuna.rustify.bridge.UserAlternatives.isUserSet(context, trackId)
                        // Honour StreamInfo.expiresAtMs — a URL past its expiry would 403 on ExoPlayer.
                        val expiresAt = urlExpiryCache[trackId]
                        val expired = expiresAt != null && System.currentTimeMillis() >= expiresAt

                        if (hasUserMapping || expired) {
                            if (expired) android.util.Log.d("AudioPlayerService", "Cached URL for $trackId expired; re-resolving")
                            if (hasUserMapping) android.util.Log.d("AudioPlayerService", "User mapping found for $trackId ($mappedId); dropping every cached URL")
                            removeCachedStreamUrl(trackId)
                            preResolvedUrls.remove(trackId)
                            resolvedStreamUrls.remove(trackId)
                            com.varuna.rustify.audio.AudioSourceRegistry.invalidateLastGood(trackId)
                            // The stored audio too. A user alternative is the user saying "not that
                            // recording"; serving the old bytes from disk would make the choice look
                            // as if it had done nothing. An expiry does not mean that, but the URL is
                            // gone either way and the file will simply be refetched.
                            if (hasUserMapping) com.varuna.rustify.audio.StreamRouting.forget(context, trackId)
                        } else {
                            // The stream cache. Asked before anything else because it is the only
                            // path that costs nothing: the audio is already on disk, so there is no
                            // backend to choose, no yt-dlp to run and nothing to download. It comes
                            // back as an ordinary http://127.0.0.1 URL that Media3 can seek in.
                            routedUrl = com.varuna.rustify.audio.StreamRouting.cachedUrlFor(context, trackId)
                            if (routedUrl != null) {
                                android.util.Log.d("AudioPlayerService", "Playing $trackId from the stream cache")
                            } else {
                                // Persisted URL cache — avoids an unnecessary yt-dlp resolution.
                                val cachedUrl = getCachedStreamUrl(trackId)
                                if (!cachedUrl.isNullOrBlank()) {
                                    streamUrl = cachedUrl
                                    android.util.Log.d("AudioPlayerService", "Using persisted cached URL for $trackId")
                                }
                            }
                        }
                    }
                    if (streamUrl == null && routedUrl == null) {
                        if (effectiveYoutubeId.isNullOrBlank()) streamUrl = preResolvedUrls[trackId]
                        if (streamUrl.isNullOrBlank()) {
                            // Network resolution now lives in the backend chain
                            // (AudioSourceRegistry.streamChain). yt-dlp is the default provider;
                            // `hint` is the explicit youtubeId of a chosen alternative.
                            android.util.Log.d("AudioPlayerService", "Resolving stream URL via audio chain for $trackId (hint=$youtubeId)...")
                            // Resolve OFF the main thread. Providers already switch to IO internally,
                            // but isAvailableFor() (e.g. Invidious fetching its instance directory) runs
                            // outside their withContext, so a stalled directory fetch could still jank the
                            // UI thread. Wrapping the whole chain call here guarantees the main thread stays
                            // free during a slow/hung backend resolution.
                            val res = withContext(Dispatchers.IO) {
                                com.varuna.rustify.audio.AudioSourceRegistry.streamChain(context)
                                    .resolveStreamUrl(track, hint = effectiveYoutubeId)
                            }
                            res.onSuccess { (providerId, info) ->
                                streamUrl = info.uri
                                lastResolveError = null   // clear any stale failure reason on success
                                // Remember when this URL dies so a later play doesn't hand ExoPlayer a 403.
                                info.expiresAtMs?.let { urlExpiryCache[trackId] = it }
                                android.util.Log.d("AudioPlayerService", "Chain resolved stream URL for $trackId via $providerId: ${info.uri.take(80)}...")
                                // Start filling the stream cache in the background, so the *next*
                                // play of this track skips everything above. It does not delay this
                                // one: the answer is null unless the bytes happen to be there
                                // already, and then playing them is strictly better anyway.
                                routedUrl = com.varuna.rustify.audio.StreamRouting
                                    .rememberAfterResolving(context, trackId, info)
                            }
                            res.onFailure { e ->
                                // Surface the real per-provider reason so the banner isn't just "not playable".
                                val detail = (e as? com.varuna.rustify.audio.AudioSourceChainException)
                                    ?.errors?.mapNotNull { it.message }?.joinToString("; ")
                                    ?.takeIf { it.isNotBlank() } ?: e.message
                                android.util.Log.w("AudioPlayerService", "Stream chain failed for $trackId: $detail")
                                lastResolveError = detail
                            }
                        } else {
                            android.util.Log.d("AudioPlayerService", "Using pre-buffered stream URL for $trackId")
                            preResolvedUrls.remove(trackId)
                        }
                    }
                }

                if (streamUrl.isNullOrBlank() && routedUrl.isNullOrBlank()) {
                    // Auto-retry the extraction failure instead of leaving the user stuck.
                    scheduleExtractionRetry(track, effectiveYoutubeId)
                    return@launch
                }

                // What actually goes to Media3. The loopback URL wins when there is one, because
                // the bytes behind it are already on this device: no expiry, no re-resolve, and a
                // real `Range` over a real file instead of a custom DataSource.
                val playbackUrl = routedUrl ?: streamUrl!!

                val mediaItem = MediaItem.Builder()
                    .setUri(playbackUrl)
                    .setMediaMetadata(metadata)
                    .apply { setCustomCacheKey(if (effectiveYoutubeId.isNullOrBlank()) trackId else "${trackId}_${effectiveYoutubeId}") }
                    .build()

                resolvedStreamUrls[trackId] = playbackUrl
                // Persist URL for next session (skip yt-dlp), but NOT for local files
                val isLocalStream = TrackRef.isLocal(trackId) || playbackUrl.startsWith("content://") || playbackUrl.startsWith("file://")
                // Expose to the UI whether the current track plays from a local file (for the green button).
                if (_state.value.currentTrack?.id == track.id) {
                    _state.value = _state.value.copy(isLocalSource = isLocalStream)
                }
                // Only ever the upstream URL, never a loopback one: the port and the token are good
                // for this process only, so persisting one would hand the next session a URL that
                // cannot connect to anything. A backend can produce one directly — Deezer returns
                // the proxy URL — so this checks the URL rather than where it came from.
                val persistable = streamUrl
                if (!TrackRef.isLocal(trackId) && !isLocalStream &&
                    !persistable.isNullOrBlank() &&
                    !persistable.startsWith(LOOPBACK_PREFIX)
                ) {
                    putCachedStreamUrl(trackId, persistable)
                }

                val mediaSource = when {
                    isLocalStream -> {
                        android.util.Log.d("AudioPlayerService", "Creating DefaultMediaSource for local track")
                        DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context))
                            .createMediaSource(mediaItem)
                    }
                    playbackUrl.startsWith(LOOPBACK_PREFIX) -> {
                        // Straight off the loopback server — either from the stream cache, or the
                        // Deezer proxy decrypting as it goes. Checked on the URL rather than on
                        // `routedUrl`, because since 3.2 a backend can hand one back directly.
                        //
                        // Deliberately not through `cacheDataSourceFactory`: cached bytes are
                        // already on this device, and Media3's own cache in front of them would
                        // store a second copy of a file we already have.
                        android.util.Log.d("AudioPlayerService", "Serving $trackId from the local server")
                        DefaultMediaSourceFactory(androidx.media3.datasource.DefaultHttpDataSource.Factory())
                            .createMediaSource(mediaItem)
                    }
                    else -> {
                        android.util.Log.d("AudioPlayerService", "yt-dlp Extracted direct stream: ${playbackUrl.take(80)}...")
                        DefaultMediaSourceFactory(cacheDataSourceFactory)
                            .createMediaSource(mediaItem)
                    }
                }

                // Bind the foreground service HERE — one frame before playback — so the OS's
                // startForeground() 5s window opens only when we're about to play. Binding it eagerly
                // (before a slow resolution) caused a foreground-service ANR with Invidious/Deezer.
                ensureForegroundServiceBound()

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                // From here on the player really holds THIS track, so its live position is meaningful.
                preparedTrackId = trackId
                // Seek to the position captured at the top of playTrack (see desiredStartMs), never to
                // the live _state.positionMs which the ticker may have advanced meanwhile.
                if (desiredStartMs > 0L) {
                    exoPlayer.seekTo(desiredStartMs)
                }
                // Never inherit a leftover DJ-duck volume onto a fresh track (a stuck 0.22 is inaudible).
                if (!isDucking) runCatching { exoPlayer.volume = 1.0f }
                // If a call is active/ringing, DON'T start audio — otherwise the song would play over a
                // hands-free call when a track change coincides with an incoming call. Stay paused; the
                // player is prepared and seeked, so the user (or the media button) resumes instantly.
                if (isInCall()) {
                    exoPlayer.playWhenReady = false
                    _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                    android.util.Log.d("AudioPlayerService", "In call — deferring playback of ${track.name}")
                } else {
                    // Deliberately NOT calling refreshAudioFocus() here any more — see its doc. It used
                    // to run on every track as a pre-emptive reset, which meant abandoning and
                    // re-requesting Android audio focus at every transition. That is the one thing this
                    // app does with audio that no other player does, and it lines up with playback going
                    // silent-but-advancing over Bluetooth. The reset is still one tap away on resume.
                    exoPlayer.play()
                    ensurePlaybackTookHold(trackId)
                }
                // onTrackStarted runs at the top of playTrack so the previous session flushes
                // deterministically on every switch, including a manual skip / a failing next track.

                // Pre-buffer the next track in the queue
                preBufferNextTrack()
            } finally {
                // Generation token — only the latest playTrack clears isResolving.
                if (resolveGen.get() == myGen) {
                    isResolving = false
                }
                // By now prepare()+play() have already moved the player to BUFFERING with
                // playWhenReady set, so media3's own lock has taken over; on the failure paths there
                // is nothing left to keep awake either way.
                if (resolveGen.get() == myGen) {
                    runCatching { if (resolutionWakeLock.isHeld) resolutionWakeLock.release() }
                }
            }
        }
    }

    /**
     * True while playback is paused because someone asked for it, as opposed to paused because
     * something took the audio focus away. Maintained entirely from `onPlayWhenReadyChanged`, which
     * is the one place every source of a pause — app button, notification, headset, Android Auto —
     * converges, so it needs no bookkeeping at the call sites.
     */
    @Volatile private var userPaused = false

    /**
     * Confirms that `play()` actually took, and asks again once if it did not.
     *
     * `exoPlayer.play()` is a request, not a guarantee. Media3 abandons Android audio focus when the
     * player reaches `STATE_ENDED`, so **every automatic track change is an abandon/re-request
     * cycle**, and when the re-request comes back denied — another app holding focus for a beat, a
     * Bluetooth route still settling — `AudioFocusManager` answers `PLAYER_COMMAND_DO_NOT_PLAY` and
     * clears `playWhenReady` without a word. The queue has advanced, the notification shows the new
     * song, and nothing plays until play is pressed by hand. That is the "sometimes the next track
     * doesn't start on its own" report, and it explains why it is intermittent.
     *
     * `playWhenReady` false shortly after asking for it can only mean something took it away, which
     * makes it a safe thing to test. A pause the user asked for in the meantime is excluded so this
     * can never override them.
     */
    private fun ensurePlaybackTookHold(trackId: String, attempt: Int = 0) {
        if (attempt >= 2) return
        mainScope.launch {
            delay(700.milliseconds)
            if (preparedTrackId != trackId) return@launch          // moved on to another track
            if (userPaused) return@launch                           // the user pressed pause
            if (_state.value.isError || isInCall()) return@launch
            if (exoPlayer.playWhenReady) return@launch              // it took
            android.util.Log.w(
                "AudioPlayerService",
                "play() did not take hold for $trackId (audio focus denied?) — asking again"
            )
            refreshAudioFocus()
            exoPlayer.play()
            ensurePlaybackTookHold(trackId, attempt + 1)
        }
    }

    // The yt-dlp extraction with retries lives in YtDlpAudioSource (resolved via
    // AudioSourceRegistry.streamChain). See audio/YtDlpAudioSource.kt::extractStreamUrlWithRetry.

    // Retry the failure to extract a stream URL.
    private fun scheduleExtractionRetry(track: FullTrack, youtubeId: String?) {
        val id = track.id ?: return
        val n = retryCountMap[id] ?: 0
        if (n >= 2) {
            retryCountMap.remove(id)
            // Include the concrete chain failure (e.g. "resolver returned empty YouTube id",
            // yt-dlp error) instead of a bare "no source" banner.
            val reason = lastResolveError?.takeIf { it.isNotBlank() }
            _state.value = _state.value.copy(
                isBuffering = false, isPlaying = false,
                isError = true,
                errorMessage = if (reason != null) "No se encontró una fuente reproducible: $reason"
                               else "No se encontró una fuente reproducible"
            )
            mainScope.launch { delay(2000.milliseconds); skipToNext() }
            return
        }
        retryCountMap[id] = n + 1
        isRetrying = true
        val delayMs = (500L shl n).coerceAtMost(8000L) + Random.nextLong(0, 500)
        _state.value = _state.value.copy(
            isError = true, isBuffering = true,
            errorMessage = "Reintentando (${n + 1}/2)…"
        )
        autoRetryJob?.cancel()
        autoRetryJob = mainScope.launch {
            delay(delayMs.milliseconds)
            if (_state.value.currentTrack?.id == id) {
                resolvedStreamUrls.remove(id)
                preResolvedUrls.remove(id)
                removeCachedStreamUrl(id)                       // don't let a stale URL shadow re-resolution
                retryCurrentTrack(youtubeId, isAutoRetry = true) // keep the retry counter
            }
            isRetrying = false
        }
    }

    /**
     * Path of the local file [track] should play from, or null to resolve it over the network.
     *
     * Shared by [playTrack] and [preBufferNextTrack] so both agree on what counts as local. They used
     * to disagree: pre-buffering went straight to the provider chain, so a track sitting in the user's
     * own library still burned a yt-dlp resolution in the background — sometimes failing with
     * "returned no url" for a song that was on the device all along.
     *
     * An explicit YouTube alternative — a just-picked hint or a user-confirmed persisted mapping —
     * beats the local match. Otherwise picking an alternative for a locally-matched track would
     * silently do nothing. An *auto*-persisted mapping does not count, so the local match wins again.
     */
    private fun localStreamFor(track: FullTrack, youtubeIdHint: String?): String? {
        val trackId = track.id ?: return null
        val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("settings_match_local_first", false)) return null
        val dirs = prefs.getStringSet("local_music_directories", emptySet()).orEmpty()
        if (dirs.isEmpty()) return null

        val hasUserAlternative = !youtubeIdHint.isNullOrBlank() ||
            com.varuna.rustify.bridge.UserAlternatives.isUserSet(context, trackId)
        if (hasUserAlternative) {
            android.util.Log.d("AudioPlayerService", "User YouTube alternative present for $trackId — skipping local match")
            return null
        }
        val match = com.varuna.rustify.bridge.SpotifyRepository.findLocalMatch(context, track)
            ?: return null
        return TrackRef.localUriOf(match.id)?.also {
            android.util.Log.d("AudioPlayerService", "Matched Spotify track to local file: $it")
        }
    }

    /**
     * Writes down where this context is, so Home can offer it back.
     *
     * Silent about everything it does not do: no context, nothing playing, or an error — none of
     * those is a session, and none of them is worth a log line every twenty seconds.
     */
    private fun recordListeningContext() {
        val context = playbackContext ?: return
        val st = _state.value
        if (st.isError || st.queue.isEmpty()) return
        val index = currentIndexIn(st)
        if (index < 0) return

        val session = com.varuna.rustify.bridge.ListeningSession(
            id = context.id,
            label = context.label,
            subtitle = context.subtitle,
            imageUrl = context.imageUrl,
            trackTitle = st.currentTrack?.name.orEmpty(),
            queue = st.queue.mapNotNull { it.id },
            index = index,
            positionMs = st.positionMs,
            durationMs = st.durationMs,
            updatedAtMs = System.currentTimeMillis()
        )
        // The queue can contain a track with no id; if that shifted the indices the session would
        // resume on the wrong song, so it is dropped rather than stored wrong.
        if (session.queue.size != st.queue.size) return

        mainScope.launch(Dispatchers.IO) {
            com.varuna.rustify.bridge.ContinueListening.record(session)
        }
    }

    /**
     * Records that the current track was heard through, within whatever context is playing —
     * point I.
     *
     * No context, no mark: a song reached from search is not part of any list, and there is nothing
     * for a tick to appear next to. The queue goes with it on every call because the marks are a
     * bitfield indexed by position, and handing over the current order is what lets it realign when
     * a playlist has been edited since.
     */
    private fun markCurrentAsListened(st: AudioPlayerState) {
        val context = playbackContext ?: return
        val trackId = st.currentTrack?.id ?: return
        val ids = st.queue.mapNotNull { it.id }
        // A queue with an idless track in it would shift every position after it, so the marks
        // would land on the wrong songs. Dropped rather than stored wrong — the same rule the
        // listening sessions already follow.
        if (ids.size != st.queue.size) return
        val queueJson =
            org.json.JSONArray().also { array -> ids.forEach { array.put(it) } }.toString()
        mainScope.launch(Dispatchers.IO) {
            runCatching { NativeEngine.markListened(context.id, queueJson, trackId) }
                .onFailure { android.util.Log.w("AudioPlayerService", "could not mark $trackId", it) }
        }
    }

    private fun preBufferNextTrack() {
        val st = _state.value
        val idx = currentIndexIn(st)
        // On the last track there is nothing to pre-buffer, and that is exactly where the longest
        // silence in the app used to be: the radio was only fetched once the track had *ended*, so
        // the gap was a network round trip for the radio plus a whole resolution for its first song,
        // with nothing playing through either. Fetching it now — while the last track is still
        // playing — turns that into an ordinary track change.
        if (idx != -1 && idx == st.queue.lastIndex) {
            prefetchRadioForEndOfQueue(st)
        }
        if (idx != -1 && idx < st.queue.lastIndex) {
            val nextTrack = st.queue[idx + 1]
            val nextTrackId = nextTrack.id ?: return
            if (preResolvedUrls.containsKey(nextTrackId)) {
                return
            }
            // A track that will play off the device needs no network resolution: playTrack takes the
            // local match before it looks at any cache, so anything resolved here would be thrown
            // away — after possibly failing and logging a yt-dlp error for a song we already have.
            // The lyrics preload below still runs, which is the other half of what this does.
            preBufferingJob?.cancel()
            preBufferingJob = mainScope.launch {
                // Inside the coroutine: matching against the local library can touch disk the first
                // time, and this is called from the main thread.
                val nextIsLocal = TrackRef.isLocal(nextTrackId) ||
                    withContext(Dispatchers.IO) { localStreamFor(nextTrack, null) } != null

                if (!nextIsLocal) {
                    android.util.Log.d("AudioPlayerService", "Pre-buffering next track: ${nextTrack.name}")

                    // Register metadata in Rust so the resolver can match the track
                    val artistsJson = "[" + nextTrack.artists.joinToString(",") {
                        "\"" + it.name.replace("\"", "\\\"") + "\""
                    } + "]"
                    NativeEngine.registerTrackMetadataNative(
                        nextTrackId, nextTrack.name, artistsJson, nextTrack.durationMs, nextTrack.isrc
                    )

                    // Pre-buffer through the same backend chain as playTrack
                    // (single source of truth: one resolveStreamUrl, without duplicating the yt-dlp pattern).
                    val res = withContext(Dispatchers.IO) {
                        com.varuna.rustify.audio.AudioSourceRegistry.streamChain(context)
                            .resolveStreamUrl(nextTrack, hint = null)
                    }
                    res.onSuccess { (_, info) ->
                        preResolvedUrls[nextTrackId] = info.uri
                        resolvedStreamUrls[nextTrackId] = info.uri
                        info.expiresAtMs?.let { urlExpiryCache[nextTrackId] = it }  // track pre-buffered URL expiry too
                        // And put it in the stream cache while we are here. Pre-buffering already
                        // decided this track is worth resolving ahead of time; downloading it is
                        // the same bet, and it is the one that survives the URL expiring.
                        com.varuna.rustify.audio.StreamRouting
                            .rememberAfterResolving(context, nextTrackId, info)
                        android.util.Log.d("AudioPlayerService", "Successfully pre-buffered: ${nextTrack.name}")
                    }
                    res.onFailure { e ->
                        android.util.Log.w("AudioPlayerService", "Pre-buffer chain failed for ${nextTrack.name}: ${e.message}")
                    }
                } else {
                    android.util.Log.d("AudioPlayerService", "Next track plays from a local file; no pre-buffer needed")
                }

                // Preload lyrics for the next track so they're ready when the user views the track screen
                preloadLyrics(nextTrack)
            }
        }
    }

    private fun preloadLyrics(track: FullTrack) {
        val trackId = track.id ?: return
        preloadedLyricsTrackId = trackId
        _preloadedLyrics.value = null // Reset for new track
        if (TrackRef.isLocal(trackId)) return
        mainScope.launch(Dispatchers.IO) {
            try {
                val artist = track.artists.firstOrNull()?.name ?: return@launch
                val durationSec = track.durationMs / 1000
                val result = LyricsRepository.getLyrics(
                    context = context,
                    trackId = trackId,
                    artist = artist,
                    title = track.name,
                    durationSec = durationSec
                )
                // Only publish if still the current track
                if (preloadedLyricsTrackId == trackId) {
                    _preloadedLyrics.value = result
                }
                android.util.Log.d("AudioPlayerService", "Lyrics preloaded for: ${track.name}")
            } catch (_: Exception) {
                android.util.Log.d("AudioPlayerService", "Lyrics preload skipped for: ${track.name}")
            }
        }
    }

    fun playPreview(spotifyTrackId: String, youtubeVideoId: String) {
        val track = _state.value.currentTrack
            ?: FullTrack(spotifyTrackId, "", "", false, 0, "", emptyList(), null)
        playTrack(track, youtubeVideoId)
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    private fun notifyQueueChanged(queue: List<FullTrack>) {
        // Build the array properly: "local:" ids carry a content URI, so hand-rolling the JSON risked
        // emitting something the Rust side can't parse if an id ever contains a quote or backslash.
        val arr = org.json.JSONArray()
        queue.forEach { t -> t.id?.let { arr.put(it) } }
        NativeEngine.updateQueueNative(arr.toString())
        // Refresh the "Queue" node of the Android Auto tree (its content is the playback queue; without
        // notifying, the car keeps showing the old cached queue).
        MediaBrowserNotifier.notifyChildrenChanged("sec_queue")
    }

    /**
     * In web-player mode a track picked anywhere in Rustify is opened in the page instead of being
     * resolved to a stream — the app stays the UI, Spotify's own player produces the audio.
     *
     * The web player is the *preferred* engine, not the only one, so this can decline in two ways:
     *
     *  - **Immediately** (returns false) when the track cannot be addressed there at all — a local
     *    file, a YouTube Music track, or no WebView yet. The caller plays it normally.
     *  - **Later**, when the page was asked but never actually started: not signed in, DRM refused,
     *    Spotify changed its markup, no network. A watchdog then hands *that track* to [fallback],
     *    leaving the preference on so the next track tries the page again.
     *
     * That second case is the whole reason this isn't a plain on/off switch: without it a single
     * unplayable track leaves the user staring at a silent player.
     */
    private fun playInWebPlayer(track: FullTrack, fallback: () -> Unit): Boolean {
        val id = track.id ?: return false
        if (TrackRef.isLocal(id) || TrackRef.isYtm(id)) return false
        if (webConsecutiveFailures >= webFailuresBeforeDisarm) return false
        val controller = com.varuna.rustify.webplayer.WebPlayerController
        // Creating a WebView is a main-thread operation; from anywhere else, decline rather than
        // crash — the normal engine covers the track.
        if (!controller.isReady) {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return false
            runCatching { controller.getOrCreate(context) }
            if (!controller.isReady) return false
        }

        // The page is taking the track, so [playTrack] will not run for it. Consume the manual queue
        // entry here instead — this is the other half of the fork, and for a long time it was missing.
        consumeFromUserQueue(id)

        webStartJob?.cancel()
        webServingCurrentTrack = true
        webStartConfirmed = false
        lastWebEndedTrackId = null
        controller.playSpotifyUrl("https://open.spotify.com/track/$id")
        publishWebMetadata(track)
        // Buffering, not playing: the page needs a moment, and the 1s poll flips this to playing as
        // soon as it really is. Claiming isPlaying here made the UI lie for the first second.
        _state.value = _state.value.copy(
            currentTrack = track,
            isPlaying = false,
            isBuffering = true,
            positionMs = 0L,
            durationMs = track.durationMs.toLong()
        )

        webStartJob = mainScope.launch {
            if (controller.awaitPlaybackStart(webStartTimeoutMs)) {
                webStartConfirmed = true
                webConsecutiveFailures = 0
                controller.onStateChanged?.invoke()
                return@launch
            }
            // The page never produced audio for this track — give it to the normal engine.
            webConsecutiveFailures++
            android.util.Log.w(
                "AudioPlayerService",
                "Web player did not start $id; falling back (failure $webConsecutiveFailures)"
            )
            webServingCurrentTrack = false
            runCatching { controller.pause() }
            controller.onStateChanged?.invoke()
            fallback()
        }
        return true
    }

    /**
     * Plays [track] with Rustify's own engine, setting the state the way the non-web paths do. Used
     * as the fallback when the web player declines a track after having been asked.
     */
    private fun playWithLocalEngine(track: FullTrack) {
        _state.value = _state.value.copy(
            currentTrack = track,
            isPlaying = false,
            isBuffering = true,
            positionMs = 0L,
            durationMs = track.durationMs.toLong()
        )
        playTrack(track)
        requestSave()
    }

    /**
     * Starts [track] on whichever engine should serve it, for the queue-navigation paths that have
     * already set the state. Web mode gets first refusal; everything else goes to ExoPlayer.
     */
    private fun startTrack(track: FullTrack) {
        if (webPlayerMode && playInWebPlayer(track) { playWithLocalEngine(track) }) return
        playTrack(track)
    }

    fun loadAndPlay(track: FullTrack) {
        // The foreground service is bound inside playTrack() right before playback starts, NOT here —
        // binding it eagerly started the OS's 5s startForeground() clock while a slow backend
        // (Invidious/Deezer) was still resolving, which fired a foreground-service ANR (with the UI
        // still alive). yt-dlp masked it by resolving from cache in <5s.
        // Never re-inject the track being played as a queued duplicate of itself, and never re-inject
        // one that is no longer pending — that is how a queue from another day ends up trailing a
        // song picked today.
        reconcileUserQueue(_state.value)
        val queue = listOf(track) + synchronized(userQueue) { userQueue.filter { it.id != track.id } }
        currentQueueIndex = 0
        _state.value = _state.value.copy(
            currentTrack = track,
            isPlaying = false,
            queue = queue,
            // Include userQueue so cycling shuffle/repeat (which restores originalQueue) doesn't
            // silently drop the manually-queued tracks.
            originalQueue = queue,
            // Picking a track by hand ends shuffle — same rule as loadPlaylist.
            isShuffle = false,
            positionMs = 0L,
            durationMs = track.durationMs.toLong()
        )
        preResolvedUrls.clear()
        // The queue above is built either way, so "Next Up" is correct in web mode too.
        startTrack(track)
        notifyQueueChanged(queue)
        requestSave()
    }

    /**
     * Plays [tracks] starting at [initialIndex].
     *
     * Choosing a song by hand turns shuffle off, the way Spotify does it: shuffle is something you
     * opt into — the shuffle button, or a list's shuffle-play — and a deliberate choice of track is
     * the opposite of asking for a random order. [keepShuffle] is for [shufflePlay], the one caller
     * that is itself the opt-in.
     */
    /**
     * @param startPositionMs where the selected track should start. Handed to the state **before**
     *   [startTrack], because that is where `playTrack` reads it (`desiredStartMs`) and seeks after
     *   `prepare()`. Seeking from the caller after this returns does nothing: the resolution is
     *   asynchronous, so at that moment ExoPlayer is not holding this track yet, and the position it
     *   later starts from was already captured as zero.
     */
    fun loadPlaylist(
        tracks: List<FullTrack>,
        initialIndex: Int = 0,
        keepShuffle: Boolean = false,
        context: PlaybackContext? = null,
        startPositionMs: Long = 0L
    ) {
        if (tracks.isEmpty()) return
        // Set before anything plays, and **cleared when absent**: a queue started from somewhere
        // with no context must not inherit the last one, or resuming would take you to an album you
        // never opened.
        playbackContext = context
        // Foreground bind happens in playTrack() right before playback (see loadAndPlay note).
        val idx = initialIndex.coerceIn(0, tracks.lastIndex)
        val selected = tracks[idx]

        val shuffle = keepShuffle && _state.value.isShuffle
        val baseQueue = if (shuffle) {
            val remaining = tracks.filterIndexed { i, _ -> i != idx }.shuffled()
            listOf(selected) + remaining
        } else {
            tracks
        }

        val selectedIdx = baseQueue.indexOfFirst { it.id == selected.id }
        // Only inject queued tracks that aren't already part of this list — otherwise starting a
        // playlist that contains a queued song silently duplicated it — and only ones still pending
        // against the queue being left behind.
        reconcileUserQueue(_state.value)
        val injected = synchronized(userQueue) {
            userQueue.filter { uq -> baseQueue.none { it.id == uq.id } }
        }
        val queue = if (selectedIdx != -1) {
            baseQueue.take(selectedIdx + 1) + injected + baseQueue.drop(selectedIdx + 1)
        } else {
            listOf(selected) + injected + baseQueue.filter { it.id != selected.id }
        }
        currentQueueIndex = if (selectedIdx != -1) selectedIdx else 0

        // originalQueue is what cycling shuffle/repeat restores, so the queued tracks have to be in it
        // too (loadAndPlay already did this) — otherwise toggling playback mode silently dropped the
        // songs the user had queued by hand.
        val origIdx = tracks.indexOfFirst { it.id == selected.id }
        val originalQueue = if (injected.isEmpty()) tracks
            else if (origIdx != -1) tracks.take(origIdx + 1) + injected + tracks.drop(origIdx + 1)
            else tracks + injected

        _state.value = _state.value.copy(
            currentTrack = selected,
            isPlaying = false,
            queue = queue,
            originalQueue = originalQueue,
            isShuffle = shuffle,
            positionMs = startPositionMs.coerceAtLeast(0L),
            durationMs = selected.durationMs.toLong()
        )
        preResolvedUrls.clear()
        // Tapping a song inside a list is THE common way to start playback, so web mode has to cover
        // it too. The engine is chosen here, after the queue is built, so web mode gets the same
        // queue as normal playback — including the manually queued tracks injected above, which an
        // earlier shortcut through this method used to drop.
        startTrack(selected)
        notifyQueueChanged(queue)
        requestSave()
    }

    /**
     * Start playback of [tracks] in shuffle mode from a random first track (not index 0).
     * Forces isShuffle ON before delegating to loadPlaylist (which honours _state.isShuffle).
     */
    fun shufflePlay(tracks: List<FullTrack>) {
        if (tracks.isEmpty()) return
        _state.value = _state.value.copy(isShuffle = true, isRepeat = false)
        loadPlaylist(tracks, tracks.indices.random(), keepShuffle = true)
        requestSave()
    }

    fun cyclePlaybackMode() {
        val st = _state.value
        reconcileUserQueue(st)
        // Each branch swaps the whole queue, so the cached position has to be recomputed against the
        // new list before anything reads it (preBufferNextTrack does, immediately).
        if (!st.isShuffle && !st.isRepeat) {
            val current = st.currentTrack
            val curIdx = currentIndexIn(st)
            // Shuffle applies to the generated queue only. The manually queued block keeps both its
            // position (immediately after the current track) and its order — those songs were lined
            // up deliberately. Scattering them also broke every later addition: with the block no
            // longer contiguous, new tracks were inserted in front of it instead of behind.
            val pending = synchronized(userQueue) { userQueue.toList() }
            val manualEnd = if (curIdx >= 0) manualBlockEnd(st.queue, curIdx, pending) else -1
            val manual = if (curIdx >= 0) st.queue.subList(curIdx + 1, manualEnd).toList() else emptyList()
            // Drop the current track by POSITION, not by id: filtering by id also removed the other
            // copies of a song a playlist legitimately repeats.
            val remaining = if (curIdx >= 0) {
                st.queue.filterIndexed { i, _ -> i != curIdx && (i < curIdx || i >= manualEnd) }.shuffled()
            } else {
                st.queue.filter { it.id != current?.id }.shuffled()
            }
            val newQueue = if (current != null) listOf(current) + manual + remaining
                           else manual + remaining
            currentQueueIndex = if (current != null) 0 else -1
            _state.value = st.copy(isShuffle = true, isRepeat = false, queue = newQueue)
            preResolvedUrls.clear()
            preBufferNextTrack()
            notifyQueueChanged(newQueue)
        } else if (st.isShuffle && !st.isRepeat) {
            currentQueueIndex = st.originalQueue.indexOfFirst { it.id == st.currentTrack?.id }
            _state.value = st.copy(isShuffle = false, isRepeat = true, queue = st.originalQueue)
            preResolvedUrls.clear()
            preBufferNextTrack()
            notifyQueueChanged(st.originalQueue)
        } else {
            currentQueueIndex = st.originalQueue.indexOfFirst { it.id == st.currentTrack?.id }
            _state.value = st.copy(isShuffle = false, isRepeat = false, queue = st.originalQueue)
            preResolvedUrls.clear()
            preBufferNextTrack()
            notifyQueueChanged(st.originalQueue)
        }
        requestSave()
    }

    fun stopPlayerAndRelease() {
        playJob?.cancel()
        // Cancelling the job skips its `finally`, so free the resolution lock here rather than
        // waiting for its timeout.
        runCatching { if (resolutionWakeLock.isHeld) resolutionWakeLock.release() }
        exoPlayer.stop()
        _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
        saveNow()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val st = _state.value
        if (fromIndex !in st.queue.indices || toIndex !in st.queue.indices) return
        val list = st.queue.toMutableList()
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        // Keep the cached position pointing at the same track after the reorder.
        val cur = currentQueueIndex
        if (cur >= 0) {
            currentQueueIndex = when {
                fromIndex == cur -> toIndex
                fromIndex < cur && toIndex >= cur -> cur - 1
                fromIndex > cur && toIndex <= cur -> cur + 1
                else -> cur
            }
        }
        _state.value = st.copy(queue = list)
        preResolvedUrls.clear()
        preBufferNextTrack()
        notifyQueueChanged(list)
        requestSave()
    }

    fun removeFromQueue(index: Int) {
        val st = _state.value
        if (index !in st.queue.indices) return

        val removed = st.queue[index]
        val list = st.queue.toMutableList()
        list.removeAt(index)

        // Drop the matching entry from userQueue, otherwise a manually queued track that the user
        // swiped away came back the next time a list was loaded (loadPlaylist re-injects userQueue).
        // The queued block sits right after the current track — which is NOT index 0 except right
        // after loadAndPlay, so the old "queue starts at 0" math missed it inside a playlist. Match by
        // relative position first, then by id, so only one entry goes even if the track repeats.
        val queuedBlockStart = currentIndexIn(st) + 1
        synchronized(userQueue) {
            val rel = index - queuedBlockStart
            if (rel in userQueue.indices && userQueue[rel].id == removed.id) {
                userQueue.removeAt(rel)
            } else {
                val byId = userQueue.indexOfFirst { it.id == removed.id }
                if (byId >= 0) userQueue.removeAt(byId)
            }
        }

        // Removing an entry before the current track shifts it one position back.
        if (currentQueueIndex > index) currentQueueIndex--

        _state.value = st.copy(queue = list)
        preResolvedUrls.clear()
        preBufferNextTrack()
        notifyQueueChanged(list)
        requestSave()
    }

    fun play() {
        if (isWebServing) { com.varuna.rustify.webplayer.WebPlayerController.play(); return }
        val currentTrack = _state.value.currentTrack
        if (currentTrack != null) {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                playTrack(currentTrack)
            } else {
                // Manual resume also clears any stuck focus-duck multiplier, so pressing play reliably
                // restores sound if a track ever went silent-but-advancing.
                refreshAudioFocus()
                exoPlayer.play()
            }
        }
        requestSave()
    }

    // DJ voice ducking: lower/restore playback volume while the DJ speaks. The TTS callbacks arrive
    // off the main thread, so post to the player's main looper before touching ExoPlayer.
    private val djDuckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Track duck state so a fresh track never inherits a stuck 0.22 duck (which made a track play but
    // be inaudible if unduck never fired, e.g. a TTS error on a bad connection).
    @Volatile private var isDucking = false
    fun duckForVoice() { isDucking = true; djDuckHandler.post { runCatching { exoPlayer.volume = 0.22f } } }
    fun unduckFromVoice() { isDucking = false; djDuckHandler.post { runCatching { exoPlayer.volume = 1.0f } } }

    // Reset media3's internal AudioFocusManager by briefly disabling and re-enabling focus handling.
    // Disabling routes through ExoPlayerImplInternal.setAudioAttributesInternal, which does
    // `audioFocusManager.setAudioAttributes(handleAudioFocus ? attrs : null)` — i.e. it ABANDONS
    // Android audio focus — and re-enabling requests it again. That forces the internal focus volume
    // multiplier back to 1.0, which is the point: it clears the rare "stuck duck" where a transient
    // duck (a notification tone, the Assistant, another app) lowered the multiplier and the matching
    // focus-gain callback never arrived, leaving a track playing inaudibly while the position keeps
    // advancing. The app cannot otherwise touch that multiplier (exoPlayer.volume is only the base
    // layer: effective = base * focusMultiplier).
    //
    // ONLY called on manual resume. It used to run on every track as well, which meant an
    // abandon/re-request of audio focus at every transition — unusual enough that it is the prime
    // suspect for audio going silent-but-advancing when a Bluetooth device connects, since the route
    // switch and the focus churn can collide and leave the A2DP stream torn down (only toggling
    // Bluetooth rebuilds it; reconnecting the device does not). Keeping it on resume preserves the
    // escape hatch — the user presses play precisely because they hear nothing — without touching
    // focus when nobody asked. Skipped while the DJ is ducking so it does not fight the DJ volume.
    private fun refreshAudioFocus() {
        if (isDucking) return
        // Never while the audio route is still settling — see [audioRouteSettlingMs].
        if (System.currentTimeMillis() - lastAudioRouteChangeAt < audioRouteSettlingMs) {
            android.util.Log.d("AudioPlayerService", "Skipping focus refresh: audio route just changed")
            return
        }
        runCatching {
            exoPlayer.setAudioAttributes(mediaAudioAttributes, false)
            exoPlayer.setAudioAttributes(mediaAudioAttributes, true)
        }
    }

    /**
     * When an output device was last added or removed.
     *
     * The reported Bluetooth failure happens **only at the moment of connecting** (to a car, say) and
     * never again once the link is up and working. That timing is the whole clue: it is not the focus
     * reset on its own, it is the reset landing while Android is still moving audio onto the new
     * device. Pressing play in the app right after connecting — the obvious thing to do — is exactly
     * how the two meet.
     *
     * So the reset is withheld for a moment after any route change. Beyond that window it behaves
     * exactly as before, and nothing else in playback is affected.
     */
    @Volatile private var lastAudioRouteChangeAt = 0L

    private val audioRouteSettlingMs = 4_000L

    private val routeWatcherRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) {
            lastAudioRouteChangeAt = System.currentTimeMillis()
        }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) {
            lastAudioRouteChangeAt = System.currentTimeMillis()
        }
    }

    /**
     * Registered on first playback rather than in an init block: [audioManager] is a lazy declared
     * further down, so touching it during construction would dereference a delegate that does not
     * exist yet.
     */
    private fun ensureAudioRouteWatcher() {
        if (!routeWatcherRegistered.compareAndSet(false, true)) return
        runCatching {
            audioManager.registerAudioDeviceCallback(
                audioDeviceCallback,
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        }.onFailure { routeWatcherRegistered.set(false) }
    }

    // AudioManager.mode reflects an active/ringing call WITHOUT needing READ_PHONE_STATE. Used to
    // avoid blasting audio over a hands-free call when a track finishes resolving mid-call.
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    private fun isInCall(): Boolean = runCatching {
        when (audioManager.mode) {
            android.media.AudioManager.MODE_IN_CALL,
            android.media.AudioManager.MODE_IN_COMMUNICATION,
            android.media.AudioManager.MODE_RINGTONE -> true
            else -> false
        }
    }.getOrDefault(false)

    fun pause() {
        if (isWebServing) { com.varuna.rustify.webplayer.WebPlayerController.pause(); return }
        exoPlayer.pause()
        requestSave()
    }

    fun togglePlayPause() {
        if (isWebServing) { com.varuna.rustify.webplayer.WebPlayerController.togglePlayPause(); return }
        val currentTrack = _state.value.currentTrack ?: return
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                playTrack(currentTrack)
            } else {
                // Manual resume also clears any stuck focus-duck multiplier, so pressing play reliably
                // restores sound if a track ever went silent-but-advancing.
                refreshAudioFocus()
                exoPlayer.play()
            }
        }
        requestSave()
    }

    fun seekTo(positionMs: Long) {
        if (isWebServing) {
            com.varuna.rustify.webplayer.WebPlayerController.seekTo(positionMs)
            _state.value = _state.value.copy(positionMs = positionMs)
            return
        }
        exoPlayer.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
        requestSave()
    }

    /**
     * Moves the queue to [decision] and starts playing there.
     *
     * The one place a decision from [PlayerQueue] becomes playback, so the mapping from effects to
     * ExoPlayer exists once rather than in each of the three callers.
     */
    private fun applyQueueDecision(st: AudioPlayerState, decision: PlayerQueue.Decision) {
        decision.seekTo?.let {
            seekTo(it)
            return
        }
        if (!decision.play) return
        val target = st.queue.getOrNull(decision.index) ?: return
        currentQueueIndex = decision.index
        _state.value = _state.value.copy(
            currentTrack = target,
            isPlaying = false,
            positionMs = decision.positionMs,
            durationMs = target.durationMs.toLong()
        )
        startTrack(target)
        if (decision.persist) requestSave()
    }

    /**
     * A track finished on its own.
     *
     * Deliberately **not** `skipToNext()`. The two look the same and are not: repeat-one means "play
     * this again when it ends", so ending replays and a person pressing next still moves on. That
     * distinction is why the core has two separate actions, and reading it off one boolean here is
     * what let the two implementations drift.
     */
    private fun onTrackEnded() {
        val st = _state.value
        val idx = currentIndexIn(st)
        if (idx < 0 || st.queue.isEmpty()) return
        // Reaching the end of a track is what "listened" means, and it is the only moment that can
        // say so honestly — a skip at forty seconds is not having heard it. Recorded here rather
        // than on a timer for exactly that reason.
        markCurrentAsListened(st)
        val decision = PlayerQueue.decide(
            ids = PlayerQueue.idsFor(st.queue.map { it.id }),
            index = idx,
            positionMs = st.positionMs,
            repeatOne = st.isRepeat,
            shuffle = st.isShuffle,
            action = PlayerQueue.TRACK_ENDED
        )
        // Repeat-one on the track that just ended: the core answers "play this one, from zero", and
        // the same track reloading is not what anyone wants when it is already loaded.
        if (decision.play && decision.index == idx && decision.positionMs == 0L) {
            seekTo(0L)
            play()
            return
        }
        if (decision.exhausted) {
            // Same end-of-queue answer as the next button: on Android this is the radio, not a stop.
            skipToNext()
            return
        }
        applyQueueDecision(st, decision)
    }

    fun skipToNext() {
        // Not delegated to the page: opening a track URL leaves Spotify's own context holding that
        // single track, so its next button has nowhere to go. Rustify's queue is the one that
        // advances, and the next track is then opened in the page below.
        val st = _state.value
        // Flush the outgoing track's real position, but only if the player actually holds it (after a
        // failed resolution it still reports the previous track's clock).
        if (preparedTrackId == st.currentTrack?.id) {
            _state.value = st.copy(positionMs = exoPlayer.currentPosition)
        }
        val idx = currentIndexIn(st)
        if (idx == -1 && st.queue.isNotEmpty()) {
            // The current track isn't in the queue (e.g. a restored/truncated originalQueue).
            // Don't get stuck — advance to the first queued track instead of doing nothing. This
            // one stays here rather than going through the core: the core's queue and this one have
            // already diverged, so there is no shared state to reason from.
            val next = st.queue.first()
            currentQueueIndex = 0
            _state.value = _state.value.copy(
                currentTrack = next, isPlaying = false,
                positionMs = 0L, durationMs = next.durationMs.toLong()
            )
            startTrack(next)
            requestSave()
            return
        }
        // What "next" means — with repeat on, and at the end of the queue — is decided in the core.
        val decision = PlayerQueue.decide(
            ids = PlayerQueue.idsFor(st.queue.map { it.id }),
            index = idx,
            positionMs = _state.value.positionMs,
            repeatOne = st.isRepeat,
            shuffle = st.isShuffle,
            action = PlayerQueue.NEXT
        )
        if (!decision.exhausted) {
            applyQueueDecision(st, decision)
            return
        }
        if (st.currentTrack != null) {
            // Autoplay radio. This is what `QueueExhausted` exists to name: the core says "there is
            // nothing after this", and on Android that is not a stop — it is where the radio starts.
            //
            // Reaching here at all means the prefetch below did not land in time (no network while
            // the last track played, or the user skipped straight to the end). It still works; it is
            // just the slow path, because the round trip happens with nothing playing.
            radioJob?.cancel()
            radioJob = mainScope.launch {
                if (appendRadioTracks(st.currentTrack.id)) skipToNext()
            }
        }
    }

    /**
     * Fetches the autoplay radio for [seedTrackId] and appends what is new to the queue.
     *
     * @return whether anything was actually added.
     */
    private suspend fun appendRadioTracks(seedTrackId: String?): Boolean {
        val seed = seedTrackId ?: return false
        if (TrackRef.isLocal(seed)) return false
        val fetched = runCatching {
            withContext(Dispatchers.IO) {
                val array = org.json.JSONArray(NativeEngine.getSpotifyTrackRadio(seed))
                (0 until array.length()).map { FullTrack.fromJson(array.getJSONObject(it)) }
            }
        }.getOrElse {
            android.util.Log.e("AudioPlayerService", "Error loading radio tracks", it)
            return false
        }
        if (fetched.isEmpty()) return false

        // Re-read the state here: fetching the radio is a network round trip, and appending to a
        // snapshot taken before it would silently discard anything queued (or removed) while it was
        // in flight.
        val live = _state.value
        val liveIds = live.queue.mapNotNull { it.id }.toSet()
        val fresh = fetched.filter { it.id != null && it.id !in liveIds }
        if (fresh.isEmpty()) return false

        val newQueue = live.queue + fresh
        _state.value = live.copy(queue = newQueue, originalQueue = live.originalQueue + fresh)
        notifyQueueChanged(newQueue)
        requestSave()
        return true
    }

    /**
     * Extends the queue with the radio **while the last track is still playing**.
     *
     * The gap this closes was the longest silence in the app: the radio used to be fetched only once
     * the queue had already run out, so the wait was a network round trip *plus* a full resolution of
     * its first song, with nothing playing through either. Doing it here turns the end of a queue
     * into an ordinary track change — by the time the last song ends there is a next one, already
     * pre-buffered by the same pass that would have pre-buffered any other.
     *
     * Skipped when repeat-one is on: there the last track does not end the queue, it replays.
     */
    private fun prefetchRadioForEndOfQueue(st: AudioPlayerState) {
        if (st.isRepeat) return
        val seed = st.currentTrack?.id ?: return
        if (TrackRef.isLocal(seed)) return
        if (radioSeedInFlight == seed) return
        radioSeedInFlight = seed
        radioJob?.cancel()
        radioJob = mainScope.launch {
            val added = appendRadioTracks(seed)
            // Pre-buffer the first radio track the same way any next track is pre-buffered. Without
            // this the queue no longer stalls but the first radio song still resolves from cold.
            if (added) {
                preBufferNextTrack()
            } else {
                // Nothing was added — no network, or the radio had nothing new. Forget the seed so
                // the next pass can try again; leaving it set would mean one failed fetch costs the
                // radio for the rest of that track.
                radioSeedInFlight = null
            }
        }
    }

    fun skipToPrevious() {
        // Same reasoning as skipToNext(): Rustify's queue navigates, the page just gets told what to
        // open. seekTo(0) below is already routed to whichever engine is playing.
        val st = _state.value
        val idx = currentIndexIn(st)
        if (idx < 0 || st.queue.isEmpty()) return
        // Spotify-style "previous" — restart once you are past the opening seconds, go back a track
        // within it — is decided in the core, along with the threshold itself. What stays here is the
        // one thing the core cannot know: which clock is live. Only trust the player's own position
        // when the player really holds this track; after a failed resolution it still reports the
        // previous one's, and in web mode it holds nothing at all.
        val livePos = when {
            isWebServing -> st.positionMs.coerceAtLeast(0L)
            preparedTrackId == st.currentTrack?.id -> exoPlayer.currentPosition.coerceAtLeast(0L)
            else -> 0L
        }
        val decision = PlayerQueue.decide(
            ids = PlayerQueue.idsFor(st.queue.map { it.id }),
            index = idx,
            positionMs = livePos,
            repeatOne = st.isRepeat,
            shuffle = st.isShuffle,
            action = PlayerQueue.PREVIOUS
        )
        applyQueueDecision(st, decision)
    }

    fun retryCurrentTrack(youtubeId: String? = null, fallbackTrackId: String? = null, isAutoRetry: Boolean = false) {
        val st = _state.value
        val track = if (fallbackTrackId != null) {
            st.queue.find { it.id == fallbackTrackId } ?: st.currentTrack
        } else {
            st.currentTrack
        } ?: return

        // Cancel any pending auto-retry to prevent it from firing later on a different track
        autoRetryJob?.cancel()
        autoRetryJob = null
        // Preserve the retry counter across auto-retries; only reset on a user-initiated retry.
        if (!isAutoRetry) track.id?.let { retryCountMap.remove(it) }

        // Force full re-resolution: clear ALL cached URLs for this track (incl. the persisted one).
        track.id?.let {
            // If it was playing from the stream cache, that file is what just failed — drop it, or
            // a corrupt entry would be served again on every retry and nothing short of emptying the
            // whole cache could clear it. A retry of a network stream leaves the cache alone.
            if (resolvedStreamUrls[it]?.startsWith(LOOPBACK_PREFIX) == true) {
                com.varuna.rustify.audio.StreamRouting.forget(context, it)
            }
            resolvedStreamUrls.remove(it)
            preResolvedUrls.remove(it)
            removeCachedStreamUrl(it)
            // Forced re-resolution → don't trust the cached provider as "good".
            com.varuna.rustify.audio.AudioSourceRegistry.invalidateLastGood(it)
        }

        // Whenever we re-resolve the SAME track — an explicit alternative pick, an auto-retry after an
        // extraction/network failure, an expired-URL refresh, or a network-reconnect retry — we must
        // resume from where the user was, not restart from 0. playTrack() seeks to _state.positionMs
        // when > 0, so we compute the best-known position here. Only a switch to a DIFFERENT track
        // (a fallbackTrackId that isn't the current track) legitimately starts from 0.
        val retryingSameTrack = fallbackTrackId == null || fallbackTrackId == st.currentTrack?.id
        // Only read the player's clock if it really holds this track. When a track change is followed
        // by a failed resolution (scheduleExtractionRetry), setMediaSource/prepare never ran, so the
        // player still sits at the PREVIOUS track's end position — using it seeked the fresh track to
        // that offset and it started mid-song.
        val livePos = if (preparedTrackId == track.id) exoPlayer.currentPosition.coerceAtLeast(0L) else 0L
        val preservedPosition = if (retryingSameTrack) maxOf(livePos, st.positionMs).coerceAtLeast(0L) else 0L

        _state.value = st.copy(
            currentTrack = track,
            positionMs = preservedPosition,
            isPlaying = false,
            isError = false,
            errorMessage = ""
        )
        playTrack(track, youtubeId, isAutoRetry = isAutoRetry)
    }

    /**
     * Play a track already in the queue. [queueIndex] disambiguates when the same id appears more
     * than once (tapping the second copy of a repeated song must play *that* row, not the first).
     */
    fun playSpecificTrackInQueue(trackId: String, youtubeId: String? = null, queueIndex: Int? = null) {
        val st = _state.value
        val idx = queueIndex?.takeIf { it in st.queue.indices && st.queue[it].id == trackId }
            ?: st.queue.indexOfFirst { it.id == trackId }
        val track = st.queue.getOrNull(idx)
        if (track != null) {
            currentQueueIndex = idx
            // Tapping a row in the queue (or picking one from Android Auto) has to honour web mode
            // too, otherwise it would start a second audio source alongside the page. The fallback
            // keeps [youtubeId], which is how an explicitly chosen alternative reaches the engine.
            val playHere = {
                _state.value = _state.value.copy(
                    currentTrack = track,
                    isPlaying = false,
                    isBuffering = true,
                    positionMs = 0L,
                    durationMs = track.durationMs.toLong()
                )
                playTrack(track, youtubeId)
                requestSave()
            }
            if (webPlayerMode && playInWebPlayer(track, playHere)) {
                requestSave()
                return
            }
            _state.value = st.copy(
                currentTrack = track,
                isPlaying = false,
                positionMs = 0L,
                durationMs = track.durationMs.toLong()
            )
            playTrack(track, youtubeId)
            requestSave()
        } else {
            // Should not normally happen; no-op fallback just in case.
        }
    }

    /**
     * First index in [list] past the block of manually queued tracks that follows [currentIdx].
     *
     * There are two queues: the one playback generated (a list, or a shuffle of it) and the one the
     * user built by hand, which always sits between the current track and the rest. New additions go
     * at the end of that block — the manual queue is first-in, first-out — so the block's extent has
     * to be found before anything is inserted.
     *
     * The walk runs from the current track while each row is still owed by [pending], matching
     * against it as a multiset rather than position by position. A strict lockstep looked equivalent
     * and was not: it compared `list[i]` with `pending[p]` and gave up at the first difference, so a
     * single entry out of place — one the user dragged, one left behind by a jump — answered "there
     * is no manual block". Every later addition then landed immediately after the current track, in
     * front of everything queued before it, and the manual queue played newest-first.
     *
     * Membership is only meaningful while [pending] describes the live queue, which is what
     * [reconcileUserQueue] is for.
     */
    private fun manualBlockEnd(list: List<FullTrack>, currentIdx: Int, pending: List<FullTrack>): Int {
        val owed = pending.mapNotNull { it.id }.toMutableList()
        var i = currentIdx + 1
        while (i < list.size && owed.isNotEmpty()) {
            val id = list[i].id ?: break
            // remove() takes out one occurrence, so a song queued twice extends the block twice.
            if (!owed.remove(id)) break
            i++
        }
        return i
    }

    /**
     * Drops from [userQueue] every track that is no longer waiting to be played.
     *
     * The manual queue is a projection of the live one: it names the tracks sitting between the
     * current track and the rest, in the order they were added. Starting a track consumes its entry,
     * but a jump does not — tapping a row past the manual block, or an entry removed with the queue
     * rebuilt underneath it, leaves the name behind with nothing to point at.
     *
     * Stale entries are not inert. They break [manualBlockEnd]'s match, which turns the manual queue
     * newest-first, and [loadPlaylist] injects them into every list started afterwards, so songs
     * queued days ago reappear ahead of a playlist that was just told to shuffle. Reconciling against
     * the live queue before each use keeps both honest whatever caused the drift.
     */
    private fun reconcileUserQueue(st: AudioPlayerState) {
        // With no live queue there is nothing to check against: a restore that brought the manual
        // queue back before the queue itself would otherwise read as "none of it is pending".
        if (st.queue.isEmpty()) return
        synchronized(userQueue) {
            if (userQueue.isEmpty()) return
            val curIdx = currentIndexIn(st)
            val ahead = if (curIdx >= 0) st.queue.drop(curIdx + 1) else st.queue
            val pendingIds = ahead.mapNotNull { it.id }.toMutableList()
            userQueue.retainAll { t ->
                val id = t.id ?: return@retainAll false
                pendingIds.remove(id)
            }
        }
    }

    /**
     * A manually queued track is spent once it starts, whichever engine started it.
     *
     * [playTrack] used to be the only place this happened, which was right until the web player
     * became a first-class engine: a track the page accepts never reaches [playTrack], so in web
     * mode the manual queue was never emptied. It grew without bound, and both of its failure modes
     * followed — see [reconcileUserQueue].
     */
    private fun consumeFromUserQueue(trackId: String?) {
        val id = trackId ?: return
        synchronized(userQueue) { userQueue.removeAll { it.id == id } }
    }

    /**
     * Insert [trackToInsert] after the current track and the manual queue that follows it, so manual
     * additions play in the order they were made. [pending] is the manual queue as it stands
     * *before* this insertion. [knownCurrentIdx] is used when the caller knows the exact position of
     * the current track in [list] (the live queue), since an id lookup would land on the wrong copy
     * when the queue repeats a song.
     */
    private fun insertTrackAfterUserQueue(
        list: List<FullTrack>,
        currentTrack: FullTrack?,
        trackToInsert: FullTrack,
        pending: List<FullTrack>,
        knownCurrentIdx: Int? = null
    ): List<FullTrack> {
        val currentIdx = knownCurrentIdx?.takeIf { it in list.indices && list[it].id == currentTrack?.id }
            ?: list.indexOfFirst { it.id == currentTrack?.id }
        if (currentIdx == -1) return list + trackToInsert

        val targetIdx = manualBlockEnd(list, currentIdx, pending)
        val result = list.toMutableList()
        if (targetIdx <= result.size) {
            result.add(targetIdx, trackToInsert)
        } else {
            result.add(trackToInsert)
        }
        return result
    }

    fun enqueue(track: FullTrack) {
        val st = _state.value
        reconcileUserQueue(st)
        // Snapshot before adding: the insertion point is "after what was already queued by hand".
        val pending = synchronized(userQueue) { userQueue.toList() }
        val curIdx = currentIndexIn(st)
        val q = insertTrackAfterUserQueue(st.queue, st.currentTrack, track, pending, curIdx)
        val orig = insertTrackAfterUserQueue(st.originalQueue, st.currentTrack, track, pending)
        synchronized(userQueue) { userQueue.add(track) }
        _state.value = st.copy(queue = q, originalQueue = orig)
        preBufferNextTrack()
        notifyQueueChanged(q)
        requestSave()
    }

    fun enqueueAll(tracks: List<FullTrack>) {
        val st = _state.value
        reconcileUserQueue(st)
        var currentQueue = st.queue
        var currentOrig = st.originalQueue
        val currentTrack = st.currentTrack
        val curIdx = currentIndexIn(st)
        // Grows as we go, so each track lands behind the one added just before it.
        val pending = synchronized(userQueue) { userQueue.toMutableList() }
        tracks.forEach { track ->
            currentQueue = insertTrackAfterUserQueue(currentQueue, currentTrack, track, pending, curIdx)
            currentOrig = insertTrackAfterUserQueue(currentOrig, currentTrack, track, pending)
            pending.add(track)
        }
        synchronized(userQueue) { userQueue.addAll(tracks) }
        _state.value = st.copy(queue = currentQueue, originalQueue = currentOrig)
        preBufferNextTrack()
        notifyQueueChanged(currentQueue)
        requestSave()
    }

    /**
     * Replaces everything after the current track with [tracks] (without touching the track playing
     * now). Intended for the autonomous DJ when it changes mood: it discards the previous block that
     * has not yet played and places the new block right after the current track, instead of stacking
     * it on top of the previous one (which `enqueueAll` did, leaving old songs behind). Clears
     * `userQueue` because, in DJ mode, those items were exactly the automatically enqueued segments
     * (not a manual user queue).
     */
    fun replaceAutoQueueAfterCurrent(tracks: List<FullTrack>) {
        if (tracks.isEmpty()) return
        val st = _state.value
        val current = st.currentTrack
        val currentIdx = currentIndexIn(st)
        val head = if (currentIdx >= 0) st.queue.subList(0, currentIdx + 1).toList() else st.queue.toList()
        val newQueue = head + tracks
        synchronized(userQueue) { userQueue.clear() }
        preResolvedUrls.clear()
        _state.value = st.copy(queue = newQueue, originalQueue = newQueue)
        preBufferNextTrack()
        notifyQueueChanged(newQueue)
        requestSave()
    }

    fun release() {
        // Persist the latest state synchronously before tearing down.
        saveNow()
        listenerTracker.flush()
        unregisterNetworkCallback()
        val stopIntent = Intent(context, RustifyForegroundService::class.java).apply {
            action = "STOP_SERVICE"
        }
        context.startService(stopIntent)
        mediaControllerFuture?.cancel(true)
        mediaControllerFuture = null
        exoPlayer.release()
        exoPlayerInstance = null
        instance = null
    }

    // -------------------------------------------------------------------
    // Stream URL cache persistence (avoid re-resolving ephemeral URLs)
    // -------------------------------------------------------------------

    private fun getCachedStreamUrl(trackId: String): String? {
        return persistedUrlCache[trackId]
    }

    // Invalidate the persisted stream URL for a track so a forced re-resolution can't reuse the
    // stale URL. Covers both the in-memory map (backed by stream_url_cache.json) and the legacy
    // SharedPreferences "cached_url_$id" entry.
    fun removeCachedStreamUrl(trackId: String) {
        persistedUrlCache.remove(trackId)
        urlExpiryCache.remove(trackId)   // drop the tracked expiry alongside the URL
        context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
            .edit().remove("cached_url_$trackId").apply()
        mainScope.launch(Dispatchers.IO) { saveUrlCache() }
    }

    private fun putCachedStreamUrl(trackId: String, url: String) {
        persistedUrlCache[trackId] = url
        // Cap the size to prevent infinite growth
        if (persistedUrlCache.size > 200) {
            val keysToRemove = persistedUrlCache.keys().toList().take(50)
            for (key in keysToRemove) {
                persistedUrlCache.remove(key)
                urlExpiryCache.remove(key)   // keep expiry map in lockstep with the URL cache
            }
        }
        // Save async to disk
        mainScope.launch(Dispatchers.IO) {
            saveUrlCache()
        }
    }

    private fun loadUrlCache() {
        try {
            val file = java.io.File(context.filesDir, "stream_url_cache.json")
            if (file.exists()) {
                val json = org.json.JSONObject(file.readText())
                val timestamp = json.optLong("savedAt", 0L)
                // Discard URL cache older than 6 hours — YouTube stream URLs expire
                val cacheAge = System.currentTimeMillis() - timestamp
                if (timestamp > 0 && cacheAge > maxUrlCacheAge) {
                    android.util.Log.d("AudioPlayerService", "Discarding expired URL cache (>6h old)")
                    file.delete()
                    return
                }
                val urls = json.optJSONObject("urls")
                if (urls != null) {
                    val keys = urls.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        persistedUrlCache[key] = urls.getString(key)
                    }
                }
                android.util.Log.d("AudioPlayerService", "Loaded ${persistedUrlCache.size} cached stream URLs")
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayerService", "Error loading URL cache", e)
        }
    }

    private fun saveUrlCache() {
        try {
            val file = java.io.File(context.filesDir, "stream_url_cache.json")
            val json = org.json.JSONObject()
            json.put("savedAt", System.currentTimeMillis())
            val urls = org.json.JSONObject()
            for ((key, value) in persistedUrlCache) {
                urls.put(key, value)
            }
            json.put("urls", urls)
            file.writeText(json.toString())
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayerService", "Error saving URL cache", e)
        }
    }

    // -------------------------------------------------------------------
    // Playback state persistence (atomic, debounced, complete)
    // -------------------------------------------------------------------

    /** Enqueue a debounced state save. Cheap to call from any playback event. */
    internal fun requestSave() {
        val st = _state.value
        if (st.isError || isResolving) return
        saveRequests.trySend(Unit)
    }

    /** Synchronous atomic write — use on shutdown paths where the async queue may not flush. */
    internal fun saveNow() {
        writeStateAtomically(_state.value)
    }

    private fun writeStateAtomically(st: AudioPlayerState) {
        try {
            // Never persist error state — it contaminates the next session
            if (st.isError || isResolving) return
            val dir = context.filesDir
            val tmp = java.io.File(dir, "playback_state.json.tmp")
            val dst = java.io.File(dir, "playback_state.json")
            val json = org.json.JSONObject().apply {
                st.currentTrack?.let { put("currentTrack", it.toJson()) }

                val qArr = org.json.JSONArray()
                st.queue.forEach { qArr.put(it.toJson()) }
                put("queue", qArr)

                val origArr = org.json.JSONArray()
                st.originalQueue.forEach { origArr.put(it.toJson()) }
                put("originalQueue", origArr)

                val uqArr = org.json.JSONArray()
                synchronized(userQueue) {
                    userQueue.forEach { uqArr.put(it.toJson()) }
                }
                put("userQueue", uqArr)

                put("positionMs", st.positionMs)
                put("durationMs", st.durationMs)
                put("isShuffle", st.isShuffle)
                put("isRepeat", st.isRepeat)
                put("wasPlaying", st.isPlaying)
                put("schemaVersion", 2)
                put("lastSavedTimestamp", System.currentTimeMillis())
            }
            // Atomic write: tmp + rename — never a half-written file.
            tmp.writeText(json.toString())
            if (!tmp.renameTo(dst)) {
                dst.writeText(json.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayerService", "Error saving state", e)
        }
    }

    private fun loadState() {
        try {
            val file = java.io.File(context.filesDir, "playback_state.json")
            if (file.exists()) {
                val json = org.json.JSONObject(file.readText())

                // Discard state older than 24 hours — stale URLs and positions
                val lastSaved = json.optLong("lastSavedTimestamp", 0L)
                val maxAge = 24 * 60 * 60 * 1000L  // 24 hours
                if (lastSaved > 0 && System.currentTimeMillis() - lastSaved > maxAge) {
                    android.util.Log.d("AudioPlayerService", "Discarding stale saved state (>24h old)")
                    file.delete()
                    return
                }

                val trackObj = json.optJSONObject("currentTrack")
                val track = if (trackObj != null) FullTrack.fromJson(trackObj) else null

                val qArr = json.optJSONArray("queue")
                val queue = FullTrack.listFromJsonArray(qArr)

                val origArr = json.optJSONArray("originalQueue")
                val origQueue = FullTrack.listFromJsonArray(origArr)

                val uqArr = json.optJSONArray("userQueue")
                val uqList = FullTrack.listFromJsonArray(uqArr)
                synchronized(userQueue) {
                    userQueue.clear()
                    userQueue.addAll(uqList)
                }

                // Restored state has no live index; recompute it from the saved current track.
                currentQueueIndex = queue.indexOfFirst { it.id == track?.id }

                val positionMs = json.optLong("positionMs", 0L)
                val durationMs = json.optLong("durationMs", 0L)
                val isShuffle = json.optBoolean("isShuffle", false)
                val isRepeat = json.optBoolean("isRepeat", false)   // restore repeat mode

                _state.value = _state.value.copy(
                    currentTrack = track,
                    originalQueue = origQueue,
                    queue = queue,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isShuffle = isShuffle,
                    isRepeat = isRepeat,
                    isPlaying = false,
                    isBuffering = false
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayerService", "Error loading state", e)
        }
    }

    fun switchToVideoStream(videoUrl: String, audioUrl: String? = null) {
        mainScope.launch {
            val pos = exoPlayer.currentPosition
            val isPlaying = exoPlayer.playWhenReady
            val currentItem = exoPlayer.currentMediaItem ?: return@launch
            val videoItem = currentItem.buildUpon().setUri(videoUrl).build()

            val mediaSource = if (audioUrl != null) {
                val audioItem = currentItem.buildUpon().setUri(audioUrl).build()
                val videoSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(videoItem)
                val audioSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(audioItem)
                androidx.media3.exoplayer.source.MergingMediaSource(videoSource, audioSource)
            } else {
                DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(videoItem)
            }
            
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.seekTo(pos)
            exoPlayer.playWhenReady = isPlaying
            _state.value = _state.value.copy(isVideoMode = true)
        }
    }

    fun switchToAudioStream() {
        mainScope.launch {
            val trackId = _state.value.currentTrack?.id ?: return@launch
            val audioUrl = resolvedStreamUrls[trackId] ?: return@launch
            val pos = exoPlayer.currentPosition
            val isPlaying = exoPlayer.playWhenReady

            val currentItem = exoPlayer.currentMediaItem ?: return@launch
            val item = currentItem.buildUpon().setUri(audioUrl).build()

            // Restore audio cache
            val isLocalStream = TrackRef.isLocal(trackId) || audioUrl.startsWith("content://") || audioUrl.startsWith("file://")
            val mediaSource = if (isLocalStream) {
                DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context))
                    .createMediaSource(item)
            } else {
                DefaultMediaSourceFactory(cacheDataSourceFactory)
                    .createMediaSource(item)
            }
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.seekTo(pos)
            exoPlayer.playWhenReady = isPlaying
            _state.value = _state.value.copy(isVideoMode = false)
        }
    }
}