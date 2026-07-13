package com.varuna.rustify.sync

import android.content.Context
import android.provider.Settings
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.metrics.ListeningTracker
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * E50 — Contenedor de backup/sync versionado.
 *
 * Agrega en un ÚNICO JSON todos los datos de usuario locales que ya tienen
 * export/import propio en la app, para poder subirlo/bajarlo de Google Drive
 * (AppData) o exportarlo/importarlo a fichero:
 *
 *  - **mappings** — `youtube_mappings.json` (mapa Spotify→YouTube que escribe el
 *    core Rust; la UI de ajustes lo lee/escribe en `filesDir`).
 *  - **local**    — E30: `local_playlists.json` + `local_favorites.json`
 *    (mismo esquema `rustify-local-user-data` que el export manual de ajustes).
 *  - **ytm**      — E40: biblioteca YouTube Music
 *    ([YtMusicRepository.exportJson] → `ytm_library.json`).
 *  - **metrics**  — E70: eventos crudos de escucha ([ListeningTracker], `metrics.json`).
 *
 * Formato:
 * ```jsonc
 * {
 *   "schema": "rustify-backup",
 *   "version": 1,
 *   "updatedAt": 1720000000000,
 *   "device": "<androidId>",
 *   "mappings": { "<spotifyId>": "<youtubeId>", ... },
 *   "local":    { "playlists": [...], "favorites": ["id", ...] },
 *   "ytm":      { "version": 1, "favorites": [...], "playlists": [...], ... },
 *   "metrics":  [ { evento }, ... ]
 * }
 * ```
 *
 * El bloque `local` y `ytm` reutilizan textualmente los mismos arrays que los
 * exports existentes, de modo que el import puede delegar en los caminos ya
 * probados (`reloadLocalUserData`, `reloadLibrary`, `ListeningTracker.importHistory`).
 */
object RustifyBackup {

    const val SCHEMA = "rustify-backup"
    const val VERSION = 1

    /** Nombre del fichero único que se guarda en `appDataFolder` de Drive. */
    const val DRIVE_FILE_NAME = "rustify-backup.json"

    private const val F_MAPPINGS = "youtube_mappings.json"
    private const val F_LOCAL_PLAYLISTS = "local_playlists.json"
    private const val F_LOCAL_FAVORITES = "local_favorites.json"
    private const val F_YTM = "ytm_library.json"
    private const val F_METRICS = "metrics.json"

    /** Id estable del dispositivo (solo para diagnóstico/merge, no es un secreto). */
    fun deviceId(ctx: Context): String = runCatching {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }.getOrDefault("unknown")

    // ---------------------------------------------------------------------
    // EXPORT — construir el contenedor a partir del disco (filesDir).
    // ---------------------------------------------------------------------

    /** Lee los ficheros locales y produce el contenedor JSON. No toca red. */
    fun build(ctx: Context): JSONObject {
        val dir = ctx.filesDir
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("version", VERSION)
        root.put("updatedAt", System.currentTimeMillis())
        root.put("device", deviceId(ctx))

        // mappings: mapa plano { spotifyId: youtubeId }
        root.put("mappings", readObject(File(dir, F_MAPPINGS)) ?: JSONObject())

        // local (E30): playlists[] + favorites[]
        root.put("local", JSONObject().apply {
            put("playlists", readArray(File(dir, F_LOCAL_PLAYLISTS)) ?: JSONArray())
            put("favorites", readArray(File(dir, F_LOCAL_FAVORITES)) ?: JSONArray())
        })

        // ytm (E40): objeto de biblioteca completo (favorites/playlists/savedAlbums/savedArtists)
        root.put("ytm", readObject(File(dir, F_YTM)) ?: JSONObject())

        // metrics (E70): array de eventos crudos
        root.put("metrics", readArray(File(dir, F_METRICS)) ?: JSONArray())

        return root
    }

    fun buildString(ctx: Context): String = build(ctx).toString()

    // ---------------------------------------------------------------------
    // IMPORT — escribir cada bloque a disco y recargar por las vías existentes.
    // ---------------------------------------------------------------------

