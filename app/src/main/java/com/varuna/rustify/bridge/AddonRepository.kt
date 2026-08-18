package com.varuna.rustify.bridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One installed audio backend, as the core stored it.
 *
 * Everything here comes from a third party's manifest, so nothing is trusted for anything but
 * display. The core has already refused ids outside `[a-z0-9._-]` and names containing control
 * characters or bidirectional overrides — a name that can be made to read backwards is exactly the
 * trick you would aim at a list of installed extensions.
 */
data class InstalledAddon(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val baseUrl: String,
    val enabled: Boolean,
    val resources: Set<String>,
    val trackKinds: Set<String>
) {
    val canStream: Boolean get() = "stream" in resources
    val canDownload: Boolean get() = "download" in resources

    /** An empty `trackKinds` means "ask me about anything", which is what a simple addon wants. */
    fun handlesKind(kind: String): Boolean = trackKinds.isEmpty() || kind in trackKinds

    companion object {
        fun fromJson(json: JSONObject): InstalledAddon? = runCatching {
            val manifest = json.optJSONObject("manifest") ?: return null
            InstalledAddon(
                id = manifest.optString("id").ifBlank { return null },
                name = manifest.optString("name").ifBlank { return null },
                version = manifest.optString("version"),
                description = manifest.optString("description").takeIf { it.isNotBlank() },
                baseUrl = json.optString("base_url"),
                enabled = json.optBoolean("enabled", true),
                resources = manifest.optJSONArray("resources").toStringSet(),
                trackKinds = manifest.optJSONArray("trackKinds").toStringSet()
            )
        }.getOrNull()

        private fun JSONArray?.toStringSet(): Set<String> {
            if (this == null) return emptySet()
            return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()
        }
    }
}

/**
 * Installing, listing and ordering audio backends.
 *
 * All of it crosses JNI, and **a JNI call blocks the thread that makes it**, so every function here
 * is `suspend` and dispatches to IO. That is not politeness: it is the shape of the bug tracked as
 * point N in `docs/stremio-core/PLAN-3.x.md`, where a blocking native call on the main thread
 * becomes an ANR the moment the connection is slow. Installing an addon fetches a manifest from the
 * internet, so it is the last place to get that wrong.
 */
object AddonRepository {

    private const val TAG = "AddonRepository"

    /** Installed backends, in the order they are tried. */
    suspend fun list(): List<InstalledAddon> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray(NativeEngine.listAddonsNative())
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(InstalledAddon::fromJson)
            }
        }.getOrElse {
            Log.e(TAG, "could not read the installed addons", it)
            emptyList()
        }
    }

    /**
     * Installs the addon served at [url].
     *
     * The core validates before storing: https only, no private or link-local addresses, no
     * credentials in the URL, a size-capped manifest, and a name that cannot lie about its shape.
     * A failure here means it was refused, and the message says why.
     */
    suspend fun install(url: String): Result<InstalledAddon> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(NativeEngine.installAddonNative(url.trim()))
            if (json.has("success") && !json.optBoolean("success")) {
                error(json.optString("error", "the addon was refused"))
            }
            InstalledAddon.fromJson(json) ?: error("the core returned an addon it could not describe")
        }
    }

    suspend fun uninstall(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { checkOk(NativeEngine.uninstallAddonNative(id)) }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { checkOk(NativeEngine.setAddonEnabledNative(id, enabled)) }
    }

    /** Reorders the fallback chain. Ids not named keep their relative order at the end. */
    suspend fun reorder(ids: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray().also { a -> ids.forEach { a.put(it) } }
            checkOk(NativeEngine.reorderAddonsNative(array.toString()))
        }
    }

    private fun checkOk(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (json.has("success") && !json.optBoolean("success")) {
            error(json.optString("error", "the core refused the change"))
        }
    }
}
