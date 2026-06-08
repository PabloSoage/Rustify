package com.varuna.rustify.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URL

data class AudioPlayerState(
    val currentTrack: FullTrack? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,  // true while resolving OR while ExoPlayer is buffering
    val isError: Boolean = false,       // true when stream could not be resolved/played
    val errorMessage: String = "",
    val queue: List<FullTrack> = emptyList(),
    val originalQueue: List<FullTrack> = emptyList(),
    val isShuffle: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferPercent: Int = 0
)

class AudioPlayerService(private val context: Context) {
    private val _state = MutableStateFlow(AudioPlayerState())
    val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    private val preResolvedUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var preBufferingJob: kotlinx.coroutines.Job? = null
    private var playJob: kotlinx.coroutines.Job? = null
    private var isResolving = false
    private val userQueueIds = mutableListOf<String>()

    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("com.google.android.youtube/17.36.4 (Linux; U; Android 12; GB) gzip")
        .setAllowCrossProtocolRedirects(true)

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
    private var proxyPort: Int = 0
    private val mainScope = CoroutineScope(Dispatchers.Main)

    init {
        // Start Rust audio proxy server
        val cachePath = context.cacheDir.absolutePath
        proxyPort = NativeEngine.startAudioServerNative(cachePath)
        android.util.Log.d("AudioPlayerService", "Rust proxy started on port $proxyPort")

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
                    }
                    Player.STATE_ENDED -> {
                        _state.value = _state.value.copy(isBuffering = false)
                        skipToNext()
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
            }
        })

        // Periodic position / buffer update
        mainScope.launch {
            while (true) {
                if (exoPlayer.isPlaying || _state.value.isBuffering) {
                    _state.value = _state.value.copy(
                        positionMs = exoPlayer.currentPosition,
                        durationMs = if (exoPlayer.duration > 0) exoPlayer.duration
                                     else _state.value.durationMs,
                        bufferPercent = exoPlayer.bufferedPercentage.coerceIn(0, 100)
                    )
                }
                delay(500)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Core: resolve YouTube stream URL then hand it to ExoPlayer
    // -----------------------------------------------------------------------

    private fun playTrack(track: FullTrack, youtubeId: String? = null) {
        val trackId = track.id ?: return
        userQueueIds.remove(trackId)

        // Register metadata in Rust so the resolver can match the track
        val artistsJson = "[" + track.artists.joinToString(",") {
            "\"" + it.name.replace("\"", "\\\"") + "\""
        } + "]"
        NativeEngine.registerTrackMetadataNative(
            trackId, track.name, artistsJson, track.durationMs, track.isrc
        )

        // Show buffering spinner immediately (resolver can take a few seconds)
        isResolving = true
        _state.value = _state.value.copy(isBuffering = true, isError = false, errorMessage = "")

        playJob?.cancel()
        playJob = mainScope.launch {
            val thisJob = coroutineContext[kotlinx.coroutines.Job]
            try {
                exoPlayer.stop()

                // Resolve the actual YouTube stream URL via the Rust proxy /resolve endpoint
                var streamUrl = preResolvedUrls[trackId]
                if (streamUrl.isNullOrBlank()) {
                    val resolveUrl = if (youtubeId != null)
                        "http://127.0.0.1:$proxyPort/resolve?track_id=$trackId&youtube_id=$youtubeId"
                    else
                        "http://127.0.0.1:$proxyPort/resolve?track_id=$trackId"

                    android.util.Log.d("AudioPlayerService", "Resolving: $resolveUrl")

                    val resolvedYoutubeId = withContext(Dispatchers.IO) {
                        try {
                            val conn = URL(resolveUrl).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 30_000
                            conn.readTimeout = 60_000   // resolution can take ~10s on first hit
                            conn.connect()
                            val code = conn.responseCode
                            if (code == 200) {
                                conn.inputStream.bufferedReader().readText().trim()
                            } else {
                                android.util.Log.e("AudioPlayerService", "Resolve HTTP $code for $trackId")
                                null
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("AudioPlayerService", "Resolve error: ${e.message}")
                            null
                        }
                    }

                    if (resolvedYoutubeId.isNullOrBlank()) {
                        android.util.Log.e("AudioPlayerService", "Could not resolve stream for $trackId")
                        _state.value = _state.value.copy(
                            isBuffering = false,
                            isError = true,
                            errorMessage = "No se encontró el stream en YouTube Music"
                        )
                        return@launch
                    }

                    android.util.Log.d("AudioPlayerService", "Resolved YouTube ID: $resolvedYoutubeId")

                    // Use YoutubeDL to extract the direct stream URL
                    streamUrl = withContext(Dispatchers.IO) {
                        try {
                            val request = com.yausername.youtubedl_android.YoutubeDLRequest("https://music.youtube.com/watch?v=$resolvedYoutubeId")
                            request.addOption("-g") // get URL
                            request.addOption("-f", "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio") // Best audio
                            
                            val response = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request)
                            val url = response.out?.trim()
                            
                            if (url.isNullOrBlank()) {
                                android.util.Log.e("AudioPlayerService", "YoutubeDL returned empty URL")
                                null
                            } else {
                                // yt-dlp might return multiple URLs (e.g. video and audio separated). Take the first one.
                                url.lines().firstOrNull()?.trim()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("AudioPlayerService", "YoutubeDL extraction error", e)
                            null
                        }
                    }
                } else {
                    android.util.Log.d("AudioPlayerService", "Using pre-buffered stream URL for $trackId")
                    preResolvedUrls.remove(trackId)
                }

                if (streamUrl.isNullOrBlank()) {
                    android.util.Log.e("AudioPlayerService", "Could not extract stream URL via yt-dlp")
                    _state.value = _state.value.copy(
                        isBuffering = false,
                        isError = true,
                        errorMessage = "Error al extraer el audio"
                    )
                    return@launch
                }
                
                android.util.Log.d("AudioPlayerService", "yt-dlp Extracted direct stream: ${streamUrl.take(80)}...")

                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgent)
                    .setAllowCrossProtocolRedirects(true)
                
                val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                    .createMediaSource(androidx.media3.common.MediaItem.fromUri(streamUrl))

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.play()

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

                val resolvedUrl = withContext(Dispatchers.IO) {
                    try {
                        val resolveUrl = "http://127.0.0.1:$proxyPort/resolve?track_id=$nextTrackId"
                        val conn = URL(resolveUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 20_000
                        conn.connect()
                        val code = conn.responseCode
                        val youtubeId = if (code == 200) {
                            conn.inputStream.bufferedReader().readText().trim()
                        } else null

                        if (youtubeId.isNullOrBlank()) return@withContext null

                        val request = com.yausername.youtubedl_android.YoutubeDLRequest("https://music.youtube.com/watch?v=$youtubeId")
                        request.addOption("-g")
                        request.addOption("-f", "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio")
                        val response = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request)
                        response.out?.trim()?.lines()?.firstOrNull()?.trim()
                    } catch (e: Exception) {
                        android.util.Log.e("AudioPlayerService", "Pre-buffer error for $nextTrackId", e)
                        null
                    }
                }
                if (!resolvedUrl.isNullOrBlank()) {
                    preResolvedUrls[nextTrackId] = resolvedUrl
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
        val queue = listOf(track)
        userQueueIds.clear()
        _state.value = _state.value.copy(
            currentTrack = track,
            isPlaying = false,
            queue = queue,
            originalQueue = queue,
            positionMs = 0L,
            durationMs = track.durationMs.toLong()
        )
        preResolvedUrls.clear()
        playTrack(track)
        notifyQueueChanged(queue)
    }

    fun loadPlaylist(tracks: List<FullTrack>, initialIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val idx = initialIndex.coerceIn(0, tracks.lastIndex)
        val selected = tracks[idx]
        
        userQueueIds.clear()
        val queue = if (_state.value.isShuffle) {
            val remaining = tracks.filterIndexed { i, _ -> i != idx }.shuffled()
            listOf(selected) + remaining
        } else {
            tracks
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
    }

    fun toggleShuffle() {
        val st = _state.value
        val newShuffle = !st.isShuffle
        if (newShuffle) {
            val current = st.currentTrack
            val remaining = st.queue.filter { it.id != current?.id }.shuffled()
            val newQueue = if (current != null) listOf(current) + remaining else remaining
            _state.value = st.copy(isShuffle = newShuffle, queue = newQueue)
            preResolvedUrls.clear()
            preBufferNextTrack()
            notifyQueueChanged(newQueue)
        } else {
            _state.value = st.copy(isShuffle = newShuffle, queue = st.originalQueue)
            preResolvedUrls.clear()
            preBufferNextTrack()
            notifyQueueChanged(st.originalQueue)
        }
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
        val removedTrack = list.removeAt(index)
        removedTrack.id?.let { userQueueIds.remove(it) }
        _state.value = st.copy(queue = list)
        preResolvedUrls.clear()
        preBufferNextTrack()
        notifyQueueChanged(list)
    }

    fun play() {
        if (_state.value.currentTrack != null) exoPlayer.play()
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun togglePlayPause() {
        if (_state.value.currentTrack == null) return
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
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

    private fun insertTrackAfterUserQueue(list: List<FullTrack>, currentTrack: FullTrack?, trackToInsert: FullTrack): List<FullTrack> {
        val currentIdx = list.indexOfFirst { it.id == currentTrack?.id }
        if (currentIdx == -1) {
            return list + trackToInsert
        }
        var count = 0
        for (i in (currentIdx + 1) until list.size) {
            if (userQueueIds.contains(list[i].id)) {
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
        val trackId = track.id
        if (trackId != null) {
            userQueueIds.add(trackId)
        }
        val q = insertTrackAfterUserQueue(_state.value.queue, _state.value.currentTrack, track)
        val orig = insertTrackAfterUserQueue(_state.value.originalQueue, _state.value.currentTrack, track)
        _state.value = _state.value.copy(queue = q, originalQueue = orig)
        preBufferNextTrack()
        notifyQueueChanged(q)
    }

    fun enqueueAll(tracks: List<FullTrack>) {
        var currentQueue = _state.value.queue
        var currentOrig = _state.value.originalQueue
        val currentTrack = _state.value.currentTrack
        tracks.forEach { track ->
            val trackId = track.id
            if (trackId != null) {
                userQueueIds.add(trackId)
            }
            currentQueue = insertTrackAfterUserQueue(currentQueue, currentTrack, track)
            currentOrig = insertTrackAfterUserQueue(currentOrig, currentTrack, track)
        }
        _state.value = _state.value.copy(queue = currentQueue, originalQueue = currentOrig)
        preBufferNextTrack()
        notifyQueueChanged(currentQueue)
    }

    fun release() {
        exoPlayer.release()
    }
}