    /**
     * Aplica un contenedor a disco y recarga los repos. Sobrescribe los ficheros
     * `mappings`/`local`/`ytm` con el contenido del contenedor (para métricas usa
     * el import con dedupe de [ListeningTracker], que NO borra lo local).
     *
     * @param spotifyRepo para `reloadLocalUserData()` tras escribir E30.
     * @param ytmRepo para `reloadLibrary()` tras escribir E40.
     */
    fun apply(
        ctx: Context,
        container: JSONObject,
        spotifyRepo: SpotifyRepository?,
        ytmRepo: YtMusicRepository?,
    ) {
        require(container.optString("schema") == SCHEMA) {
            "Not a rustify-backup container (schema=${container.optString("schema")})"
        }
        val dir = ctx.filesDir

        // mappings
        container.optJSONObject("mappings")?.let {
            atomicWrite(File(dir, F_MAPPINGS), it.toString())
        }

        // local (E30) — escribir ficheros y recargar in-memory
        container.optJSONObject("local")?.let { local ->
            atomicWrite(File(dir, F_LOCAL_PLAYLISTS), (local.optJSONArray("playlists") ?: JSONArray()).toString())
            atomicWrite(File(dir, F_LOCAL_FAVORITES), (local.optJSONArray("favorites") ?: JSONArray()).toString())
            spotifyRepo?.reloadLocalUserData()
        }

        // ytm (E40) — escribir fichero y recargar in-memory
        container.optJSONObject("ytm")?.let { ytm ->
            atomicWrite(File(dir, F_YTM), ytm.toString())
            ytmRepo?.reloadLibrary()
        }

        // metrics (E70) — import con dedupe (unión, no reemplazo)
        container.optJSONArray("metrics")?.let { metrics ->
            runCatching {
                ListeningTracker.importHistory(ctx, ByteArrayInputStream(metrics.toString().toByteArray(Charsets.UTF_8)))
            }
        }
    }

    // ---------------------------------------------------------------------
    // MERGE — unión por conjuntos + last-write-wins por `updatedAt`.
    // ---------------------------------------------------------------------

    /**
     * Fusiona dos contenedores en uno nuevo. Estrategia (§3.3 del diseño):
     *  - **mappings**: unión de claves; en conflicto gana el del contenedor cuyo
     *    `updatedAt` es mayor (last-write-wins global, ya que un mapping no tiene ts propio).
     *  - **local.playlists / ytm.playlists**: unión por id; en conflicto gana el de
     *    `updatedAt` mayor de la propia entidad.
     *  - **local.favorites / ytm.favorites / savedAlbums / savedArtists**: unión de
     *    conjuntos por id (nunca se pierde un favorito por sync).
     *  - **metrics**: unión con dedupe por (trackId, startedAt, listenedMs).
     *
     * Los borrados NO se propagan (sin tombstones en v1, decisión abierta §10.1).
     */
    fun merge(a: JSONObject, b: JSONObject): JSONObject {
        val aNewer = a.optLong("updatedAt") >= b.optLong("updatedAt")
        val newer = if (aNewer) a else b
        val older = if (aNewer) b else a

        val out = JSONObject()
        out.put("schema", SCHEMA)
        out.put("version", VERSION)
        out.put("updatedAt", maxOf(a.optLong("updatedAt"), b.optLong("updatedAt")))
        out.put("device", newer.optString("device"))

        // mappings — unión; el "newer" pisa en conflicto.
        out.put("mappings", mergeMappings(older.optJSONObject("mappings"), newer.optJSONObject("mappings")))

        // local
        val aLocal = a.optJSONObject("local") ?: JSONObject()
        val bLocal = b.optJSONObject("local") ?: JSONObject()
        out.put("local", JSONObject().apply {
            put("playlists", mergePlaylists(aLocal.optJSONArray("playlists"), bLocal.optJSONArray("playlists"), "id"))
            put("favorites", unionStrings(aLocal.optJSONArray("favorites"), bLocal.optJSONArray("favorites")))
        })

        // ytm
        val aYtm = a.optJSONObject("ytm") ?: JSONObject()
        val bYtm = b.optJSONObject("ytm") ?: JSONObject()
        out.put("ytm", JSONObject().apply {
            put("version", 1)
            // Nombres de campo tal como los serializan los modelos YTM (SpotifyModels.kt):
            // YtmTrack→"video_id", YtmAlbumSlim→"browse_id", YtmArtistRef→"id".
            put("favorites", unionByKey(aYtm.optJSONArray("favorites"), bYtm.optJSONArray("favorites"), "video_id"))
            put("playlists", mergePlaylists(aYtm.optJSONArray("playlists"), bYtm.optJSONArray("playlists"), "localId"))
            put("savedAlbums", unionByKey(aYtm.optJSONArray("savedAlbums"), bYtm.optJSONArray("savedAlbums"), "browse_id"))
            put("savedArtists", unionByKey(aYtm.optJSONArray("savedArtists"), bYtm.optJSONArray("savedArtists"), "id"))
        })

        // metrics — unión con dedupe
        out.put("metrics", mergeMetrics(a.optJSONArray("metrics"), b.optJSONArray("metrics")))

        return out
    }

