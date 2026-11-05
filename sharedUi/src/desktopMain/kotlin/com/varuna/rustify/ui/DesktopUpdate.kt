// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopUpdate.kt
package com.varuna.rustify.ui

import com.varuna.rustify.platform.AppDirs
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's update check (`app/.../update/AppUpdate.kt`) for the desktop: the same two GitHub
 * endpoints, merged for the same reason, and the same numeric comparison. What differs is the end:
 * no download and no install — the release page opens in the browser, and the dialog names the
 * package for this computer, by the names the release workflow gives them
 * (`Rustify-<version>-<os>-<arch>.msi`, `rustify-<version>-linux-x86_64.deb`, …).
 *
 * A release counts only if it carries a package for this computer: the APKs are often published on
 * their own, and "update available" with nothing to install would be noise.
 */
object DesktopUpdate {
    const val OWNER = "PabloSoage"
    const val REPO = "Rustify"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val RELEASES_API = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"

    /** A published release, as much of it as the check reads. */
    data class Release(
        val tag: String,
        val title: String,
        val body: String,
        val htmlUrl: String,
        val assets: List<String>,
    )

    /** A newer release with a package for this computer. */
    data class UpdateInfo(
        val tag: String,
        val title: String,
        /** Every missed release's notes, newest first. */
        val body: String,
        val htmlUrl: String,
        /** The file to download from the page, for this system and architecture. */
        val asset: String?,
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The newest release past [installed] with a package for this computer, or `null` when there is
     * none. An unknown [installed] (a run from Gradle) answers the newest release there is. Throws
     * when neither endpoint answers.
     */
    suspend fun check(installed: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val listed = runCatching { JSONArray(getJson(RELEASES_API)).let { array -> (0 until array.length()).map { array.getJSONObject(it) } } }
            .getOrDefault(emptyList())
        val newest = runCatching { JSONObject(getJson(LATEST_API)) }.getOrNull()
        if (listed.isEmpty() && newest == null) error("Could not reach the GitHub releases API")
        val releases = (listOfNotNull(newest) + listed).mapNotNull { release(it) }
        pick(releases, installed, platformName(), packagePreference())
    }

    private fun getJson(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Rustify-Updater")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub HTTP ${response.code}")
            return response.body.string()
        }
    }

    private fun release(json: JSONObject): Release? {
        if (json.optBoolean("draft", false)) return null
        val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val assets = json.optJSONArray("assets")
        return Release(
            tag = tag,
            title = json.optString("name").ifBlank { tag },
            body = json.optString("body").trim(),
            htmlUrl = json.optString("html_url").ifBlank { RELEASES_PAGE },
            assets = if (assets == null) emptyList() else (0 until assets.length()).mapNotNull {
                assets.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() }
            },
        )
    }

    /**
     * The decision, apart from the network: [releases] in any order and with repeats, [platform] as
     * the workflow names it (`windows-x86_64`; `null` when this computer has no name there, and then
     * any release counts), [preference] the package endings in the order to offer them.
     */
    fun pick(releases: List<Release>, installed: String, platform: String?, preference: List<String>): UpdateInfo? {
        val sorted = releases.distinctBy { it.tag }.sortedByDescending { versionKey(it.tag) }
        val missed = if (parseVersion(installed).isEmpty()) sorted.take(1) else sorted.filter { isNewer(it.tag, installed) }
        val latest = missed.firstOrNull { platform == null || packageFor(it.assets, platform, preference) != null } ?: return null
        val notes = missed.dropWhile { it !== latest }
        return UpdateInfo(
            tag = latest.tag,
            title = latest.title,
            body = if (notes.size == 1) latest.body else notes.joinToString("\n\n\n") { r ->
                if (r.body.isBlank()) "## ${r.title}" else "## ${r.title}\n\n${r.body}"
            }.trim(),
            htmlUrl = latest.htmlUrl,
            asset = platform?.let { packageFor(latest.assets, it, preference) },
        )
    }

    /** This computer as the release workflow names it, or `null` when it builds nothing for it. */
    fun platformName(
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
    ): String? {
        val os = osName.lowercase()
        val arm = osArch.lowercase().let { it == "aarch64" || it == "arm64" }
        val x64 = osArch.lowercase().let { it == "amd64" || it == "x86_64" || it == "x64" }
        return when {
            os.contains("win") -> if (x64) "windows-x86_64" else null
            os.contains("mac") -> if (arm) "macos-arm64" else if (x64) "macos-x86_64" else null
            os.contains("linux") -> if (arm) "linux-aarch64" else if (x64) "linux-x86_64" else null
            else -> null
        }
    }

    /** How this copy was installed, as the package endings to offer, best first. */
    private fun packagePreference(): List<String> {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val portable = listOf("-portable.zip", "-portable.tar.gz")
        return when {
            AppDirs.portable -> portable
            os.contains("win") -> listOf(".msi", "-setup.exe")
            os.contains("mac") -> listOf(".dmg")
            !System.getenv("APPIMAGE").isNullOrBlank() -> listOf(".AppImage")
            // A system with rpm and no dpkg (Fedora, openSUSE) installed the .rpm.
            !File("/usr/bin/dpkg").exists() && File("/usr/bin/rpm").exists() -> listOf(".rpm", ".AppImage")
            else -> listOf(".deb", ".rpm", ".AppImage")
        }
    }

    /** The package in [assets] for [platform], the first ending of [preference] that has one. */
    fun packageFor(assets: List<String>, platform: String, preference: List<String>): String? {
        val mine = assets.filter { it.contains("-$platform.") || it.contains("-$platform-") }
        return preference.firstNotNullOfOrNull { ending -> mine.firstOrNull { it.endsWith(ending, ignoreCase = true) } }
    }

    /** `[major, minor, patch]` from "v5.0.0b", "v2.11.4-beta" or "5.1"; empty when there is no number. */
    fun parseVersion(s: String): List<Int> {
        val start = s.indexOfFirst { it.isDigit() }
        if (start < 0) return emptyList()
        val parts = s.substring(start).takeWhile { it.isDigit() || it == '.' }.split('.').filter { it.isNotEmpty() }.take(3)
        if (parts.size < 2) return emptyList()
        val numbers = parts.map { it.toIntOrNull() ?: return emptyList() }
        return numbers + List(3 - numbers.size) { 0 }
    }

    private fun versionKey(s: String): Long {
        val v = parseVersion(s)
        if (v.isEmpty()) return -1L
        return v[0] * 1_000_000L + v[1] * 1_000L + v[2]
    }

    /** True when [latest] is strictly greater than [current], number by number. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /** The release notes as plain text: comments gone, heading marks and emphasis dropped. */
    fun plainNotes(markdown: String): String {
        var text = markdown
        while (true) {
            val open = text.indexOf("<!--")
            if (open < 0) break
            val close = text.indexOf("-->", open + 4)
            text = if (close < 0) text.substring(0, open) else text.substring(0, open) + text.substring(close + 3)
        }
        return text.lineSequence()
            .map { line -> line.trimEnd().trimStart('#').let { if (it.length < line.trimEnd().length) it.trimStart() else it } }
            .map { it.replace("**", "").replace("`", "") }
            .joinToString("\n")
            .replace("\n\n\n", "\n\n")
            .trim()
    }
}
