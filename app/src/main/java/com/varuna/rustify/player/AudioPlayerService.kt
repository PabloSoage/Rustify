@file:Suppress("SpellCheckingInspection")
@file:SuppressLint("StaticFieldLeak", "UseKtx")

package com.varuna.rustify.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
    // true cuando la pista actual se resolvió a un ARCHIVO LOCAL (match local o track "local:").
    // Lo usa la UI para poner en verde el botón de alternativas e indicar el match actual.
    val isLocalSource: Boolean = false
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @SuppressLint("StaticFieldLeak")
class AudioPlayerService private constructor(private val context: Context) {
    private val _state = MutableStateFlow(AudioPlayerState())
    val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    private val listenerTracker = ListeningTracker(context.applicationContext)

    // Preloaded lyrics for the current track (BUG-20 fix)
    private val _preloadedLyrics = MutableStateFlow<com.varuna.rustify.bridge.LyricsResult?>(null)
    val preloadedLyrics: StateFlow<com.varuna.rustify.bridge.LyricsResult?> = _preloadedLyrics.asStateFlow()
    @Volatile
    var preloadedLyricsTrackId: String? = null
        private set

    companion object {
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
                    val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(500L * 1024 * 1024) // 500MB max
                    val databaseProvider = StandaloneDatabaseProvider(context)
                    SimpleCache(cacheDir, evictor, databaseProvider).also { downloadCache = it }
                }
            }
        }

    }

    private val preResolvedUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val persistedUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // E60: per-track absolute expiry (epoch ms) for cached stream URLs, fed by StreamInfo.expiresAtMs.
    // A googlevideo URL past this instant is dropped before it reaches ExoPlayer (avoids a guaranteed 403).
    private val urlExpiryCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // E60: last human-readable reason the audio chain failed to resolve, shown to the user on give-up.
    @Volatile private var lastResolveError: String? = null
    private var preBufferingJob: kotlinx.coroutines.Job? = null
    private var playJob: kotlinx.coroutines.Job? = null
    @Volatile private var isResolving = false
    @Volatile private var isRetrying = false
    // Generation token so a stale playJob's finally won't clobber isResolving (E10 RC-3).
    private val resolveGen = AtomicLong(0)
    private val userQueue = mutableListOf<FullTrack>()

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

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
        .build().apply {
            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            setAudioAttributes(audioAttributes, true)
        }

    init {
        exoPlayerInstance = exoPlayer
        instance = this
    }

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var mediaControllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    // Single coalescing save queue — serializes all state writes, eliminating the
    // race where two concurrent `saveState` launches wrote the file out of order (E13 §3.2).
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        // Initialize the YouTube resolver cache + mappings (replaces removed loopback server, E11).
        // NOTE: must be filesDir (persistent), NOT cacheDir: youtube_mappings.json holds the user's
        // confirmed YouTube alternatives and Settings/export read it from filesDir. Using cacheDir made
        // the confirmed matchings invisible ("0 matchings") and wiped by the OS clearing the cache.
        val cachePath = context.filesDir.absolutePath
        NativeEngine.initCacheDirNative(cachePath)

        loadUrlCache()
        loadState()

        // Single consumer: debounced, serialized state persistence (E13).
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
                            // E60: purge the ACTUAL persisted URL cache (map + json + legacy prefs + expiry),
                            // not just the legacy SharedPreferences key — otherwise getCachedStreamUrl would
                            // re-serve the same expired googlevideo URL on the next play.
                            removeCachedStreamUrl(currentTrackId)
                            // E60: también invalida la caché "provider que funcionó" para que la
                            // siguiente resolución pase por toda la cadena (no credite el caduco).
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
                        // E10: exponential backoff + jitter for transient errors; fast re-resolve for expired URLs.
                        val delayMs = if (isExpiredUrl) 100L else (500L shl retries).coerceAtMost(8000L) + Random.nextLong(0, 500)
                        android.util.Log.d("AudioPlayerService", "Auto-retrying track $currentTrackId (attempt ${retries + 1}/2) in ${delayMs}ms")
                        autoRetryJob?.cancel()
                        autoRetryJob = mainScope.launch {
                            delay(delayMs.milliseconds)
                            if (_state.value.isError && _state.value.currentTrack?.id == currentTrackId) {
                                retryCurrentTrack(isAutoRetry = true)   // B1: keep onPlayerError's own counter
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
                if (!isResolving && (exoPlayer.isPlaying || _state.value.isBuffering)) {
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
                // E13: only request a save when position advanced meaningfully (>3s) to reduce I/O.
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

        // E11: observe network availability so playback auto-recovers after a VPN tunnel
        // comes up or Wi-Fi reconnects (no ConnectivityManager existed anywhere before).
        registerNetworkCallback()
    }

    // -----------------------------------------------------------------------
    // Network resilience (E11)
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

    // E12: explicit, deterministic foreground-service bind, not inside the cancelable playJob.
    private fun ensureForegroundServiceBound() {
        // BUG B: don't early-return just because we bound ONCE. If the OS killed the foreground
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
        userQueue.removeAll { it.id == trackId }

        // Cancel any pending auto-retry from a previous track to prevent notification/playback race conditions
        autoRetryJob?.cancel()
        autoRetryJob = null
        // B1: only reset the retry counter on a GENUINE new play, never on an auto-retry re-entry,
        // otherwise scheduleExtractionRetry/onPlayerError can never reach the give-up threshold.
        if (!isAutoRetry) retryCountMap.remove(trackId)
        isRetrying = false

        // E70: flush the OUTGOING listening session HERE (deterministically, at the moment of switch),
        // not after the new track resolves. Doing it after resolution (below) meant a manual skip / a
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

        // Preload lyrics asynchronously so they're cached when user opens TrackScreen
        // E40: skip lyrics for ytm: (no Spotify track id)
        if (!trackId.startsWith("ytm:")) { preloadLyrics(track) }

        // Show buffering spinner immediately (resolver can take a few seconds)
        // E40: extract videoId from ytm: prefix (bypasses Spotify resolver)
        var effectiveYoutubeId = youtubeId
        if (trackId.startsWith("ytm:")) { effectiveYoutubeId = trackId.removePrefix("ytm:") }

        val myGen = resolveGen.incrementAndGet()
        isResolving = true
        _state.value = _state.value.copy(isBuffering = true, isError = false, errorMessage = "")

        // E12: bind the foreground service before launching the cancelable job so a rapid
        // skip can't prevent the service/notification from starting.
        ensureForegroundServiceBound()

        playJob?.cancel()
        playJob = mainScope.launch {
            val thisJob = coroutineContext[kotlinx.coroutines.Job]

            try {
                // E12: do NOT set a dummy MediaItem / pause the player here. The notification
                // keeps the previous item until the real one is prepared, instead of freezing
                // on a fake "loading" item in pausse. The UI spinner is driven by _state.isBuffering.
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
                        // An explicit YouTube alternative — a just-picked hint OR a user-confirmed
                        // persisted mapping — must WIN over the local match. Otherwise picking a
                        // YouTube alternative for a locally-matched track silently does nothing (the
                        // local file keeps playing), which is exactly the "alternatives don't work on
                        // matched tracks" bug.
                        // Solo una alternativa REALMENTE elegida por el usuario (hint en curso o
                        // marcada en UserAlternatives) gana al match local. Un mapping auto-persistido
                        // por el resolver antiguo NO cuenta → el match local vuelve a ganar.
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

                    // B3: only use the cached/pre-resolved URL when NO explicit youtubeId hint is given.
                    // An explicit hint means "force a fresh resolution" (e.g. picking a YouTube alternative),
                    // so the stale cached URL must not shadow the new source.
                    if (streamUrl == null && effectiveYoutubeId.isNullOrBlank()) {
                        // Check persisted URL cache first (avoids unnecessary yt-dlp resolution)
                        val cachedUrl = getCachedStreamUrl(trackId)
                        if (!cachedUrl.isNullOrBlank()) {
                            // E60: honour StreamInfo.expiresAtMs — a URL past its expiry would 403 on
                            // ExoPlayer, so drop it and force a fresh chain resolution instead.
                            val expiresAt = urlExpiryCache[trackId]
                            val expired = expiresAt != null && System.currentTimeMillis() >= expiresAt
                            // If user has a confirmed alternative mapping, the cached URL may be from an
                            // old auto-match — skip cache and force re-resolution through the resolver
                            // which checks get_alternative_track first.
                            val mappedId = NativeEngine.getAlternativeTrackNative(trackId)
                            val hasUserMapping = mappedId.isNotBlank() &&
                                com.varuna.rustify.bridge.UserAlternatives.isUserSet(context, trackId)
                            if (expired || hasUserMapping) {
                                if (expired) android.util.Log.d("AudioPlayerService", "Cached URL for $trackId expired; re-resolving")
                                if (hasUserMapping) android.util.Log.d("AudioPlayerService", "User mapping found for $trackId ($mappedId); skipping cached URL")
                                removeCachedStreamUrl(trackId)
                                preResolvedUrls.remove(trackId)
                                resolvedStreamUrls.remove(trackId)
                                com.varuna.rustify.audio.AudioSourceRegistry.invalidateLastGood(trackId)
                            } else {
                                streamUrl = cachedUrl
                                android.util.Log.d("AudioPlayerService", "Using persisted cached URL for $trackId")
                            }
                        }
                    }
                    if (streamUrl == null) {
                        if (effectiveYoutubeId.isNullOrBlank()) streamUrl = preResolvedUrls[trackId]
                        if (streamUrl.isNullOrBlank()) {
                            // E60: la resolución de red vive ahora en la cadena de backends
                            // (AudioSourceRegistry.streamChain). yt-dlp es el provider por defecto;
                            // el `hint` es el youtubeId explícito de una alternativa elegida.
                            android.util.Log.d("AudioPlayerService", "Resolving stream URL via audio chain for $trackId (hint=$youtubeId)...")
                            val res = com.varuna.rustify.audio.AudioSourceRegistry.streamChain(context)
                                .resolveStreamUrl(track, hint = effectiveYoutubeId)
                            res.onSuccess { (providerId, info) ->
                                streamUrl = info.uri
                                lastResolveError = null   // E60: clear any stale failure reason on success
                                // E60: remember when this URL dies so a later play doesn't hand ExoPlayer a 403.
                                info.expiresAtMs?.let { urlExpiryCache[trackId] = it }
                                android.util.Log.d("AudioPlayerService", "Chain resolved stream URL for $trackId via $providerId: ${info.uri.take(80)}...")
                            }
                            res.onFailure { e ->
                                // E60: surface the real per-provider reason so the banner isn't just "not playable".
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
                    // E10: auto-retry the extraction failure instead of leaving the user stuck.
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
                // E62: Deezer entrega una URI deezer://<sngId>?u=<b64> que descifra al vuelo un DataSource
                // propio. No es cacheable (URL del CDN caduca + lleva clave por-track) ni pasa por el cache HTTP.
                val isDeezerStream = streamUrl.startsWith("deezer://")
                // Expone a la UI si la pista actual suena desde un archivo local (para el botón verde).
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

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                if (_state.value.positionMs > 0L) {
                    exoPlayer.seekTo(_state.value.positionMs)
                }
                exoPlayer.play()
                // (E70: onTrackStarted moved to the top of playTrack so the previous session flushes
                //  deterministically on every switch, incl. manual skip / failing next track.)

                // Pre-buffer the next track in the queue
                preBufferNextTrack()
            } finally {
                // E10: generation token — only the latest playTrack clears isResolving.
                if (resolveGen.get() == myGen) {
                    isResolving = false
                }
            }
        }
    }

    // E60: the yt-dlp extraction with retries now lives in YtDlpAudioSource (resolved via
    // AudioSourceRegistry.streamChain). See audio/YtDlpAudioSource.kt::extractStreamUrlWithRetry.

    // E10: retry the failure to extract a stream URL (previously a dead-end error banner).
    private fun scheduleExtractionRetry(track: FullTrack, youtubeId: String?) {
        val id = track.id ?: return
        val n = retryCountMap[id] ?: 0
        if (n >= 2) {
            retryCountMap.remove(id)
            // E60: include the concrete chain failure (e.g. "resolver returned empty YouTube id",
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
                removeCachedStreamUrl(id)                       // B3: don't let a stale URL shadow re-resolution
                retryCurrentTrack(youtubeId, isAutoRetry = true) // B1: keep the retry counter
            }
            isRetrying = false
        }
    }

    private fun preBufferNextTrack() {
        val st = _state.value
        val idx = st.queue.indexOfFirst { it.id == st.currentTrack?.id }
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

                // E60: pre-buffer a través de la misma cadena de backends que playTrack
                // (single source of truth: un solo resolveStreamUrl, sin duplicar el patrón yt-dlp).
                val res = com.varuna.rustify.audio.AudioSourceRegistry.streamChain(context)
                    .resolveStreamUrl(nextTrack, hint = null)
                res.onSuccess { (_, info) ->
                    preResolvedUrls[nextTrackId] = info.uri
                    resolvedStreamUrls[nextTrackId] = info.uri
                    info.expiresAtMs?.let { urlExpiryCache[nextTrackId] = it }  // E60: track pre-buffered URL expiry too
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
        val ids = queue.mapNotNull { it.id }
        val json = "[" + ids.joinToString(",") { "\"$it\"" } + "]"
        NativeEngine.updateQueueNative(json)
        // E96: refresca el nodo "Cola" del árbol de Android Auto (su contenido es la cola de
        // reproducción; si no avisamos, el coche sigue mostrando la cola vieja en caché).
        MediaBrowserNotifier.notifyChildrenChanged("sec_queue")
    }

    fun loadAndPlay(track: FullTrack) {
        ensureForegroundServiceBound()
        val queue = listOf(track) + userQueue
        _state.value = _state.value.copy(
            currentTrack = track,
            isPlaying = false,
            queue = queue,
            // F4/§3.2: include userQueue so cycling shuffle/repeat (which restores originalQueue)
            // doesn't silently drop the manually-queued tracks.
            originalQueue = queue,
            positionMs = 0L,
            durationMs = track.durationMs.toLong()
        )
        preResolvedUrls.clear()
        playTrack(track)
        notifyQueueChanged(queue)
        requestSave()
    }

    fun loadPlaylist(tracks: List<FullTrack>, initialIndex: Int = 0) {
        if (tracks.isEmpty()) return
        ensureForegroundServiceBound()
        val idx = initialIndex.coerceIn(0, tracks.lastIndex)
        val selected = tracks[idx]

        val baseQueue = if (_state.value.isShuffle) {
            val remaining = tracks.filterIndexed { i, _ -> i != idx }.shuffled()
            listOf(selected) + remaining
        } else {
            tracks
        }

        val selectedIdx = baseQueue.indexOfFirst { it.id == selected.id }
        val queue = if (selectedIdx != -1) {
            baseQueue.take(selectedIdx + 1) + userQueue + baseQueue.drop(selectedIdx + 1)
        } else {
            listOf(selected) + userQueue + baseQueue.filter { it.id != selected.id }
        }

        _state.value = _state.value.copy(
            currentTrack = selected,
            isPlaying = false,
            queue = queue,
            originalQueue = tracks,
            positionMs = 0L,
            durationMs = selected.durationMs.toLong()
        )
        preResolvedUrls.clear()
        playTrack(selected)
        notifyQueueChanged(queue)
        requestSave()
    }

    /**
     * F4: start playback of [tracks] in shuffle mode from a RANDOM first track (not index 0).
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
        if (!st.isShuffle && !st.isRepeat) {
            val current = st.currentTrack
            val remaining = st.queue.filter { it.id != current?.id }.shuffled()
            val newQueue = if (current != null) listOf(current) + remaining else remaining
            _state.value = st.copy(isShuffle = true, isRepeat = false, queue = newQueue)
            preResolvedUrls.clear()
            preBufferNextTrack()
            notifyQueueChanged(newQueue)
        } else if (st.isShuffle && !st.isRepeat) {
            _state.value = st.copy(isShuffle = false, isRepeat = true, queue = st.originalQueue)
            preResolvedUrls.clear()
            preBufferNextTrack()
            notifyQueueChanged(st.originalQueue)
        } else {
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
        _state.value = st.copy(queue = list)
        preResolvedUrls.clear()
        preBufferNextTrack()
        notifyQueueChanged(list)
        requestSave()
    }

    fun removeFromQueue(index: Int) {
        val st = _state.value
        if (index !in st.queue.indices) return

        val list = st.queue.toMutableList()
        list.removeAt(index)

        // Remove from userQueue based on relative position to avoid removing duplicates
        val hasCurrent = if (st.currentTrack != null && st.queue.firstOrNull()?.id == st.currentTrack.id) 1 else 0
        synchronized(userQueue) {
            if (index >= hasCurrent && index < hasCurrent + userQueue.size) {
                userQueue.removeAt(index - hasCurrent)
            }
        }

        _state.value = st.copy(queue = list)
        preResolvedUrls.clear()
        preBufferNextTrack()
        notifyQueueChanged(list)
        requestSave()
    }

    fun play() {
        val currentTrack = _state.value.currentTrack
        if (currentTrack != null) {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                playTrack(currentTrack)
            } else {
                exoPlayer.play()
            }
        }
        requestSave()
    }

    // DJ voice ducking (E90): lower/restore playback volume while the DJ speaks. The TTS callbacks
    // arrive off the main thread, so post to the player's main looper before touching ExoPlayer.
    private val djDuckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    fun duckForVoice() { djDuckHandler.post { runCatching { exoPlayer.volume = 0.22f } } }
    fun unduckFromVoice() { djDuckHandler.post { runCatching { exoPlayer.volume = 1.0f } } }

    fun pause() {
        exoPlayer.pause()
        requestSave()
    }

    fun togglePlayPause() {
        val currentTrack = _state.value.currentTrack ?: return
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                playTrack(currentTrack)
            } else {
                exoPlayer.play()
            }
        }
        requestSave()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
        requestSave()
    }

    fun skipToNext() {
        val st = _state.value
        _state.value = st.copy(positionMs = exoPlayer.currentPosition)
        val idx = st.queue.indexOfFirst { it.id == st.currentTrack?.id }
        if (idx == -1 && st.queue.isNotEmpty()) {
            // F4/§3.3: the current track isn't in the queue (e.g. a restored/truncated originalQueue).
            // Don't get stuck — advance to the first queued track instead of doing nothing.
            val next = st.queue.first()
            _state.value = _state.value.copy(
                currentTrack = next, isPlaying = false,
                positionMs = 0L, durationMs = next.durationMs.toLong()
            )
            playTrack(next)
            requestSave()
            return
        }
        if (idx != -1 && idx < st.queue.lastIndex) {
            val next = st.queue[idx + 1]
            _state.value = _state.value.copy(
                currentTrack = next, isPlaying = false,
                positionMs = 0L, durationMs = next.durationMs.toLong()
            )
            playTrack(next)
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
                            val newQueue = st.queue + newTracks
                            _state.value = _state.value.copy(queue = newQueue, originalQueue = st.originalQueue + newTracks)
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
        val st = _state.value
        val idx = st.queue.indexOfFirst { it.id == st.currentTrack?.id }
        if (idx > 0) {
            val prev = st.queue[idx - 1]
            _state.value = st.copy(
                currentTrack = prev, isPlaying = false,
                positionMs = 0L, durationMs = prev.durationMs.toLong()
            )
            playTrack(prev)
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
        // B1: preserve the retry counter across auto-retries; only reset on a user-initiated retry.
        if (!isAutoRetry) track.id?.let { retryCountMap.remove(it) }

        // Force full re-resolution: clear ALL cached URLs for this track (incl. the persisted one — B3).
        track.id?.let {
            resolvedStreamUrls.remove(it)
            preResolvedUrls.remove(it)
            removeCachedStreamUrl(it)
            // E60: re-resolution forzada → no confíes en el provider cacheado como "bueno".
            com.varuna.rustify.audio.AudioSourceRegistry.invalidateLastGood(it)
        }

        // BUG (offline retry restart): whenever we re-resolve the SAME track — an explicit
        // alternative pick, an auto-retry after an extraction/network failure, an expired-URL
        // refresh, or a network-reconnect retry — we must resume from where the user was, not
        // restart from 0. Previously only explicit alternative selections preserved the position,
        // so a background auto-retry (e.g. after coming back online) would yank a mid-song track
        // back to the beginning. playTrack() seeks to _state.positionMs when > 0, so we compute
        // the best-known position here. Only a switch to a DIFFERENT track (fallbackTrackId that
        // isn't the current track) legitimately starts from 0.
        val retryingSameTrack = fallbackTrackId == null || fallbackTrackId == st.currentTrack?.id
        val livePos = exoPlayer.currentPosition.coerceAtLeast(0L)
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

    fun playSpecificTrackInQueue(trackId: String, youtubeId: String? = null) {
        val st = _state.value
        val track = st.queue.find { it.id == trackId }
        if (track != null) {
            _state.value = st.copy(
                currentTrack = track,
                isPlaying = false,
                positionMs = 0L,
                durationMs = track.durationMs.toLong()
            )
            playTrack(track, youtubeId)
            requestSave()
        } else {
            // Should not happen normally, but fallback just in case
        }
    }

    private fun insertTrackAfterUserQueue(list: List<FullTrack>, currentTrack: FullTrack?, trackToInsert: FullTrack): List<FullTrack> {
        val currentIdx = list.indexOfFirst { it.id == currentTrack?.id }
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
        val q = insertTrackAfterUserQueue(_state.value.queue, _state.value.currentTrack, track)
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
        tracks.forEach { track ->
            currentQueue = insertTrackAfterUserQueue(currentQueue, currentTrack, track)
            currentOrig = insertTrackAfterUserQueue(currentOrig, currentTrack, track)
        }
        _state.value = _state.value.copy(queue = currentQueue, originalQueue = currentOrig)
        preBufferNextTrack()
        notifyQueueChanged(currentQueue)
        requestSave()
    }

    /**
     * Reemplaza todo lo que hay DESPUÉS de la pista actual por [tracks] (sin tocar la pista que
     * suena ahora). Pensado para el DJ autónomo al cambiar de mood: descarta el bloque anterior que
     * aún no se ha reproducido y pone el bloque nuevo a continuación de la pista actual, en lugar de
     * apilarlo sobre el anterior (que era lo que hacía `enqueueAll` y dejaba canciones viejas).
     * Vacía `userQueue` porque, en modo DJ, esos items eran precisamente los segmentos encolados
     * automáticamente (no cola manual del usuario).
     */
    fun replaceAutoQueueAfterCurrent(tracks: List<FullTrack>) {
        if (tracks.isEmpty()) return
        val st = _state.value
        val current = st.currentTrack
        val currentIdx = st.queue.indexOfFirst { it.id == current?.id }
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
        // E13: persist the latest state synchronously before tearing down.
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
    // Stream URL cache persistence (BUG-01: avoid re-resolving ephemeral URLs)
    // -------------------------------------------------------------------

    private fun getCachedStreamUrl(trackId: String): String? {
        return persistedUrlCache[trackId]
    }

    // B3: invalidate the persisted stream URL for a track so a forced re-resolution can't reuse the
    // stale URL. Covers both the in-memory map (backed by stream_url_cache.json) and the legacy
    // SharedPreferences "cached_url_$id" entry.
    fun removeCachedStreamUrl(trackId: String) {
        persistedUrlCache.remove(trackId)
        urlExpiryCache.remove(trackId)   // E60: drop the tracked expiry alongside the URL
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
                urlExpiryCache.remove(key)   // E60: keep expiry map in lockstep with the URL cache
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
    // Playback state persistence (E13: atomic, debounced, complete)
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
                put("isRepeat", st.isRepeat)           // E13: previously missing
                put("wasPlaying", st.isPlaying)
                put("schemaVersion", 2)
                put("lastSavedTimestamp", System.currentTimeMillis())
            }
            // Atomic write: tmp + rename — never a half-written file (E13 §3.3).
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

                val positionMs = json.optLong("positionMs", 0L)
                val durationMs = json.optLong("durationMs", 0L)
                val isShuffle = json.optBoolean("isShuffle", false)
                val isRepeat = json.optBoolean("isRepeat", false)   // E13: restore repeat mode

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