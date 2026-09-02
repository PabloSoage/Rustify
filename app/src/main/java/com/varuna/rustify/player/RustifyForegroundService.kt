package com.varuna.rustify.player

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.snapshotFlow
import androidx.core.net.toUri
import androidx.media3.common.ForwardingPlayer
import com.varuna.rustify.webplayer.WebPlayerController
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.TrackFavorites
import com.varuna.rustify.bridge.YtMusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A [MediaLibraryService] (a superset of the former MediaSessionService) so Android Auto can browse a
 * content tree and play through the same session. Existing phone playback is preserved: the session
 * still wraps the shared ExoPlayer via a ForwardingPlayer; the library callback only adds the
 * browsable tree (Liked / Local playlists / YTM favorites) and a browse→play bridge that routes a
 * tapped item back through [AudioPlayerService.loadPlaylist] (our custom resolution pipeline).
 *
 * The browse→play bridge returns empty from onAddMediaItems because we drive the shared player
 * ourselves.
 */
@UnstableApi
class RustifyForegroundService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var webSessionPlayer: WebSessionPlayer? = null
    private val ytmRepo by lazy { YtMusicRepository(applicationContext) }
    // Scope for resolving Android Auto tree branches that require network (playlists/albums).
    private val autoScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "rustify_playback"

        /** Custom session command behind the notification's save button. */
        private const val ACTION_TOGGLE_SAVED = "com.varuna.rustify.TOGGLE_SAVED"
    }

    @SuppressLint("ObsoleteSdkInt")
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

                // In experimental web-player mode the audio comes from the WebView, not from this
                // player, so notification / lockscreen / headset / Android Auto transport has to be
                // forwarded there instead of reaching ExoPlayer.
                private fun webMode() =
                    AudioPlayerService.instance?.isWebServing == true

                override fun play() {
                    if (webMode()) AudioPlayerService.instance?.play() else super.play()
                }

                override fun pause() {
                    if (webMode()) AudioPlayerService.instance?.pause() else super.pause()
                }
            }

            val intent = Intent(this, com.varuna.rustify.MainActivity::class.java).apply {
                action = "com.varuna.rustify.action.VIEW_NOW_PLAYING"
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // In web-player mode the audio comes from a WebView while ExoPlayer stays idle, so the
            // session would advertise "paused" regardless of what the page is doing. This facade
            // rewrites the reported state (and routes transport commands) while web mode is on, and
            // is a straight pass-through otherwise.
            val sessionPlayer = WebSessionPlayer(forwardingPlayer) {
                AudioPlayerService.instance?.isWebServing == true
            }
            webSessionPlayer = sessionPlayer
            WebPlayerController.onStateChanged = {
                runCatching { sessionPlayer.refresh() }
            }

            mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
                .setSessionActivity(pendingIntent)
                .build()
            // Expose the session to the repositories so they can invalidate the Auto tree
            // (notifyChildrenChanged) when the user changes favorites/playlists from the app.
            MediaBrowserNotifier.bind(mediaSession)
            observeSaveButton()
        } else {
            android.util.Log.e("RustifyForegroundService", "ExoPlayer is null, cannot create MediaSession")
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    // ── Save to library, from the notification ───────────────────────────────────────────
    //
    // Hearting the song you are hearing meant opening the app and navigating to it, which is a lot of
    // taps for a decision made in two seconds while the song is still playing. The button lives in the
    // session rather than in a hand-built notification because the notification is Media3's: it is
    // rendered from the session's button preferences, which is also what puts the same control on the
    // lock screen and in Android Auto for free.

    /**
     * Keeps the button's icon telling the truth.
     *
     * Two sources have to be watched, and they are different kinds of thing. The song comes from a
     * `StateFlow`; whether it is saved is Compose state that the screens write directly, so
     * `snapshotFlow` is what notices a heart pressed inside the app. Without the second one the icon
     * would only be correct until the user saved the song from somewhere else and then stayed on it.
     *
     * `collectLatest` on the outer flow cancels the inner collection when the track changes, so only
     * one saved-state observer is ever alive.
     */
    private fun observeSaveButton() {
        val audio = AudioPlayerService.instance ?: return
        autoScope.launch {
            audio.state
                .map { it.currentTrack }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { track ->
                    snapshotFlow { TrackFavorites.isSaved(this@RustifyForegroundService, track) }
                        .distinctUntilChanged()
                        .collect { saved -> applySaveButton(track, saved) }
                }
        }
    }

    /** Session calls belong on the application thread, which is not where the flows above run. */
    private suspend fun applySaveButton(track: FullTrack?, saved: Boolean) {
        withContext(Dispatchers.Main) {
            val session = mediaSession ?: return@withContext
            // Nothing playing, or something with no id to save: no button rather than a dead one.
            val buttons = if (track?.id == null) {
                ImmutableList.of()
            } else {
                ImmutableList.of(
                    CommandButton.Builder(
                        if (saved) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
                    )
                        .setDisplayName(
                            getString(if (saved) R.string.notif_saved else R.string.notif_save)
                        )
                        .setSessionCommand(SessionCommand(ACTION_TOGGLE_SAVED, Bundle.EMPTY))
                        .setEnabled(true)
                        .build()
                )
            }
            // A released session throws rather than ignoring the call, and this arrives from a
            // coroutine that can outlive it by a frame.
            runCatching { session.setMediaButtonPreferences(buttons) }
        }
    }

    // ── Android Auto browsable tree + browse→play ────────────────────────────────────────
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        // A custom command is refused unless the controller was told it exists, and the notification
        // is a controller like any other — without this its button would be there and do nothing.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_SAVED, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_TOGGLE_SAVED) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            // Answer now and save in the background: this runs on the application thread, and the
            // Spotify half is a network call. The icon is not flipped here — [observeSaveButton] is
            // watching the store, so it updates from what actually happened rather than from what was
            // asked for, and a save that fails leaves the button correct.
            val track = AudioPlayerService.instance?.state?.value?.currentTrack
            autoScope.launch {
                runCatching { TrackFavorites.toggle(this@RustifyForegroundService, track) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofItem(browsable("root", getString(R.string.app_name)), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // Async: the Spotify playlist/album/artist nodes are resolved over the network.
            val future = com.google.common.util.concurrent.SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            autoScope.launch {
                val items = runCatching {
                    AndroidAutoBrowse.childrenAsync(this@RustifyForegroundService, parentId, ytmRepo).map { nodeItem(it) }
                }.getOrDefault(emptyList())
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val t = resolveTrack(mediaId)
            return if (t != null) Futures.immediateFuture(LibraryResult.ofItem(trackItem(t), null))
            else Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }

        // Browse→play bridge: a tapped item arrives with a mediaId but no URI (our audio URLs are
        // resolved lazily). Route it through our own pipeline and return empty so the session doesn't
        // also try to set URI-less items on the (shared) player timeline.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val svc = AudioPlayerService.getInstance(this@RustifyForegroundService)
            val first = mediaItems.firstOrNull()?.mediaId
            // A "queue" section item jumps to that point without reloading the list.
            if (first != null && first.startsWith("queue:")) {
                svc.playSpecificTrackInQueue(first.removePrefix("queue:"))
                return Futures.immediateFuture(mutableListOf())
            }
            val tracks = mediaItems.mapNotNull { resolveTrack(it.mediaId) }
            if (tracks.isNotEmpty()) svc.loadPlaylist(tracks, 0)
            return Futures.immediateFuture(mutableListOf())
        }
    }

    private fun browsable(id: String, title: String, subtitle: String = "", imageUrl: String? = null): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title)
                .apply { if (subtitle.isNotBlank()) setSubtitle(subtitle) }
                .apply { if (!imageUrl.isNullOrBlank()) setArtworkUri(android.net.Uri.parse(coverArtUri(imageUrl))) }
                .setIsBrowsable(true).setIsPlayable(false).build()
        ).build()

    private fun trackItem(t: FullTrack): MediaItem {
        val art = coverArtUri(t.album?.images?.firstOrNull()?.url)
        return MediaItem.Builder().setMediaId(t.id ?: "").setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.name)
                .setArtist(t.artists.joinToString(", ") { it.name })
                .apply { if (!art.isNullOrBlank()) setArtworkUri(art.toUri()) }
                .setIsBrowsable(false).setIsPlayable(true).build()
        ).build()
    }

    /** Builds the MediaItem for an [AndroidAutoBrowse.Node] (a folder with cover art, or a track). */
    private fun nodeItem(n: AndroidAutoBrowse.Node): MediaItem {
        if (n.browsable) return browsable(n.id, n.title, n.subtitle, n.imageUrl)
        val t = n.track!!
        val art = coverArtUri(n.imageUrl ?: t.album?.images?.firstOrNull()?.url)
        return MediaItem.Builder().setMediaId(n.id).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.name)
                .setArtist(t.artists.joinToString(", ") { it.name })
                .apply { if (!art.isNullOrBlank()) setArtworkUri(art.toUri()) }
                .setIsBrowsable(false).setIsPlayable(true).build()
        ).build()
    }

    /**
     * Converts a local track/folder cover URI to the `content://` scheme when needed. Local covers are
     * stored in `filesDir/covers/` as `file://...` (readable by the app itself), but the Android Auto
     * renderer runs in another process and has no permission to read our private storage. Exposing
     * them via FileProvider yields a temporary `content://` with read permission. Remote covers
     * (https://) are returned unchanged.
     */
    private fun coverArtUri(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith("file://") || !url.contains("/covers/")) return url
        return runCatching {
            val path = url.toUri().path ?: return url
            val file = java.io.File(path)
            if (!file.exists()) return url
            androidx.core.content.FileProvider.getUriForFile(
                this, "com.varuna.rustify.fileprovider", file
            ).toString()
        }.getOrNull() ?: url
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
        // Drop the session-refresh hook before releasing, so a late poll can't touch a dead session.
        WebPlayerController.onStateChanged = null
        webSessionPlayer = null
        mediaSession?.release()
        mediaSession = null
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AudioPlayerService.instance?.saveNow()
        MediaBrowserNotifier.unbind()
        // Drop the session-refresh hook before releasing, so a late poll can't touch a dead session.
        WebPlayerController.onStateChanged = null
        webSessionPlayer = null
        mediaSession?.release()
        mediaSession = null
        autoScope.cancel()
        super.onDestroy()
    }
}
