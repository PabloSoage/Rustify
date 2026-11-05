// The track screen's two video surfaces, Android's side of AppServices.CanvasVideo and PlayerVideo:
// ExoPlayer on a SurfaceView, which only Android has (the desktop's are docs serie-4.x/27 §video).
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.varuna.rustify.player.AudioPlayerService

/**
 * Renders a looping, muted mp4 canvas in an independent ExoPlayer + SurfaceView.
 * The player is fully owned by this composable and released on dispose.
 *
 * [fitWidth] = true (default): scale to the screen width preserving aspect ratio (may leave bars
 * top/bottom, but never stretches). false: fill the whole screen (can stretch).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun CanvasVideoPlayer(url: String, fitWidth: Boolean = true) {
    val context = LocalContext.current
    var ratio by remember(url) { mutableStateOf<Float?>(null) }

    val exoPlayer = remember(url) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        ratio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    }
                }
            })
            prepare()
        }
    }

    DisposableEffect(url) {
        onDispose {
            exoPlayer.release()
        }
    }

    val surface: @Composable (Modifier) -> Unit = { mod ->
        AndroidView(
            modifier = mod,
            factory = { ctx ->
                android.view.SurfaceView(ctx).also {
                    exoPlayer.setVideoSurfaceView(it)
                }
            }
        )
    }

    if (fitWidth) {
        // Fit to width, keep aspect ratio; centre vertically. Default 9:16 until the real size lands.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            surface(Modifier.fillMaxWidth().aspectRatio(ratio ?: (9f / 16f)))
        }
    } else {
        surface(Modifier.fillMaxSize())
    }
}

/**
 * Plays the matched YouTube video (with its own audio) on the track screen's "Video"
 * tab. Reuses the Canvas pattern (2nd ExoPlayer on a SurfaceView, no media3-ui dependency). Pauses
 * the main audio while shown to avoid double audio; tap toggles play/pause.
 */
@Composable
fun YouTubeVideoPlayer(videoUrlPair: Pair<String, String?>, audioPlayerService: AudioPlayerService) {
    val videoRatio = audioPlayerService.state.collectAsState().value.videoSizeRatio

    DisposableEffect(videoUrlPair) {
        audioPlayerService.switchToVideoStream(videoUrlPair.first, videoUrlPair.second)
        onDispose { 
            audioPlayerService.switchToAudioStream()
            // clear the surface view when leaving so the player doesn't hold onto a stale surface
            AudioPlayerService.exoPlayerInstance?.clearVideoSurface()
        }
    }
    
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (videoRatio != null) Modifier.aspectRatio(videoRatio) else Modifier),
            factory = { ctx -> 
                android.view.SurfaceView(ctx).also { 
                    AudioPlayerService.exoPlayerInstance?.setVideoSurfaceView(it)
                } 
            }
        )
    }
}
