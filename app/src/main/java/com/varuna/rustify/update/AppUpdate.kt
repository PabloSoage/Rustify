package com.varuna.rustify.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * E109 — In-app update checker.
 *
 * Queries GitHub `releases/latest` for the public Rustify repo, compares the release tag against the
 * installed [BuildConfig.versionName-equivalent], and (if newer) surfaces the release changelog plus a
 * one-tap download+install of the APK that matches the device ABI. No new SDK/library: OkHttp (already a
 * dependency) + org.json (platform). Falls back to opening the GitHub release page when no matching APK
 * asset exists or the device ABI is unknown.
 */
object AppUpdate {

    const val OWNER = "PabloSoage"
    const val REPO = "Rustify"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Parsed, device-relevant view of the latest GitHub release. */
    data class UpdateInfo(
        val tag: String,            // e.g. "v2.11.4-beta"
        val versionName: String,    // e.g. "2.11.4" (numeric core, for display)
        val title: String,          // release name (falls back to tag)
        val body: String,           // markdown changelog
        val htmlUrl: String,        // release page (fallback / "open in browser")
        val apkUrl: String?,        // browser_download_url of the ABI-matched APK (null → no direct install)
        val apkName: String?,       // asset file name (used for the cached file)
        val apkSize: Long           // bytes (0 if unknown)
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Numeric [major, minor, patch] parsed from any version-ish string ("v2.11.4-beta", "2.11.4b"). */
    private fun parseVersion(s: String): List<Int> {
        val m = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(s) ?: return emptyList()
        val patch = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: "0"
        return listOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), patch.toInt())
    }

    /** True when [latest] is strictly greater than [current] component-by-component. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""

    /** Pick the release APK asset that matches this device's primary ABI. */
    private fun matchApkAsset(assets: List<JSONObject>): JSONObject? {
        val abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        val apks = assets.filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        for (abi in abis) {
            apks.firstOrNull { it.optString("name").contains(abi, ignoreCase = true) }?.let { return it }
        }
        // Single universal APK with no ABI in the name.
        return apks.firstOrNull { a ->
            val n = a.optString("name").lowercase()
            listOf("arm64-v8a", "armeabi", "x86_64", "x86").none { n.contains(it) }
        }
    }

    /**
     * Fetch the latest release and return [UpdateInfo] if it is newer than the installed build, or
     * `null` when up to date. Throws on network/parse failure (callers decide how loud to be).
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Rustify-Updater")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return@withContext null
            if (!isNewer(tag, installedVersion(context))) return@withContext null

            val assetsArr = json.optJSONArray("assets")
            val assets = ArrayList<JSONObject>()
            if (assetsArr != null) for (i in 0 until assetsArr.length()) assets.add(assetsArr.getJSONObject(i))
            val apk = matchApkAsset(assets)

            UpdateInfo(
                tag = tag,
                versionName = parseVersion(tag).joinToString(".").ifEmpty { tag },
                title = json.optString("name").ifBlank { tag },
                body = json.optString("body").trim(),
                htmlUrl = json.optString("html_url").ifBlank { RELEASES_PAGE },
                apkUrl = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
                apkName = apk?.optString("name")?.takeIf { it.isNotBlank() },
                apkSize = apk?.optLong("size") ?: 0L
            )
        }
    }

    /**
     * Download the release APK into `cacheDir/updates/`, reporting progress in [0f, 1f] via
     * [onProgress] (best-effort; unknown length reports -1f). Returns the downloaded file.
     */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val url = info.apkUrl ?: error("No APK asset for this device")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clear stale downloads so cacheDir doesn't accumulate old APKs.
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            val out = File(dir, info.apkName ?: "rustify-update.apk")

            val req = Request.Builder().url(url).header("User-Agent", "Rustify-Updater").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download HTTP ${resp.code}")
                val body = resp.body ?: error("Empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize.takeIf { it > 0 } ?: -1L
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            else onProgress(-1f)
                        }
                    }
                }
            }
            out
        }

    /** An app able to handle a package-archive VIEW intent (system installer, Install With Options, SAI…). */
    data class Installer(val label: String, val packageName: String, val activityName: String)

    private fun installIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun apkUri(context: Context, apk: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)

    /**
     * Every app that can install [apk] (system PackageInstaller + any Shizuku/adb-based installer such as
     * "Install With Options" or SAI). Requires the package-archive `<queries>` entry on Android 11+.
     */
    fun listInstallers(context: Context, apk: File): List<Installer> {
        val pm = context.packageManager
        val intent = installIntent(apkUri(context, apk))
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { ri ->
                    val ai = ri.activityInfo ?: return@mapNotNull null
                    Installer(ri.loadLabel(pm).toString(), ai.packageName, ai.name)
                }
                // Stable order, and never list ourselves.
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
        }.getOrDefault(emptyList())
    }

    /** Install [apk] with a specific installer (chosen in-app). Grants it read access to the content URI. */
    fun installWith(context: Context, apk: File, installer: Installer) {
        val uri = apkUri(context, apk)
        runCatching {
            context.grantUriPermission(installer.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val intent = installIntent(uri).setPackage(installer.packageName)
        context.startActivity(intent)
    }

    /** True when the OS will let us launch a package-install intent without a detour to Settings. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Send the user to the per-app "install unknown apps" screen. */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Fire the system package installer for [apk]. Uses a chooser so the user can pick the stock
     * "Package installer" or any other installer they have (e.g. a Shizuku-based "Install with options").
     */
    fun install(context: Context, apk: File) {
        val view = installIntent(apkUri(context, apk))
        val chooser = Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** Open the release page in a browser (fallback when no direct-install APK is available). */
    fun openReleasePage(context: Context, info: UpdateInfo) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
