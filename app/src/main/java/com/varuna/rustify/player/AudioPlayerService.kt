@file:Suppress("SpellCheckingInspection")
@file:SuppressLint("StaticFieldLeak", "UseKtx")

package com.varuna.rustify.player

import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val bufferPercent: Int = 0
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPlayerService private constructor(private val context: Context) {
    private val _state = MutableStateFlow(AudioPlayerState())
    val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

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
                    val evictor = LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024) // 200MB max
                    val databaseProvider = StandaloneDatabaseProvider(context)
                    SimpleCache(cacheDir, evictor, databaseProvider).also { downloadCache = it }
                }
            }
        }

        fun getCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
            val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent("com.google.android.youtube/17.36.4 (Linux; U; Android 12; GB) gzip")
                .setAllowCrossProtocolRedirects(true)
                
            return androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(getCache(context))
                .setUpstreamDataSourceFactory(dataSourceFactory)
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
    }

    private val preResolvedUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var preBufferingJob: kotlinx.coroutines.Job? = null
    private var playJob: kotlinx.coroutines.Job? = null
    private var isResolving = false
    private val userQueue = mutableListOf<FullTrack>()

    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("com.google.android.youtube/17.36.4 (Linux; U; Android 12; GB) gzip")
        .setAllowCrossProtocolRedirects(true)

    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(getCache(context))
        .setUpstreamDataSourceFactory(dataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val retryCountMap = java.util.concurrent.ConcurrentHashMap<String, Int>()

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

    private var proxyPort: Int = 0
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var mediaControllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    init {
        // Start Rust audio proxy server
        val cachePath = context.cacheDir.absolutePath
        proxyPort = NativeEngine.startAudioServerNative(cachePath)
        android.util.Log.d("AudioPlayerService", "Rust proxy started on port $proxyPort")

        loadState()

        // ExoPlayer state listeners
        exoPlayer.addListener(object : Player.Listener {
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
                    }
                    Player.STATE_ENDED -> {
                        _state.value = _state.value.copy(isBuffering = false)
                        if (_state.value.isRepeat) {
                            seekTo(0L)
                            play()
                        } else {
                            skipToNext()
                        }
                    }
                    Player.STATE_IDLE -> {
                        // Idle without error is fine (after stop())
                        if (!_state.value.isError && !isResolving) {
                            _state.value = _state.value.copy(isBuffering = false)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("AudioPlayerService", "ExoPlayer error: ${error.message}")
                
                _state.value = _state.value.copy(
                    isBuffering = false,
                    isPlaying = false,
                    isError = true,
                    errorMessage = error.message ?: "Playback error"
                )

                val currentTrackId = _state.value.currentTrack?.id
                if (currentTrackId != null && !currentTrackId.startsWith("local:")) {
                    resolvedStreamUrls.remove(currentTrackId)
                    val retries = retryCountMap[currentTrackId] ?: 0
                    if (retries < 2) {
                        retryCountMap[currentTrackId] = retries + 1
                        android.util.Log.d("AudioPlayerService", "Auto-retrying track $currentTrackId (attempt ${retries + 1}/2) in 3s")
                        mainScope.launch {
                            delay(3000)
                            retryCurrentTrack()
                        }
                    } else {
                        android.util.Log.e("AudioPlayerService", "Track $currentTrackId failed after 2 retries, skipping.")
                        retryCountMap.remove(currentTrackId)
                        mainScope.launch {
                            delay(2000)
                            skipToNext()
                        }
                    }
                }
            }
        })

        // Periodic position / buffer update
        mainScope.launch {
            var lastSaveTime = 0L
            while (true) {
                if (!isResolving && (exoPlayer.isPlaying || _state.value.isBuffering)) {
                    _state.value = _state.value.copy(
                        positionMs = exoPlayer.currentPosition,
                        durationMs = if (exoPlayer.duration > 0) exoPlayer.duration
                                     else _state.value.durationMs,
                        bufferPercent = exoPlayer.bufferedPercentage.coerceIn(0, 100)
                    )
                }
                val now = System.currentTimeMillis()
                if (now - lastSaveTime > 5000) {
                    saveState()
                    lastSaveTime = now
                }
                delay(500.milliseconds)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Core: resolve YouTube stream URL then hand it to ExoPlayer
    // -----------------------------------------------------------------------

    private fun playTrack(track: FullTrack, youtubeId: String? = null) {
        val trackId = track.id ?: return
        userQueue.removeAll { it.id == trackId }

        // Register metadata in Rust so the resolver can match the track (only if not local)
        if (!trackId.startsWith("local:")) {
            val artistsJson = "[" + track.artists.joinToString(",") {
                "\"" + it.name.replace("\"", "\\\"") + "\""
            } + "]"
            NativeEngine.registerTrackMetadataNative(
                trackId, track.name, artistsJson, track.durationMs, track.isrc
            )
        }

        // Show buffering spinner immediately (resolver can take a few seconds)
        isResolving = true
        _state.value = _state.value.copy(isBuffering = true, isError = false, errorMessage = "")

        playJob?.cancel()
        playJob = mainScope.launch {
            val thisJob = coroutineContext[kotlinx.coroutines.Job]
            
            // Prevent background termination by binding a MediaController if not already bound
            if (mediaControllerFuture == null) {
                val sessionToken = androidx.media3.session.SessionToken(context, android.content.ComponentName(context, RustifyForegroundService::class.java))
                mediaControllerFuture = androidx.media3.session.MediaController.Builder(context, sessionToken).buildAsync()
            }
            
            try {
                // Set dummy item with new metadata so the notification persists while resolving
                val artworkUrl = track.album?.images?.firstOrNull()?.url ?: track.externalUri ?: ""
                val metadata = androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artists.joinToString(", ") { it.name })
                    .setArtworkUri(if (artworkUrl.isNotBlank()) android.net.Uri.parse(artworkUrl) else null)
                    .build()
                    
                val dummyItem = androidx.media3.common.MediaItem.Builder()
                    .setMediaId("loading")
                    .setUri("https://example.com/dummy.mp3")
                    .setMediaMetadata(metadata)
                    .build()
                    
                exoPlayer.setMediaItem(dummyItem)
                // Don't call prepare yet, just let the media session update its state
                // This keeps the player from clearing the notification
                
                var streamUrl: String? = null
                var finalYoutubeId: String? = null
                
                if (trackId.startsWith("local:")) {
                    streamUrl = trackId.removePrefix("local:")
                    android.util.Log.d("AudioPlayerService", "Playing local track: $streamUrl")
                } else {
                    // Match local first logic
                    val prefs = context.getSharedPreferences("rustify_settings", android.content.Context.MODE_PRIVATE)
                    val matchLocalFirst = prefs.getBoolean("settings_match_local_first", false)
                    val localMusicDirs = prefs.getStringSet("local_music_directories", emptySet()) ?: emptySet()
                    
                    if (matchLocalFirst && localMusicDirs.isNotEmpty()) {
                        val localCacheFile = java.io.File(context.filesDir, "local_music_cache.json")
                        if (localCacheFile.exists()) {
                            try {
                                val jsonStr = localCacheFile.readText()
                                val array = org.json.JSONArray(jsonStr)
                                for (i in 0 until array.length()) {
                                    val localTrack = com.varuna.rustify.bridge.FullTrack.fromJson(array.getJSONObject(i))
                                    if (localTrack.name.equals(track.name, ignoreCase = true) && 
                                        localTrack.artists.firstOrNull()?.name.equals(track.artists.firstOrNull()?.name, ignoreCase = true)) {
                                        streamUrl = localTrack.id?.removePrefix("local:")
                                        android.util.Log.d("AudioPlayerService", "Matched Spotify track to local file: $streamUrl")
                                        break
                                    }
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }

                    if (streamUrl == null) {
                        streamUrl = preResolvedUrls[trackId]
                        if (streamUrl.isNullOrBlank()) {
                            android.util.Log.d("AudioPlayerService", "Resolving YouTube ID for track $trackId...")

                        val resolvedYoutubeId = withContext(Dispatchers.IO) {
                            NativeEngine.resolveYouTubeIdNative(trackId, youtubeId ?: "")
                        }

                        if (!resolvedYoutubeId.isNullOrBlank()) {
                            finalYoutubeId = resolvedYoutubeId
                            android.util.Log.d("AudioPlayerService", "Resolved YouTube ID: $resolvedYoutubeId. Extracting stream with yt-dlp...")
                            streamUrl = withContext(Dispatchers.IO) {
                                try {
                                    val request = com.yausername.youtubedl_android.YoutubeDLRequest("https://music.youtube.com/watch?v=$resolvedYoutubeId")
                                    request.addOption("-g")
                                    request.addOption("-f", "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio")
                                    // Optimization flags (Safe ones only)
                                    request.addOption("--no-check-certificate")
                                    request.addOption("--no-warnings")

                                    val response = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request)
                                    response.out.trim().lines().firstOrNull()?.trim()
                                } catch (e: Exception) {
                                    android.util.Log.e("AudioPlayerService", "YoutubeDL extraction failed", e)
                                    null
                                }
                            }
                        }
                    } else {
                        android.util.Log.d("AudioPlayerService", "Using pre-buffered stream URL for $trackId")
                        preResolvedUrls.remove(trackId)
                    }
                }
            }

            if (streamUrl.isNullOrBlank()) {
                    android.util.Log.e("AudioPlayerService", "Could not extract stream URL")
                    _state.value = _state.value.copy(
                        isBuffering = false,
                        isPlaying = false,
                        isError = true,
                        errorMessage = "Error al extraer el audio"
                    )
                    return@launch
                }
                

                val mediaItem = MediaItem.Builder()
                    .setUri(streamUrl)
                    .setMediaMetadata(metadata)
                    .apply { setCustomCacheKey(trackId) }
                    .build()
                    
                resolvedStreamUrls[trackId] = streamUrl
                
                val isLocalStream = trackId.startsWith("local:") || streamUrl.startsWith("content://") || streamUrl.startsWith("file://")
                
                val mediaSource = if (isLocalStream) {
                    android.util.Log.d("AudioPlayerService", "Creating DefaultMediaSource for local track")
                    DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context))
                        .createMediaSource(mediaItem)
                } else {
                    android.util.Log.d("AudioPlayerService", "yt-dlp Extracted direct stream: ${streamUrl.take(80)}...")
                    val cacheFactory = cacheDataSourceFactory
                    
                    DefaultMediaSourceFactory(cacheFactory)
                        .createMediaSource(mediaItem)
                }

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                if (_state.value.positionMs > 0L) {
                    exoPlayer.seekTo(_state.value.positionMs)
                }
                exoPlayer.play()
                saveState()

                // Pre-buffer the next track in the queue
                preBufferNextTrack()
            } finally {
                if (playJob == thisJob) {
                    isResolving = false
                }
            }
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

                var resolvedUrl: String? = null
                val youtubeId = withContext(Dispatchers.IO) {
                    NativeEngine.resolveYouTubeIdNative(nextTrackId, "")
                }

                if (!youtubeId.isNullOrBlank()) {
                    resolvedUrl = withContext(Dispatchers.IO) {
                        try {
                            val request = com.yausername.youtubedl_android.YoutubeDLRequest("https://music.youtube.com/watch?v=$youtubeId")
                            request.addOption("-g")
                            request.addOption("-f", "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio")
                            // Optimization flags (Safe ones only)
                            request.addOption("--no-check-certificate")
                            request.addOption("--no-warnings")

                            val response = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request)
                            response.out.trim().lines().firstOrNull()?.trim()
                        } catch (e: Exception) {
                            android.util.Log.e("AudioPlayerService", "Pre-buffer YoutubeDL fallback error for $nextTrackId", e)
                            null
                        }
                    }
                }
                
                if (!resolvedUrl.isNullOrBlank()) {
                    preResolvedUrls[nextTrackId] = resolvedUrl
                    resolvedStreamUrls[nextTrackId] = resolvedUrl
                    android.util.Log.d("AudioPlayerService", "Successfully pre-buffered: ${nextTrack.name}")
                }
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
    }

    fun loadAndPlay(track: FullTrack) {
        val queue = listOf(track) + userQueue
        _state.value = _state.value.copy(
            currentTrack = track,
            isPlaying = false,
            queue = queue,
            originalQueue = listOf(track),
            positionMs = 0L,
            durationMs = track.durationMs.toLong()
        )
        preResolvedUrls.clear()
        playTrack(track)
        notifyQueueChanged(queue)
        saveState()
    }

    fun loadPlaylist(tracks: List<FullTrack>, initialIndex: Int = 0) {
        if (tracks.isEmpty()) return
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
        saveState()
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
        saveState()
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
    }

    fun removeFromQueue(index: Int) {
        val st = _state.value
        if (index !in st.queue.indices) return
        
        val list = st.queue.toMutableList()
        list.removeAt(index)
        
        // Remove from userQueue based on relative position to avoid removing duplicates
        val hasCurrent = if (st.currentTrack != null && st.queue.firstOrNull()?.id == st.currentTrack.id) 1 else 0
        if (index >= hasCurrent && index < hasCurrent + userQueue.size) {
            userQueue.removeAt(index - hasCurrent)
        }
        
        _state.value = st.copy(queue = list)
        preResolvedUrls.clear()
        preBufferNextTrack()
        notifyQueueChanged(list)
    }

    @Suppress("unused")
    fun play() {
        val currentTrack = _state.value.currentTrack
        if (currentTrack != null) {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                playTrack(currentTrack)
            } else {
                exoPlayer.play()
            }
        }
    }

    fun pause() {
        exoPlayer.pause()
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
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    fun skipToNext() {
        val st = _state.value
        val idx = st.queue.indexOfFirst { it.id == st.currentTrack?.id }
        if (idx != -1 && idx < st.queue.lastIndex) {
            val next = st.queue[idx + 1]
            _state.value = st.copy(
                currentTrack = next, isPlaying = false,
                positionMs = 0L, durationMs = next.durationMs.toLong()
            )
            playTrack(next)
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
        }
    }

    fun retryCurrentTrack(youtubeId: String? = null, fallbackTrackId: String? = null) {
        val st = _state.value
        val track = if (fallbackTrackId != null) {
            st.queue.find { it.id == fallbackTrackId } ?: st.currentTrack
        } else {
            st.currentTrack
        } ?: return
        
        if (track.id != st.currentTrack?.id) {
            _state.value = st.copy(currentTrack = track, positionMs = 0L, isPlaying = false)
        }
        playTrack(track, youtubeId)
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
        userQueue.add(track)
        val q = insertTrackAfterUserQueue(_state.value.queue, _state.value.currentTrack, track)
        val orig = insertTrackAfterUserQueue(_state.value.originalQueue, _state.value.currentTrack, track)
        _state.value = _state.value.copy(queue = q, originalQueue = orig)
        preBufferNextTrack()
        notifyQueueChanged(q)
    }

    @Suppress("unused")
    fun enqueueAll(tracks: List<FullTrack>) {
        var currentQueue = _state.value.queue
        var currentOrig = _state.value.originalQueue
        val currentTrack = _state.value.currentTrack
        tracks.forEach { track ->
            userQueue.add(track)
            currentQueue = insertTrackAfterUserQueue(currentQueue, currentTrack, track)
            currentOrig = insertTrackAfterUserQueue(currentOrig, currentTrack, track)
        }
        _state.value = _state.value.copy(queue = currentQueue, originalQueue = currentOrig)
        preBufferNextTrack()
        notifyQueueChanged(currentQueue)
    }

    fun release() {
        val stopIntent = Intent(context, RustifyForegroundService::class.java).apply {
            action = "STOP_SERVICE"
        }
        context.startService(stopIntent)
        exoPlayer.release()
        exoPlayerInstance = null
        instance = null
    }

    private fun saveState() {
        mainScope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(context.filesDir, "playback_state.json")
                val json = org.json.JSONObject()
                val st = _state.value
                st.currentTrack?.let { json.put("currentTrack", it.toJson()) }
                
                val qArr = org.json.JSONArray()
                st.queue.forEach { qArr.put(it.toJson()) }
                json.put("queue", qArr)
                
                val origArr = org.json.JSONArray()
                st.originalQueue.forEach { origArr.put(it.toJson()) }
                json.put("originalQueue", origArr)
                
                val uqArr = org.json.JSONArray()
                userQueue.forEach { uqArr.put(it.toJson()) }
                json.put("userQueue", uqArr)
                
                json.put("positionMs", st.positionMs)
                json.put("durationMs", st.durationMs)
                json.put("isShuffle", st.isShuffle)
                
                file.writeText(json.toString())
            } catch (e: Exception) {
                android.util.Log.e("AudioPlayerService", "Error saving state", e)
            }
        }
    }

    private fun loadState() {
        try {
            val file = java.io.File(context.filesDir, "playback_state.json")
            if (file.exists()) {
                val json = org.json.JSONObject(file.readText())
                val trackObj = json.optJSONObject("currentTrack")
                val track = if (trackObj != null) FullTrack.fromJson(trackObj) else null
                
                val qArr = json.optJSONArray("queue")
                val queue = FullTrack.listFromJsonArray(qArr)

                val origArr = json.optJSONArray("originalQueue")
                val origQueue = FullTrack.listFromJsonArray(origArr)
                
                val uqArr = json.optJSONArray("userQueue")
                val uqList = FullTrack.listFromJsonArray(uqArr)
                userQueue.clear()
                userQueue.addAll(uqList)
                
                val positionMs = json.optLong("positionMs", 0L)
                val durationMs = json.optLong("durationMs", 0L)
                val isShuffle = json.optBoolean("isShuffle", false)
                
                _state.value = _state.value.copy(
                    currentTrack = track,
                    originalQueue = origQueue,
                    queue = queue,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isShuffle = isShuffle,
                    isPlaying = false,
                    isBuffering = false
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayerService", "Error loading state", e)
        }
    }
}
