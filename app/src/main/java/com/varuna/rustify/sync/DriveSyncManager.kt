package com.varuna.rustify.sync

import android.content.Context
import androidx.core.content.edit
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import org.json.JSONObject

/**
 * E50 — Preferencias persistentes de la sync (en `rustify_settings`, el mismo
 * SharedPreferences que usa Ajustes).
 */
object DriveSyncPrefs {
    private const val PREFS = "rustify_settings"
    private const val K_LINKED = "drive_sync_linked"
    private const val K_AUTO = "drive_sync_auto"
    private const val K_LAST = "drive_sync_last_ms"

    fun isLinked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_LINKED, false)

    fun setLinked(ctx: Context, linked: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(K_LINKED, linked) }

    fun isAutoSync(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_AUTO, false)

    fun setAutoSync(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(K_AUTO, enabled) }

    /** epoch millis de la última sync correcta, o 0 si nunca. */
    fun lastSyncMs(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(K_LAST, 0L)

    fun setLastSyncMs(ctx: Context, ms: Long) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(K_LAST, ms) }
}

/**
 * E50 — Orquesta una sincronización completa (bidireccional) contra Drive:
 *   1. baja el contenedor remoto (si existe),
 *   2. construye el contenedor local desde disco,
 *   3. hace **merge** (unión + last-write-wins),
 *   4. aplica el resultado a disco/repos (recarga in-memory),
 *   5. sube el contenedor fusionado.
 *
 * Requiere un access token válido con scope `drive.appdata`
 * (ver [GoogleDriveSync.authorize]). Debe llamarse fuera del hilo principal.
 */
class DriveSyncManager(
    private val appContext: Context,
    private val drive: GoogleDriveSync,
    private val spotifyRepo: SpotifyRepository?,
    private val ytmRepo: YtMusicRepository?,
) {
    /**
     * Ejecuta la sync. Lanza excepción (IO/parse) si algo falla; el llamador la
     * traduce a estado de error en la UI. Bloqueante — llamar en Dispatchers.IO.
     */
    @Throws(Exception::class)
    fun syncNow(accessToken: String) {
        val fileId = drive.findBackupFileId(accessToken)
        val remote: JSONObject? = fileId?.let { drive.download(accessToken, it) }
        val local: JSONObject = RustifyBackup.build(appContext)

        val merged: JSONObject = if (remote != null) RustifyBackup.merge(local, remote) else local

        // Aplicar el resultado localmente (escribe ficheros + recarga repos).
        RustifyBackup.apply(appContext, merged, spotifyRepo, ytmRepo)

        // Subir el contenedor fusionado (create o update).
        drive.upload(accessToken, merged, fileId)

        DriveSyncPrefs.setLastSyncMs(appContext, System.currentTimeMillis())
    }
}