    // ---------------------------------------------------------------------
    // Helpers de merge
    // ---------------------------------------------------------------------

    private fun mergeMappings(older: JSONObject?, newer: JSONObject?): JSONObject {
        val out = JSONObject()
        older?.keys()?.forEach { k -> out.put(k, older.opt(k)) }
        newer?.keys()?.forEach { k -> out.put(k, newer.opt(k)) } // newer pisa
        return out
    }

    /** Playlists: unión por [idKey]; en conflicto gana la de `updatedAt` mayor. */
    private fun mergePlaylists(a: JSONArray?, b: JSONArray?, idKey: String): JSONArray {
        val byId = LinkedHashMap<String, JSONObject>()
        fun ingest(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString(idKey, "")
                if (id.isBlank()) continue
                val prev = byId[id]
                if (prev == null || o.optLong("updatedAt") >= prev.optLong("updatedAt")) byId[id] = o
            }
        }
        ingest(a); ingest(b)
        return JSONArray().apply { byId.values.forEach { put(it) } }
    }

    /** Unión de un array de objetos por la primera [idKeys] que exista y no esté vacía. */
    private fun unionByKey(a: JSONArray?, b: JSONArray?, vararg idKeys: String): JSONArray {
        val byId = LinkedHashMap<String, JSONObject>()
        fun keyOf(o: JSONObject): String {
            for (k in idKeys) { val v = o.optString(k, ""); if (v.isNotBlank()) return "$k:$v" }
            return o.toString() // fallback: objeto entero
        }
        fun ingest(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                byId.putIfAbsent(keyOf(o), o)
            }
        }
        ingest(a); ingest(b)
        return JSONArray().apply { byId.values.forEach { put(it) } }
    }

    /** Unión de un array de strings (favoritos locales son ids planos). */
    private fun unionStrings(a: JSONArray?, b: JSONArray?): JSONArray {
        val set = LinkedHashSet<String>()
        fun ingest(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let { set.add(it) }
        }
        ingest(a); ingest(b)
        return JSONArray().apply { set.forEach { put(it) } }
    }

    /** Métricas: unión con dedupe por (trackId|trackName, startedAt, listenedMs). */
    private fun mergeMetrics(a: JSONArray?, b: JSONArray?): JSONArray {
        val seen = HashSet<String>()
        val out = JSONArray()
        fun ingest(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (seen.add(metricKey(o))) out.put(o)
            }
        }
        ingest(a); ingest(b)
        return out
    }

    /** Igual criterio que el `dedupeKey` privado de [ListeningTracker]. */
    private fun metricKey(e: JSONObject): String {
        val id = e.optString("trackId", "")
        val idPart = if (id.isNotBlank()) id else "n:${e.optString("trackName", "")}"
        return "$idPart|${e.optLong("startedAt")}|${e.optLong("listenedMs")}"
    }

    // ---------------------------------------------------------------------
    // I/O helpers
    // ---------------------------------------------------------------------

    private fun readObject(f: File): JSONObject? =
        if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() else null

    private fun readArray(f: File): JSONArray? =
        if (f.exists()) runCatching { JSONArray(f.readText()) }.getOrNull() else null

    private fun atomicWrite(dst: File, content: String) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(dst)) { dst.writeText(content); tmp.delete() }
    }
}
