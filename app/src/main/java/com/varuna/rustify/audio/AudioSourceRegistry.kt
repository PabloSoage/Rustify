package com.varuna.rustify.audio

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry of audio backends. Builds the available providers, initializes them
 * at app startup, and builds the stream/download chains by reading the order the user
 * saved in [AudioBackendSettings].
 *
 * The player and the DownloadManager consume `[AudioSourceRegistry.stream/download]Chain`
 * instead of knowing about any specific backend.
 */
object AudioSourceRegistry {

    private const val TAG = "AudioSourceRegistry"

    @Volatile private var providers: List<AudioSourceProvider> = emptyList()
    @Volatile private var knownIds: List<String> = emptyList()
    @Volatile private var initialized = false

    /** Shared between stream/download chains: "which provider served this track last time". */
    private val lastGood = ConcurrentHashMap<String, String>()

    /** Builds and initializes all providers. Idempotent; safe to call multiple times. */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Catalog order = declaration order. New providers are added here. Invidious and Deezer
            // start disabled for existing builds (via AudioBackendSettings forward-compat); the user
            // enables them in Settings.
            val built = listOf<AudioSourceProvider>(
                YtDlpAudioSource(appContext),
                InvidiousAudioSource(appContext),
                DeezerAudioSource(appContext)
            )
            providers = built
            knownIds = built.map { it.capabilities.id }
            built.forEach { p ->
                runCatching { p.initialize() }
                    .onFailure { Log.e(TAG, "Provider ${p.capabilities.id} init failed", it) }
            }
            initialized = true
        }
    }

    /** Streaming chain using the order/toggles the user saved (stream). */
    fun streamChain(context: Context): AudioSourceChain =
        buildChain(context, AudioBackendSettings.KEY_STREAM)

    /** Download chain using the order/toggles the user saved (download). */
    fun downloadChain(context: Context): AudioSourceChain =
        buildChain(context, AudioBackendSettings.KEY_DOWNLOAD)

    /**
     * Builds a chain honoring the order the user chose (not the declaration order): maps the
     * enabled ids to their providers in the same order they appear in prefs, so the Settings
     * drag&drop actually changes the fallback priority when there is more than one provider.
     */
    private fun buildChain(context: Context, key: String): AudioSourceChain {
        ensureReady()
        val order = AudioBackendSettings.loadOrder(context, key, knownIds)
        val enabled = AudioBackendSettings.enabledIds(order)
        val byId = providers.associateBy { it.capabilities.id }
        val chosen = enabled.mapNotNull { byId[it] }
        return AudioSourceChain(
            chosen.ifEmpty { providers.filter { it.capabilities.id == YtDlpAudioSource.ID } },
            lastGood = lastGood
        )
    }

    /** Invalidates the "provider that worked" cache for a track (call after 403/410 / forced retry). */
    fun invalidateLastGood(trackId: String) { lastGood.remove(trackId) }

    /** Catalog list (id + capabilities) for the Settings UI, in declaration order. */
    fun catalog(): List<AudioSourceCapabilities> = providers.map { it.capabilities }

    /** Known ids in declaration order (for forward-compat when saving). */
    fun knownIds(): List<String> = knownIds

    private fun ensureReady() {
        if (!initialized) {
            // Should not happen: MainActivity initializes it in onCreate. Safeguard.
            Log.w(TAG, "Registry accessed before initialize() — providers empty")
        }
    }
}