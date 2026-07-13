package com.varuna.rustify.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class RustifyForegroundService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        // BUG B: dedicated channel id for the playback notification. Media3's
        // DefaultMediaNotificationProvider must target an EXISTING channel or the
        // startForeground promotion can fail silently and the notification never shows.
        private const val NOTIFICATION_CHANNEL_ID = "rustify_playback"
    }

    // BUG B: ensure a valid notification channel exists BEFORE Media3 tries to
    // promote the service to foreground (which happens while the player may still be
    // buffering the E60 chain). Without this the notification can silently fail to
    // appear and Android suspends background playback.
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Reproducción",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Controles de reproducción de Rustify"
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // BUG B: create the channel first, then install a DefaultMediaNotificationProvider
        // bound to it so Media3 reliably calls startForeground with an immediately-showable
        // notification (metadata of the current item) even while the player is buffering.
        ensureNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .build()
        )

        // E12: guarantee AudioPlayerService + ExoPlayer exist; the no-op re-read was removed.
        AudioPlayerService.getInstance(this)
        val basePlayer = AudioPlayerService.exoPlayerInstance

        if (basePlayer != null) {
            val forwardingPlayer = object : ForwardingPlayer(basePlayer) {
                override fun getAvailableCommands(): Player.Commands {
                    return super.getAvailableCommands().buildUpon()
                        .add(COMMAND_SEEK_TO_NEXT)
                        .add(COMMAND_SEEK_TO_PREVIOUS)
                        .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
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
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            mediaSession = MediaSession.Builder(this, forwardingPlayer)
                .setSessionActivity(pendingIntent)
                .build()
        } else {
            // E12: if the ExoPlayer is gone (release() ran), don't keep a headless session alive.
            android.util.Log.e("RustifyForegroundService", "ExoPlayer is null, cannot create MediaSession")
            stopSelf()
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Clean up state when the user swipes the app away from recents
        android.util.Log.d("RustifyForegroundService", "onTaskRemoved — cleaning up")
        val audioService = AudioPlayerService.instance
        audioService?.stopPlayerAndRelease()
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSession?.release()
        mediaSession = null
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // E13: persist the latest playback state synchronously before the session goes away.
        AudioPlayerService.instance?.saveNow()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
