package com.varuna.rustify.player

import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioPlayerState(
    val currentTrack: FullTrack? = null,
    val isPlaying: Boolean = false,
    val queue: List<FullTrack> = emptyList(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

class AudioPlayerService {
    private val _state = MutableStateFlow(AudioPlayerState())
    val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    fun loadAndPlay(track: FullTrack) {
        _state.value = _state.value.copy(
            currentTrack = track,
            isPlaying = true,
            queue = listOf(track)
        )
        // TODO: Implement actual playback logic
    }

    fun loadPlaylist(tracks: List<FullTrack>, initialIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val safeIndex = initialIndex.coerceIn(0, tracks.lastIndex)
        _state.value = _state.value.copy(
            currentTrack = tracks[safeIndex],
            isPlaying = true,
            queue = tracks
        )
        // TODO: Implement actual playback logic
    }

    fun play() {
        if (_state.value.currentTrack != null) {
            _state.value = _state.value.copy(isPlaying = true)
        }
    }

    fun pause() {
        _state.value = _state.value.copy(isPlaying = false)
    }
    
    fun togglePlayPause() {
        if (_state.value.currentTrack != null) {
            _state.value = _state.value.copy(isPlaying = !_state.value.isPlaying)
        }
    }

    fun skipToNext() {
        val currentState = _state.value
        val currentIndex = currentState.queue.indexOf(currentState.currentTrack)
        if (currentIndex != -1 && currentIndex < currentState.queue.lastIndex) {
            val nextTrack = currentState.queue[currentIndex + 1]
            _state.value = currentState.copy(currentTrack = nextTrack, isPlaying = true)
        }
    }

    fun skipToPrevious() {
        val currentState = _state.value
        val currentIndex = currentState.queue.indexOf(currentState.currentTrack)
        if (currentIndex > 0) {
            val previousTrack = currentState.queue[currentIndex - 1]
            _state.value = currentState.copy(currentTrack = previousTrack, isPlaying = true)
        }
    }

    fun enqueue(track: FullTrack) {
        _state.value = _state.value.copy(
            queue = _state.value.queue + track
        )
    }

    fun enqueueAll(tracks: List<FullTrack>) {
         _state.value = _state.value.copy(
            queue = _state.value.queue + tracks
        )
    }
}
