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
    // UA de navegador real: algunos WAFs de estas webs (Cloudflare/Blogger) sirven una página recortada
    // o bloquean clientes con UA "okhttp"/genérico. Con esto se comporta como Chrome de móvil.
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** Resultado con diagnóstico: si [entries] viene vacío, [error] dice POR QUÉ (HTTP 403, timeout, 0 ARLs…). */
    data class FetchResult(val entries: List<ArlEntry>, val error: String? = null)
    // E62/E108 — Fecha "actualizado" junto a un ARL. Estricta para no colar basura tipo "9-4.029":
    // año 20xx, mes 1-12, día 1-31, en formato yyyy(-|/|.)mm(-|/|.)dd o dd(-|/|.)mm(-|/|.)yyyy.
    private val DATE_RE = Regex(
        "\\b(20\\d{2}[/.-](0?[1-9]|1[0-2])[/.-](0?[1-9]|[12]\\d|3[01])" +
        "|(0?[1-9]|[12]\\d|3[01])[/.-](0?[1-9]|1[0-2])[/.-]20\\d{2})\\b"
    )

    /** Descarga la web y extrae la lista de ARLs (país + token). Vacío = fallo silencioso (compat). */
    suspend fun fetch(context: Context, sourceUrl: String): List<ArlEntry> = fetchDetailed(context, sourceUrl).entries

    /**
     * Igual que [fetch] pero devuelve el motivo del fallo. La UI lo muestra tal cual, para que "no sale
     * ningún ARL" deje de ser un misterio (HTTP 403 / timeout / DNS / 0 ARLs en la página, etc.).
     */
    suspend fun fetchDetailed(context: Context, sourceUrl: String): FetchResult = withContext(Dispatchers.IO) {
        if (sourceUrl.isBlank()) return@withContext FetchResult(emptyList(), "empty url")
        val url = sourceUrl.trim().let { if (it.startsWith("http", true)) it else "https://$it" }
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,es;q=0.8")
                .build()
            AudioHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext FetchResult(emptyList(), "HTTP ${resp.code}")
                val html = resp.body?.string()
                if (html.isNullOrBlank()) return@withContext FetchResult(emptyList(), "empty body")
                val entries = parse(html)
                FetchResult(entries, if (entries.isEmpty()) "0 ARLs (${html.length} chars fetched)" else null)
            }
        } catch (e: Exception) {
            FetchResult(emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }

    /** Parseo puro (testeable). Quita ads y saca ARLs hex + su país. */
    fun parse(html: String): List<ArlEntry> {
        val cleaned = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<iframe.*?</iframe>"), " ")
            .replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        // Pase primario con las etiquetas intactas (los ARLs suelen ir contiguos en un nodo de texto).
        val primary = extract(cleaned)
        if (primary.isNotEmpty()) return primary
        // Fallback: quita TODAS las etiquetas (poniendo un espacio, para no fusionar dos ARLs contiguos
        // en un único hex >256 que dejaría de casar) por si vienen dentro de atributos o wrappers raros.
        return extract(cleaned.replace(Regex("<[^>]+>"), " "))
    }

    private fun extract(cleaned: String): List<ArlEntry> {
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
