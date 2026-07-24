package com.varuna.rustify.sync

import android.content.Context
import androidx.core.content.edit
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import org.json.JSONObject

/**
 * Persistent sync preferences (in `rustify_settings`, the same SharedPreferences that Settings uses).
 */
object DriveSyncPrefs {
    private const val PREFS = "rustify_settings"
    private const val K_LINKED = "drive_sync_linked"
    private const val K_AUTO = "drive_sync_auto"
    private const val K_LAST = "drive_sync_last_ms"
    private const val K_METHOD = "drive_auth_method"   // "play" (Play Services) | "browser" (AppAuth)

    /** Drive auth method: "play" (Play Services) by default, or "browser" (AppAuth). */
    fun authMethod(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_METHOD, "play") ?: "play"

    fun setAuthMethod(ctx: Context, method: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(K_METHOD, method) }

    fun isLinked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_LINKED, false)

    fun setLinked(ctx: Context, linked: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(K_LINKED, linked) }

    fun isAutoSync(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_AUTO, false)

    fun setAutoSync(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(K_AUTO, enabled) }

    /** Epoch millis of the last successful sync, or 0 if never. */
    fun lastSyncMs(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(K_LAST, 0L)

    fun setLastSyncMs(ctx: Context, ms: Long) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(K_LAST, ms) }
}

/**
 * Orchestrates a full (bidirectional) sync against Drive:
 *   1. downloads the remote container (if it exists),
 *   2. builds the local container from disk,
 *   3. performs a **merge** (union + last-write-wins),
 *   4. applies the result to disk/repos (reloads in-memory state),
 *   5. uploads the merged container.
 *
 * Requires a valid access token with the `drive.appdata` scope (see [GoogleDriveSync.authorize]).
 * Must be called off the main thread.
 */
class DriveSyncManager(
    private val appContext: Context,
    private val drive: GoogleDriveSync,
    private val spotifyRepo: SpotifyRepository?,
    private val ytmRepo: YtMusicRepository?,
) {
    /**
     * Runs the sync. Throws (IO/parse) if anything fails; the caller translates it into an error
     * state in the UI. Blocking — call on Dispatchers.IO.
     */
    @Throws(Exception::class)
    fun syncNow(accessToken: String) {
        val fileId = drive.findBackupFileId(accessToken)
        val remote: JSONObject? = fileId?.let { drive.download(accessToken, it) }
        val local: JSONObject = RustifyBackup.build(appContext)

        val merged: JSONObject = if (remote != null) RustifyBackup.merge(local, remote) else local

        // Apply the result locally (writes files + reloads repos).
        RustifyBackup.apply(appContext, merged, spotifyRepo, ytmRepo)

        // Upload the merged container (create or update).
        drive.upload(accessToken, merged, fileId)

        DriveSyncPrefs.setLastSyncMs(appContext, System.currentTimeMillis())
    }
}
