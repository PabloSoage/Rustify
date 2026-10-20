package com.varuna.rustify.audio

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the order and activation state of the audio backends.
 *
 * There are two separate lists (streaming vs download), each reorderable and with
 * its own set of toggles. They are stored as two independent JSON arrays in
 * `SharedPreferences("rustify_settings")`:
 *
 * ```json
 * // key: "audio_backends_stream_order"  |  "audio_backends_download_order"
 * [ { "id": "ytdlp", "enabled": true } ]
 * ```
 *
 * Forward compatibility: when reading, any *known* provider missing from the JSON
 * is appended at the end **disabled**. Ids in the JSON that are no longer known
 * providers are ignored (removing a provider does not break existing settings).
 */
object AudioBackendSettings {

    private const val PREFS = "rustify_settings"
    const val KEY_STREAM = "audio_backends_stream_order"
    const val KEY_DOWNLOAD = "audio_backends_download_order"

    /** An entry in the backend list: stable id + activation flag. */
    data class BackendEntry(val id: String, val enabled: Boolean)

    /**
     * Providers enabled on a fresh install. Everything else ships off and is opt-in from Settings:
     * Deezer needs an ARL before it can do anything, and public Invidious instances are routinely
     * blocked by YouTube/Cloudflare (they answer /stats so they look healthy, then fail to resolve
     * audio). Leaving them on by default only produced slow, confusing fallbacks.
     */
    private val DEFAULT_ON = setOf(InnertubeAudioSource.ID, YtDlpAudioSource.ID)

    /**
     * Providers that, on upgrade, arrive **enabled and first** instead of disabled at the end.
     *
     * The rule below — anything new arrives off, so an upgrade cannot change behaviour — is right for
     * a provider that needs setup or is unproven. InnerTube is neither: it needs nothing, was
     * measured before it shipped (0.5 s against 36 s for yt-dlp), and yt-dlp stays right behind it in
     * the chain. Leaving it off would leave every existing user on the slow path the release exists
     * to fix. Applied once per provider (the id is then stored), so moving it afterwards sticks.
     */
    private val PROMOTE_ON_UPGRADE = listOf(InnertubeAudioSource.ID)

    /**
     * Where each provider belongs by default, by what was measured and by how well it survives
     * upstream changes (docs `serie-4.x/02` §2.3):
     *
     * 0. **InnerTube** — fastest (~5 s tap-to-sound against ~38 s for yt-dlp on the emulator), but it
     *    impersonates official clients, so a change at YouTube can break it until a client table
     *    update. Which is why it is not alone:
     * 1. **yt-dlp** — slow, but it updates itself, so it is the stable one. The hedged chain starts it
     *    4 s after InnerTube if InnerTube has not answered, so its slowness only costs when needed.
     * 2. **Add-ons** — whatever the user installed on purpose.
     * 8. **Invidious** — public instances are blocked by YouTube more often than not.
     * 9. **Deezer** — needs an ARL, which expires, and decrypting through the proxy.
     *
     * The last two ship **off**. Unknown ids (add-ons) rank as add-ons.
     */
    internal fun defaultRank(id: String): Int = when (id) {
        InnertubeAudioSource.ID -> 0
        YtDlpAudioSource.ID -> 1
        InvidiousAudioSource.ID -> 8
        DeezerAudioSource.ID -> 9
        else -> 2
    }

    /** Set once the 4.7 reorder has been applied to a stored list, so a later drag sticks. */
    private const val KEY_RANKED = "audio_backends_ranked_v47"

    /**
     * [entries] in [defaultRank] order, keeping each one's on/off and, within a rank, the user's
     * relative order (the sort is stable).
     */
    internal fun ranked(entries: List<BackendEntry>): List<BackendEntry> = entries.sortedBy { defaultRank(it.id) }

