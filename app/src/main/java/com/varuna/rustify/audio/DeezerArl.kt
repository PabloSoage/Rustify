package com.varuna.rustify.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * E62 — Gestión de ARLs desde una **URL que el usuario introduzca**.
 *
 * La app **no provee ni sugiere** ninguna fuente de ARLs: solo, si el usuario pega una URL, automatiza
 * *probar* los tokens que esa página liste y quedarse con el que funciona (rotación). El parseo es
 * **resistente a la publicidad** por diseño: se quitan script/style/iframe y se buscan **cadenas hex
 * largas** (≥100 hex) — ningún anuncio contiene eso, así que no hace falta un adblocker; los ARLs se
 * agrupan por el país que aparezca justo antes si lo hay.
 */
object DeezerArl {
    /** [updated] = texto de fecha "actualizado" que aparezca junto al ARL en la web (best-effort, crudo). */
    data class ArlEntry(val country: String, val arl: String, val updated: String = "")

    private val COUNTRIES = linkedMapOf(
        "estados unidos" to "US", "united states" to "US", "mexic" to "MX", "brasil" to "BR",
        "brazil" to "BR", "argentin" to "AR", "colombi" to "CO", "españa" to "ES", "spain" to "ES",
        "canad" to "CA", "franc" to "FR"
    )
    private val ARL_RE = Regex("[0-9a-fA-F]{100,256}")
    // E62 — Fecha "actualizado" junto a un ARL (dd/mm/yyyy, yyyy-mm-dd, etc.). Best-effort: las webs
    // varían, así que se muestra cruda tal cual; si no hay ninguna cerca, el ARL sale sin fecha.
    private val DATE_RE = Regex("\\b(\\d{4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,2}|\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4})\\b")

    /** Descarga la web y extrae la lista de ARLs (país + token). */
    suspend fun fetch(context: Context, sourceUrl: String): List<ArlEntry> = withContext(Dispatchers.IO) {
        if (sourceUrl.isBlank()) return@withContext emptyList()
        val html = runCatching {
            val req = Request.Builder().url(sourceUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) Rustify").build()
            AudioHttp.client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext emptyList()
        parse(html)
    }

    /** Parseo puro (testeable). Quita ads y saca ARLs hex + su país. */
    fun parse(html: String): List<ArlEntry> {
        val cleaned = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<iframe.*?</iframe>"), " ")
            .replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        val lower = cleaned.lowercase()
        val dates = DATE_RE.findAll(cleaned).map { it.range.first to it.value }.toList()
        val out = ArrayList<ArlEntry>()
        for (m in ARL_RE.findAll(cleaned)) {
            val pos = m.range.first
            var country = ""; var bestIdx = -1
            for ((kw, code) in COUNTRIES) {
                val idx = lower.lastIndexOf(kw, pos)
                if (idx in 0..pos && idx > bestIdx) { bestIdx = idx; country = code }
            }
            // Fecha "actualizado" más cercana ANTES del ARL (best-effort), cruda tal cual la web.
            val updated = dates.lastOrNull { it.first <= pos }?.second ?: ""
            out.add(ArlEntry(country, m.value.lowercase(), updated))
        }
        return out.distinctBy { it.arl }
    }

    /**
     * Prueba los ARLs de la web (empezando por [preferCountry] si se da) y guarda el primero que
     * funcione como ARL activo. Devuelve ese ARL o null si ninguno va.
     */
    suspend fun refreshWorkingArl(context: Context, preferCountry: String? = null): String? {
        val src = DeezerSettings.sourceUrl(context)
        val entries = fetch(context, src)
        if (entries.isEmpty()) return null
        val ordered = if (preferCountry.isNullOrBlank()) entries
            else entries.sortedByDescending { it.country.equals(preferCountry, true) }
        val client = DeezerClient()
        for (e in ordered) {
            if (client.testArl(e.arl)) {
                DeezerSettings.setWorkingArl(context, e.arl)
                return e.arl
            }
        }
        return null
    }

    /**
     * Devuelve un ARL utilizable: el cacheado si sigue válido; si no, según el modo (propio, o probar
     * la web). Valida el cacheado con un auth rápido y rota si hace falta.
     */
    suspend fun ensureArl(context: Context): String? {
        val client = DeezerClient()
        val cached = DeezerSettings.workingArl(context)
        if (cached.isNotBlank() && client.testArl(cached)) return cached
        return when (DeezerSettings.arlMode(context)) {
            "single" -> {
                val own = DeezerSettings.arl(context)
                if (own.isNotBlank() && client.testArl(own)) { DeezerSettings.setWorkingArl(context, own); own } else null
            }
            else -> refreshWorkingArl(context)
        }
    }
}
