package com.varuna.rustify.audio

import android.content.Context
import com.varuna.rustify.audio.DeezerArl.fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Manages ARLs from a URL the user provides.
 *
 * The app does not provide or suggest any ARL source: only, if the user pastes a URL, it automates
 * testing the tokens that page lists and keeps the one that works (rotation). Parsing is ad-resistant
 * by design: script/style/iframe are stripped and long hex strings (≥100 hex) are matched — no ad
 * contains that, so no adblocker is needed; ARLs are grouped by the country that appears just before
 * them, if any.
 */
object DeezerArl {
    /** [updated] = "updated" date text that appears next to the ARL on the page (best-effort, raw). */
    data class ArlEntry(val country: String, val arl: String, val updated: String = "")

    private val COUNTRIES = linkedMapOf(
        "estados unidos" to "US", "united states" to "US", "mexic" to "MX", "brasil" to "BR",
        "brazil" to "BR", "argentin" to "AR", "colombi" to "CO", "españa" to "ES", "spain" to "ES",
        "canad" to "CA", "franc" to "FR"
    )
    private val ARL_RE = Regex("[0-9a-fA-F]{100,256}")
    // Real browser UA: some site WAFs (Cloudflare/Blogger) serve a trimmed page or block clients with
    // a generic "okhttp" UA. This makes the client look like mobile Chrome.
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** Result with diagnostics: if [entries] is empty, [error] explains why (HTTP 403, timeout, 0 ARLs…). */
    data class FetchResult(val entries: List<ArlEntry>, val error: String? = null)
    // "Updated" date next to an ARL. Strict so it does not match junk like "9-4.029": year 20xx,
    // month 1-12, day 1-31, in yyyy(-|/|.)mm(-|/|.)dd or dd(-|/|.)mm(-|/|.)yyyy format.
    private val DATE_RE = Regex(
        "\\b(20\\d{2}[/.-](0?[1-9]|1[0-2])[/.-](0?[1-9]|[12]\\d|3[01])" +
        "|(0?[1-9]|[12]\\d|3[01])[/.-](0?[1-9]|1[0-2])[/.-]20\\d{2})\\b"
    )

    /** Fetches the page and extracts the ARL list (country + token). Empty = silent failure. */
    suspend fun fetch(context: Context, sourceUrl: String): List<ArlEntry> = fetchDetailed(context, sourceUrl).entries

    /**
     * Like [fetch] but returns the failure reason. The UI shows it verbatim so "no ARL appears" is no
     * longer a mystery (HTTP 403 / timeout / DNS / 0 ARLs on the page, etc.).
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
                val html = resp.body.string()
                if (html.isBlank()) return@withContext FetchResult(emptyList(), "empty body")
                val entries = parse(html)
                FetchResult(entries, if (entries.isEmpty()) "0 ARLs (${html.length} chars fetched)" else null)
            }
        } catch (e: Exception) {
            FetchResult(emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }

    /** Pure parsing (testable). Strips ads and extracts hex ARLs + their country. */
    fun parse(html: String): List<ArlEntry> {
        val cleaned = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<iframe.*?</iframe>"), " ")
            .replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        // Primary pass with tags intact (ARLs usually sit contiguously in a text node).
        val primary = extract(cleaned)
        if (primary.isNotEmpty()) return primary
        // Fallback: strip ALL tags (replacing with a space, so two contiguous ARLs are not merged into a
        // single hex >256 that would stop matching) in case they sit inside attributes or odd wrappers.
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
            // Nearest "updated" date BEFORE the ARL (best-effort), raw as it appears on the page.
            val updated = dates.lastOrNull { it.first <= pos }?.second ?: ""
            out.add(ArlEntry(country, m.value.lowercase(), updated))
        }
        return out.distinctBy { it.arl }
    }

    /**
     * Tests the ARLs from the page (starting with [preferCountry] if given) and saves the first one
     * that works as the active ARL. Returns that ARL, or null if none work.
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
     * Returns a usable ARL: the cached one if still valid; otherwise per the mode (own, or test the
     * page). Validates the cached one with a quick auth and rotates if needed.
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
