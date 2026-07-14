package com.varuna.rustify.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * E96 — Now a [MediaLibraryService] (a superset of the former MediaSessionService) so Android Auto
 * can browse a content tree and play through the same session. Existing phone playback is preserved:
 * the session still wraps the shared ExoPlayer via a ForwardingPlayer; the library callback only adds
 * the browsable tree (Liked / Local playlists / YTM favorites) and a browse→play bridge that routes a
 * tapped item back through [AudioPlayerService.loadPlaylist] (our custom resolution pipeline).
 *
 * ⚠️ NOT compiled/tested here. The Media3 MediaLibrarySession callback API and the browse→play bridge
 * (returning empty from onAddMediaItems because we drive the shared player ourselves) need a compile
 * + an Android Auto device test.
 */
@UnstableApi
class RustifyForegroundService : MediaLibraryService() {

    private var mediaSession: MediaLibraryService.MediaLibrarySession? = null
    private val ytmRepo by lazy { YtMusicRepository(applicationContext) }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "rustify_playback"
    }

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

        ensureNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .build()
        )

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

            mediaSession = MediaLibraryService.MediaLibrarySession.Builder(this, forwardingPlayer, LibraryCallback())
                .setSessionActivity(pendingIntent)
                .build()
        } else {
            android.util.Log.e("RustifyForegroundService", "ExoPlayer is null, cannot create MediaSession")
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
        return mediaSession
    }

    // ── E96 — Android Auto browsable tree + browse→play ─────────────────────────────────
    private inner class LibraryCallback : MediaLibraryService.MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofItem(browsable("root", getString(R.string.app_name)), params))
        }

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(buildChildren(parentId)), params)
            )
        }

        override fun onGetItem(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val t = resolveTrack(mediaId)
            return if (t != null) Futures.immediateFuture(LibraryResult.ofItem(trackItem(t), null))
            else Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        // Browse→play bridge: a tapped item arrives with a mediaId but no URI (our audio URLs are
        // resolved lazily). Route it through our own pipeline and return empty so the session doesn't
        // also try to set URI-less items on the (shared) player timeline.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val tracks = mediaItems.mapNotNull { resolveTrack(it.mediaId) }
            if (tracks.isNotEmpty()) {
                AudioPlayerService.getInstance(this@RustifyForegroundService).loadPlaylist(tracks, 0)
            }
            return Futures.immediateFuture(mutableListOf())
        }
    }

    private fun browsable(id: String, title: String): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).build()
        ).build()

    private fun trackItem(t: FullTrack): MediaItem {
        val art = t.album?.images?.firstOrNull()?.url
        return MediaItem.Builder().setMediaId(t.id ?: "").setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.name)
                .setArtist(t.artists.joinToString(", ") { it.name })
                .apply { if (!art.isNullOrBlank()) setArtworkUri(android.net.Uri.parse(art)) }
                .setIsBrowsable(false).setIsPlayable(true).build()
        ).build()
    }

    private fun buildChildren(parentId: String): List<MediaItem> =
        AndroidAutoBrowse.children(this, parentId, ytmRepo).map {
            if (it.browsable) browsable(it.id, it.title) else trackItem(it.track!!)
        }

    private fun resolveTrack(mediaId: String): FullTrack? =
        AndroidAutoBrowse.resolveTrack(mediaId, ytmRepo)

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
        android.util.Log.d("RustifyForegroundService", "onTaskRemoved — cleaning up")
        val audioService = AudioPlayerService.instance
        audioService?.stopPlayerAndRelease()
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSession?.release()
        mediaSession = null
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AudioPlayerService.instance?.saveNow()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
