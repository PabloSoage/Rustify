@file:Suppress("SpellCheckingInspection")
@file:SuppressLint("StaticFieldLeak", "UseKtx")

package com.varuna.rustify.player

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
        /** Past this point into a track, "previous" restarts it instead of going back a track. */
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 10_000L

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
        NativeEngine.initCacheDirNative(cachePath)

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
                        if (_state.value.isRepeat) {
                            seekTo(0L)
                            play()
                        } else {
                            skipToNext()
                        }
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

    private fun playTrack(track: FullTrack, youtubeId: String? = null, isAutoRetry: Boolean = false) {
        val trackId = track.id ?: return
        // A queued track is consumed once it actually starts playing.
        synchronized(userQueue) { userQueue.removeAll { it.id == trackId } }

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
        if (!trackId.startsWith("local:") && !trackId.startsWith("ytm:")) {
            val artistsJson = "[" + track.artists.joinToString(",") {
                "\"" + it.name.replace("\"", "\\\"") + "\""
            } + "]"
            NativeEngine.registerTrackMetadataNative(
                trackId, track.name, artistsJson, track.durationMs, track.isrc
            )
        }

        // Preload lyrics asynchronously so they're cached when user opens TrackScreen.
        // Skip lyrics for ytm: (no Spotify track id).
        if (!trackId.startsWith("ytm:")) { preloadLyrics(track) }

        // Show buffering spinner immediately (resolver can take a few seconds).
        // Extract videoId from the ytm: prefix (bypasses the Spotify resolver).
        var effectiveYoutubeId = youtubeId
        if (trackId.startsWith("ytm:")) { effectiveYoutubeId = trackId.removePrefix("ytm:") }

        val myGen = resolveGen.incrementAndGet()
        isResolving = true
        _state.value = _state.value.copy(isBuffering = true, isError = false, errorMessage = "")

        playJob?.cancel()
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

                if (trackId.startsWith("local:")) {
                    streamUrl = trackId.removePrefix("local:")
                    android.util.Log.d("AudioPlayerService", "Playing local track: $streamUrl")
                } else {
                    // Match local first logic
                    val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
                    val matchLocalFirst = prefs.getBoolean("settings_match_local_first", false)
                    val localMusicDirs = prefs.getStringSet("local_music_directories", emptySet()) ?: emptySet()

                    if (matchLocalFirst && localMusicDirs.isNotEmpty()) {
                        // An explicit YouTube alternative — a just-picked hint or a user-confirmed
                        // persisted mapping — must win over the local match. Otherwise picking a
                        // YouTube alternative for a locally-matched track silently does nothing (the
                        // local file keeps playing). Only an alternative genuinely chosen by the user
                        // (an in-flight hint or one marked in UserAlternatives) wins over the local
                        // match; an auto-persisted mapping does not count, so the local match wins again.
                        val hasUserAlternative = !effectiveYoutubeId.isNullOrBlank() ||
                            com.varuna.rustify.bridge.UserAlternatives.isUserSet(context, trackId)
                        if (!hasUserAlternative) {
                            val match = com.varuna.rustify.bridge.SpotifyRepository.findLocalMatch(context, track)
                            if (match != null) {
                                streamUrl = match.id?.removePrefix("local:")
                                android.util.Log.d("AudioPlayerService", "Matched Spotify track to local file: $streamUrl")
                            }
                        } else {
                            android.util.Log.d("AudioPlayerService", "User YouTube alternative present for $trackId — skipping local match")
                        }
                    }

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
                        } else {
                            // Persisted URL cache — avoids an unnecessary yt-dlp resolution.
                            val cachedUrl = getCachedStreamUrl(trackId)
                            if (!cachedUrl.isNullOrBlank()) {
                                streamUrl = cachedUrl
                                android.util.Log.d("AudioPlayerService", "Using persisted cached URL for $trackId")
                            }
                        }
                    }
                    if (streamUrl == null) {
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

                if (streamUrl.isNullOrBlank()) {
                    // Auto-retry the extraction failure instead of leaving the user stuck.
                    scheduleExtractionRetry(track, effectiveYoutubeId)
                    return@launch
                }


                val mediaItem = MediaItem.Builder()
                    .setUri(streamUrl)
                    .setMediaMetadata(metadata)
                    .apply { setCustomCacheKey(if (effectiveYoutubeId.isNullOrBlank()) trackId else "${trackId}_${effectiveYoutubeId}") }
                    .build()

                resolvedStreamUrls[trackId] = streamUrl
                // Persist URL for next session (skip yt-dlp), but NOT for local files
                val isLocalStream = trackId.startsWith("local:") || streamUrl.startsWith("content://") || streamUrl.startsWith("file://")
                // Deezer serves a deezer://<sngId>?u=<b64> URI decrypted on the fly by a custom
                // DataSource. It is not cacheable (the CDN URL expires and carries a per-track key) and
                // does not go through the HTTP cache.
                val isDeezerStream = streamUrl.startsWith("deezer://")
                // Expose to the UI whether the current track plays from a local file (for the green button).
                if (_state.value.currentTrack?.id == track.id) {
                    _state.value = _state.value.copy(isLocalSource = isLocalStream)
                }
                if (!trackId.startsWith("local:") && !isLocalStream && !isDeezerStream) {
                    putCachedStreamUrl(trackId, streamUrl)
                }

                val mediaSource = when {
                    isDeezerStream -> {
                        android.util.Log.d("AudioPlayerService", "Creating Deezer decrypting media source")
                        DefaultMediaSourceFactory(com.varuna.rustify.audio.DeezerDecryptingDataSource.Factory())
                            .createMediaSource(mediaItem)
                    }
                    isLocalStream -> {
                        android.util.Log.d("AudioPlayerService", "Creating DefaultMediaSource for local track")
                        DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context))
                            .createMediaSource(mediaItem)
                    }
                    else -> {
                        android.util.Log.d("AudioPlayerService", "yt-dlp Extracted direct stream: ${streamUrl.take(80)}...")
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
                    // Start the fresh track with a clean focus multiplier so it can never inherit a stuck
                    // duck from the previous track's focus churn (silent-but-advancing bug). Track is not
                    // audible yet, so this is inaudible.
                    refreshAudioFocus()
                    exoPlayer.play()
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
            }
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

    private fun preBufferNextTrack() {
        val st = _state.value
        val idx = currentIndexIn(st)
        if (idx != -1 && idx < st.queue.lastIndex) {
            val nextTrack = st.queue[idx + 1]
            val nextTrackId = nextTrack.id ?: return
            if (preResolvedUrls.containsKey(nextTrackId)) {
                return
            }
            preBufferingJob?.cancel()
            preBufferingJob = mainScope.launch {
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
                    android.util.Log.d("AudioPlayerService", "Successfully pre-buffered: ${nextTrack.name}")
                }
                res.onFailure { e ->
                    android.util.Log.w("AudioPlayerService", "Pre-buffer chain failed for ${nextTrack.name}: ${e.message}")
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
        if (trackId.startsWith("local:")) return
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
        if (id.startsWith("local:") || id.startsWith("ytm:")) return false
        if (webConsecutiveFailures >= webFailuresBeforeDisarm) return false
        val controller = com.varuna.rustify.webplayer.WebPlayerController
        // Creating a WebView is a main-thread operation; from anywhere else, decline rather than
        // crash — the normal engine covers the track.
        if (!controller.isReady) {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return false
            runCatching { controller.getOrCreate(context) }
            if (!controller.isReady) return false
        }

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
        // Never re-inject the track being played as a queued duplicate of itself.
        val queue = listOf(track) + synchronized(userQueue) { userQueue.filter { it.id != track.id } }
        currentQueueIndex = 0
        _state.value = _state.value.copy(
            currentTrack = track,
            isPlaying = false,
            queue = queue,
            // Include userQueue so cycling shuffle/repeat (which restores originalQueue) doesn't
            // silently drop the manually-queued tracks.
            originalQueue = queue,
            positionMs = 0L,
            durationMs = track.durationMs.toLong()
        )
        preResolvedUrls.clear()
        // The queue above is built either way, so "Next Up" is correct in web mode too.
        startTrack(track)
        notifyQueueChanged(queue)
        requestSave()
    }

    fun loadPlaylist(tracks: List<FullTrack>, initialIndex: Int = 0) {
        if (tracks.isEmpty()) return
        // Foreground bind happens in playTrack() right before playback (see loadAndPlay note).
        val idx = initialIndex.coerceIn(0, tracks.lastIndex)
        val selected = tracks[idx]

        val baseQueue = if (_state.value.isShuffle) {
            val remaining = tracks.filterIndexed { i, _ -> i != idx }.shuffled()
            listOf(selected) + remaining
        } else {
            tracks
        }

        val selectedIdx = baseQueue.indexOfFirst { it.id == selected.id }
        // Only inject queued tracks that aren't already part of this list — otherwise starting a
        // playlist that contains a queued song silently duplicated it.
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
            positionMs = 0L,
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
        loadPlaylist(tracks, tracks.indices.random())
        requestSave()
    }

    fun cyclePlaybackMode() {
        val st = _state.value
        // Each branch swaps the whole queue, so the cached position has to be recomputed against the
        // new list before anything reads it (preBufferNextTrack does, immediately).
        if (!st.isShuffle && !st.isRepeat) {
            val current = st.currentTrack
            val curIdx = currentIndexIn(st)
            // Drop the current track by POSITION, not by id: filtering by id also removed the other
            // copies of a song a playlist legitimately repeats.
            val remaining = if (curIdx >= 0) st.queue.filterIndexed { i, _ -> i != curIdx }.shuffled()
                            else st.queue.filter { it.id != current?.id }.shuffled()
            val newQueue = if (current != null) listOf(current) + remaining else remaining
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
    // Disabling (handleAudioFocus=false) forces the internal focus volume multiplier back to 1.0 and
    // abandons focus WITHOUT pausing; re-enabling immediately re-requests it. This clears the rare
    // "stuck duck" state where a transient duck (a notification tone, Assistant, another app) lowered the
    // internal multiplier and the matching focus-gain callback never arrived, leaving a track playing
    // inaudibly while the position keeps advancing. The app cannot otherwise touch that multiplier
    // (exoPlayer.volume is only the base layer: effective = base * focusMultiplier). Cheap and
    // glitch-free: attributes are unchanged (no sink reinit), playback is not paused, and steady-state
    // stays handleAudioFocus=true so call/duck/other-app interruption behavior is unchanged. Called only
    // at safe points (a fresh track before it becomes audible, and on manual resume). Skipped while the
    // DJ is ducking so it does not fight the DJ volume.
    private fun refreshAudioFocus() {
        if (isDucking) return
        runCatching {
            exoPlayer.setAudioAttributes(mediaAudioAttributes, false)
            exoPlayer.setAudioAttributes(mediaAudioAttributes, true)
        }
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
            // Don't get stuck — advance to the first queued track instead of doing nothing.
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
        if (idx != -1 && idx < st.queue.lastIndex) {
            val next = st.queue[idx + 1]
            currentQueueIndex = idx + 1
            _state.value = _state.value.copy(
                currentTrack = next, isPlaying = false,
                positionMs = 0L, durationMs = next.durationMs.toLong()
            )
            startTrack(next)
            requestSave()
        } else if (idx == st.queue.lastIndex && st.currentTrack != null) {
            // Autoplay radio
            mainScope.launch(Dispatchers.IO) {
                try {
                    val currentTrackId = st.currentTrack.id ?: return@launch
                    if (currentTrackId.startsWith("local:")) return@launch
                    val radioJson = NativeEngine.getSpotifyTrackRadioNative(currentTrackId)
                    val array = org.json.JSONArray(radioJson)
                    val newTracks = mutableListOf<FullTrack>()
                    val existingIds = st.queue.mapNotNull { it.id }.toSet()
                    for (i in 0 until array.length()) {
                        val track = FullTrack.fromJson(array.getJSONObject(i))
                        if (track.id != null && !existingIds.contains(track.id)) {
                            newTracks.add(track)
                        }
                    }
                    if (newTracks.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            // Re-read the state here: fetching the radio is a network round-trip, and
                            // appending to the snapshot taken before it would silently discard anything
                            // queued (or removed) while it was in flight.
                            val live = _state.value
                            val liveIds = live.queue.mapNotNull { it.id }.toSet()
                            val fresh = newTracks.filter { it.id !in liveIds }
                            if (fresh.isEmpty()) return@withContext
                            val newQueue = live.queue + fresh
                            _state.value = live.copy(queue = newQueue, originalQueue = live.originalQueue + fresh)
                            notifyQueueChanged(newQueue)
                            requestSave()
                            skipToNext()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AudioPlayerService", "Error loading radio tracks", e)
                }
            }
        }
    }

    fun skipToPrevious() {
        // Same reasoning as skipToNext(): Rustify's queue navigates, the page just gets told what to
        // open. seekTo(0) below is already routed to whichever engine is playing.
        val st = _state.value
        val idx = currentIndexIn(st)
        // Spotify-style "previous": once past the first few seconds it restarts the current track;
        // only within that opening window (or when nothing precedes it) does it go back a track. So a
        // double tap always reaches the previous song, and a single tap right after one starts still
        // goes back. Live position is only trusted when the player really holds this track.
        val livePos = when {
            // In web mode ExoPlayer holds nothing; the mirrored page position is the live clock.
            isWebServing -> st.positionMs.coerceAtLeast(0L)
            preparedTrackId == st.currentTrack?.id -> exoPlayer.currentPosition.coerceAtLeast(0L)
            else -> 0L
        }
        if (st.currentTrack != null && (livePos > PREVIOUS_RESTART_THRESHOLD_MS || idx <= 0)) {
            seekTo(0L)
            return
        }
        if (idx > 0) {
            val prev = st.queue[idx - 1]
            currentQueueIndex = idx - 1
            _state.value = st.copy(
                currentTrack = prev, isPlaying = false,
                positionMs = 0L, durationMs = prev.durationMs.toLong()
            )
            startTrack(prev)
            requestSave()
        }
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
     * Insert [trackToInsert] right after the current track and the run of already-queued tracks that
     * follows it. [knownCurrentIdx] is used when the caller knows the exact position of the current
     * track in [list] (the live queue), since an id lookup would land on the wrong copy when the
     * queue repeats a song.
     */
    private fun insertTrackAfterUserQueue(
        list: List<FullTrack>,
        currentTrack: FullTrack?,
        trackToInsert: FullTrack,
        knownCurrentIdx: Int? = null
    ): List<FullTrack> {
        val currentIdx = knownCurrentIdx?.takeIf { it in list.indices && list[it].id == currentTrack?.id }
            ?: list.indexOfFirst { it.id == currentTrack?.id }
        if (currentIdx == -1) return list + trackToInsert

        var count = 0
        for (i in (currentIdx + 1) until list.size) {
            if (userQueue.any { it.id == list[i].id }) {
                count++
            } else {
                break
            }
        }
        val targetIdx = currentIdx + 1 + count
        val result = list.toMutableList()
        if (targetIdx <= result.size) {
            result.add(targetIdx, trackToInsert)
        } else {
            result.add(trackToInsert)
        }
        return result
    }

    fun enqueue(track: FullTrack) {
        synchronized(userQueue) { userQueue.add(track) }
        val curIdx = currentIndexIn(_state.value)
        val q = insertTrackAfterUserQueue(_state.value.queue, _state.value.currentTrack, track, curIdx)
        val orig = insertTrackAfterUserQueue(_state.value.originalQueue, _state.value.currentTrack, track)
        _state.value = _state.value.copy(queue = q, originalQueue = orig)
        preBufferNextTrack()
        notifyQueueChanged(q)
        requestSave()
    }

    fun enqueueAll(tracks: List<FullTrack>) {
        var currentQueue = _state.value.queue
        var currentOrig = _state.value.originalQueue
        val currentTrack = _state.value.currentTrack
        synchronized(userQueue) {
            tracks.forEach { track ->
                userQueue.add(track)
            }
        }
        val curIdx = currentIndexIn(_state.value)
        tracks.forEach { track ->
            currentQueue = insertTrackAfterUserQueue(currentQueue, currentTrack, track, curIdx)
            currentOrig = insertTrackAfterUserQueue(currentOrig, currentTrack, track)
        }
        _state.value = _state.value.copy(queue = currentQueue, originalQueue = currentOrig)
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
            val isLocalStream = trackId.startsWith("local:") || audioUrl.startsWith("content://") || audioUrl.startsWith("file://")
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