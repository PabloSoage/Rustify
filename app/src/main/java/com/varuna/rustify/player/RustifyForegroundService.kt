package com.varuna.rustify.player

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class RustifyForegroundService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val basePlayer = AudioPlayerService.getInstance(this).let { AudioPlayerService.exoPlayerInstance }
        if (basePlayer != null) {
            val forwardingPlayer = object : androidx.media3.common.ForwardingPlayer(basePlayer) {
                override fun getAvailableCommands(): androidx.media3.common.Player.Commands {
                    return super.getAvailableCommands().buildUpon()
                        .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT)
                        .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .build()
                }

                private fun doSkipNext() {
                    val intent = Intent(this@RustifyForegroundService, RustifyForegroundService::class.java).apply {
                        action = "SKIP_NEXT"
                    }
                    startService(intent)
                }

                private fun doSkipPrevious() {
                    val intent = Intent(this@RustifyForegroundService, RustifyForegroundService::class.java).apply {
                        action = "SKIP_PREVIOUS"
                    }
                    startService(intent)
                }

                override fun seekToNext() = doSkipNext()
                override fun seekToPrevious() = doSkipPrevious()
                override fun seekToNextMediaItem() = doSkipNext()
                override fun seekToPreviousMediaItem() = doSkipPrevious()
            }

            val intent = Intent(this, com.varuna.rustify.MainActivity::class.java).apply {
                action = "com.varuna.rustify.action.VIEW_NOW_PLAYING"
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
            )

            mediaSession = MediaSession.Builder(this, forwardingPlayer)
                .setSessionActivity(pendingIntent)
                .build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_SERVICE" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            "SKIP_NEXT" -> {
                AudioPlayerService.getInstance(this).skipToNext()
            }
            "SKIP_PREVIOUS" -> {
                AudioPlayerService.getInstance(this).skipToPrevious()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