    /**
     * Reads the stored order for [key], appending any missing [knownIds] at the end
     * as `enabled=false`. Never throws: if the JSON is corrupt, it falls back to the default.
     */
    fun loadOrder(context: Context, key: String, knownIds: List<String>): List<BackendEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(key, null)
        val parsed = mutableListOf<BackendEntry>()
        // Sane default: on a fresh install only the providers in DEFAULT_ON start enabled, so the
        // user hears audio without touching Settings but isn't silently routed through backends that
        // need setup (Deezer needs an ARL) or that are unreliable in practice (public Invidious
        // instances are widely blocked by YouTube/Cloudflare). Appending new providers as disabled
        // only applies when prefs already existed, so upgrades preserve the user's choices.
        val isFirstRun = raw.isNullOrBlank()
        if (!isFirstRun) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i)
                    if (o != null) {
                        val id = o.optString("id", "")
                        if (id.isNotBlank()) parsed.add(BackendEntry(id, o.optBoolean("enabled", false)))
                    }
                }
            }
        }
        // Append missing known providers: on first run only the DEFAULT_ON ones start enabled;
        // on upgrade anything new arrives disabled so it can't change existing behaviour.
        val present = parsed.map { it.id }.toMutableSet()
        // On a fresh install the catalogue order already puts it first.
        if (!isFirstRun) {
            for (id in PROMOTE_ON_UPGRADE.reversed()) {
                if (id in knownIds && id !in present) {
                    parsed.add(0, BackendEntry(id, enabled = true))
                    present.add(id)
                }
            }
        }
        for (id in knownIds) {
            if (id !in present) {
                parsed.add(BackendEntry(id, isFirstRun && id in DEFAULT_ON))
                present.add(id)
            }
        }
        // Drop unknown ids (providers removed in future versions).
        val known = parsed.filter { it.id in knownIds }
        if (isFirstRun) return ranked(known)
        // Once per list on upgrade: put the stored order into the default ranking, so the slow and
        // fragile providers end up behind the rest. On/off is kept — someone who set up Deezer
        // still has it — and from then on the user's drags are left alone.
        val rankedKey = "${KEY_RANKED}_$key"
        if (!prefs.getBoolean(rankedKey, false)) {
            val reordered = ranked(known)
            prefs.edit { putBoolean(rankedKey, true) }
            if (reordered != known) saveOrder(context, key, reordered)
            return reordered
        }
        return known
    }

    /**
     * Adds [ids] to both orders, **enabled**, but only the ones not stored yet.
     *
     * [loadOrder] appends unknown ids disabled, which is right for a provider that arrives with a
     * new app version — it must not change behaviour on upgrade — and wrong for one the user just
     * installed by pasting a URL. Installing it *is* the opt-in; leaving it off would make the
     * add-on look broken with nothing on screen explaining why.
     *
     * Ids already present are left exactly as they are, so turning an add-on off in the backend
     * order survives every later refresh.
     */
    fun adoptNewProviders(context: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        for (key in listOf(KEY_STREAM, KEY_DOWNLOAD)) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(key, null)
            val stored = mutableListOf<BackendEntry>()
            if (!raw.isNullOrBlank()) {
                runCatching {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id", "")
                        if (id.isNotBlank()) stored.add(BackendEntry(id, o.optBoolean("enabled", false)))
                    }
                }
            }
            val present = stored.map { it.id }.toSet()
            val missing = ids.filter { it !in present }
            if (missing.isEmpty()) continue
            // In front of Invidious and Deezer rather than behind them: an add-on was installed on
            // purpose, those two are last for being unreliable.
            val at = stored.indexOfFirst { defaultRank(it.id) > defaultRank(missing.first()) }.let { if (it < 0) stored.size else it }
            stored.addAll(at, missing.map { BackendEntry(it, enabled = true) })
            saveOrder(context, key, stored)
        }
    }

    fun saveOrder(context: Context, key: String, entries: List<BackendEntry>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply { put("id", e.id); put("enabled", e.enabled) })
        }
        val next = arr.toString()
        val changed = prefs.getString(key, null) != next
        prefs.edit { putString(key, next) }

        // Reordering or disabling a backend changes which one produces the audio, so what is
        // already stored may no longer be what the user wants. Bumping the generation puts the
        // whole cache out of reach in one integer instead of deleting anything — see
        // [StreamRouting]. Guarded on an actual change, because a needless bump costs the user a
        // re-download of everything they had.
        if (changed) StreamRouting.bumpGeneration(context)
    }

    /** Enabled ids in priority order — what the chain consumes. */
    fun enabledIds(order: List<BackendEntry>): List<String> =
        order.filter { it.enabled }.map { it.id }
}