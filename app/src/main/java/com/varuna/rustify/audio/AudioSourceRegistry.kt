package com.varuna.rustify.audio

import android.content.Context
import android.util.Log
import com.varuna.rustify.bridge.AddonRepository
import com.varuna.rustify.bridge.InstalledAddon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * Rebuilds the provider list from the installed addons.
     *
     * Called after installing, uninstalling, enabling or disabling one. The built-in three are kept
     * in their declaration order and the addons follow, each as one more [AudioSourceProvider] —
     * which is the whole point of E60's abstraction: the chain, the fallback and the drag-and-drop
     * ordering in Settings need no changes at all to gain a backend that did not exist at build time.
     *
     * Suspending because reading the list crosses JNI.
     */
    suspend fun refreshAddons(context: Context): Unit = withContext(Dispatchers.IO) {
        // `withContext(IO)` is not decoration. `initialize` is synchronous and, on a first run,
        // unpacks yt-dlp; `AddonRepository.list` crosses JNI. Called from a `LaunchedEffect` this
        // would otherwise run on the main thread, which is the ANR shape tracked as point N.
        val appContext = context.applicationContext
        initialize(appContext)
        val installed: List<InstalledAddon> = AddonRepository.list()
        val addonProviders = installed
            .filter { it.enabled && (it.canStream || it.canDownload) }
            .map { AddonAudioSource(appContext, it) }

        val rebuiltIds = synchronized(this@AudioSourceRegistry) {
            val builtIn = providers.filter { AddonAudioSource.addonIdOf(it.capabilities.id) == null }
            val rebuilt = builtIn + addonProviders
            providers = rebuilt
            knownIds = rebuilt.map { it.capabilities.id }
            knownIds
        }
        addonProviders.forEach { provider ->
            runCatching { provider.initialize() }
                .onFailure { Log.e(TAG, "Addon ${provider.capabilities.id} init failed", it) }
        }

        // An add-on the user just installed must actually be in the chain.
        //
        // Without this it would not be: `AudioBackendSettings` appends unknown ids **disabled** on
        // upgrade — right for a provider that arrives with a new app version, wrong for one the
        // user deliberately installed a moment ago. They would have flipped the switch in the
        // add-ons list and nothing would have played through it, with no indication why.
        //
        // Only ids that are not stored yet are touched, so turning an add-on off in the backend
        // order is never undone by a later refresh.
        AudioBackendSettings.adoptNewProviders(
            appContext,
            addonProviders.map { it.capabilities.id }
        )
        Log.i(TAG, "providers rebuilt: ${rebuiltIds.joinToString()}")
    }

    /** The addon-backed providers currently in the chain, for the Settings list. */
    fun addonProviders(): List<AddonAudioSource> = providers.filterIsInstance<AddonAudioSource>()

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