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
        val basePlayer = AudioPlayerService.exoPlayerInstance
        if (basePlayer != null) {
            val forwardingPlayer = object : androidx.media3.common.ForwardingPlayer(basePlayer) {
                override fun getAvailableCommands(): androidx.media3.common.Player.Commands {
                    return super.getAvailableCommands().buildUpon()
                        .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT)
                        .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS)
                        .build()
                }

                override fun seekToNext() {
                    // Send intent to our service to skip
                    android.util.Log.d("RustifyForegroundService", "Skip to next clicked")
                    val intent = Intent(this@RustifyForegroundService, RustifyForegroundService::class.java).apply {
                        action = "SKIP_NEXT"
                    }
                    startService(intent)
                }

                override fun seekToPrevious() {
                    android.util.Log.d("RustifyForegroundService", "Skip to previous clicked")
                    val intent = Intent(this@RustifyForegroundService, RustifyForegroundService::class.java).apply {
                        action = "SKIP_PREVIOUS"
                    }
                    startService(intent)
                }
            }

            val intent = Intent(this, com.varuna.rustify.MainActivity::class.java)
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
                AudioPlayerService.instance?.skipToNext()
            }
            "SKIP_PREVIOUS" -> {
                AudioPlayerService.instance?.skipToPrevious()
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
