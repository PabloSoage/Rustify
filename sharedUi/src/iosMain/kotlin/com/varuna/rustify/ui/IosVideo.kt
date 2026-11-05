// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/IosVideo.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.varuna.rustify.player.LocalPlayer
import kotlin.math.abs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.currentTime
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.seekToTime
import platform.CoreGraphics.CGRectZero
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
private class VideoView(player: AVPlayer, gravity: String?) : UIView(frame = CGRectZero.readValue()) {
    private val surface = AVPlayerLayer.playerLayerWithPlayer(player).also { it.videoGravity = gravity }

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(surface)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        surface.frame = bounds
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun VideoSurface(player: AVPlayer, gravity: String?) {
    UIKitView(
        factory = { VideoView(player, gravity) },
        modifier = Modifier.fillMaxSize(),
        // Behind the track screen's controls, which take the touches.
        properties = UIKitInteropProperties(interactionMode = null),
    )
}

/** A Canvas: a short clip, on a loop and silent, under the track screen. As the phone's, its own player. */
@Composable
internal fun IosCanvasVideo(url: String, fitWidth: Boolean) {
    val target = remember(url) { NSURL.URLWithString(url) } ?: return
    val player = remember(url) { AVQueuePlayer().also { it.muted = true } }
    // Holds the loop going; a looper nobody keeps stops looping.
    val looper = remember(url) { AVPlayerLooper.playerLooperWithPlayer(player, AVPlayerItem.playerItemWithURL(target)) }
    DisposableEffect(url) {
        player.play()
        onDispose {
            looper.disableLooping()
            player.pause()
        }
    }
    VideoSurface(player, if (fitWidth) AVLayerVideoGravityResizeAspect else AVLayerVideoGravityResizeAspectFill)
}

/**
 * The song's video, on the Video tab. Unlike the phone, whose one player swaps its stream, this stays
 * silent and follows the engine, which goes on playing: the bar, the buttons and the lock screen are the
 * same as ever. What is heard comes from the same YouTube id unless the song plays from another source.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosPlayerVideo(url: String) {
    val target = remember(url) { NSURL.URLWithString(url) } ?: return
    val player = remember(url) { AVPlayer.playerWithURL(target).also { it.muted = true } }
    val engine = LocalPlayer.current
    LaunchedEffect(url) {
        while (isActive) {
            val now = engine.state.value
            if (now.isPlaying) player.play() else player.pause()
            val at = CMTimeGetSeconds(player.currentTime()) * 1000
            if (at.isNaN() || abs(at - now.positionMs) > FOLLOW_WITHIN_MS) {
                player.seekToTime(CMTimeMakeWithSeconds(now.positionMs / 1000.0, preferredTimescale = 600))
            }
            delay(FOLLOW_EVERY_MS)
        }
    }
    DisposableEffect(url) { onDispose { player.pause() } }
    VideoSurface(player, AVLayerVideoGravityResizeAspect)
}

private const val FOLLOW_WITHIN_MS = 1_000.0
private const val FOLLOW_EVERY_MS = 500L
