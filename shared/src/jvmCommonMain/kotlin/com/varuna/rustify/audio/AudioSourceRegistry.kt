package com.varuna.rustify.audio

import com.varuna.rustify.bridge.AddonRepository
import com.varuna.rustify.bridge.InstalledAddon
import com.varuna.rustify.platform.Log
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
 *
 * The same registry on the phone and on the desktop: each platform calls [initialize] once with
 * its [AudioSourceEnvironment] and its own yt-dlp (youtubedl-android on the phone, the `yt-dlp`
 * program on the desktop), and everything else — the providers, their order, the add-ons — is this.
 */
object AudioSourceRegistry {

    private const val TAG = "AudioSourceRegistry"

    /** yt-dlp's id. The provider itself is each platform's own; the id is the same everywhere. */
    const val YTDLP_ID = "ytdlp"

    @Volatile private var providers: List<AudioSourceProvider> = emptyList()
    @Volatile private var knownIds: List<String> = emptyList()
    @Volatile private var initialized = false

    /** What [initialize] was given; null before. */
    @Volatile var environment: AudioSourceEnvironment? = null
        private set

    /** The platform's stream cache, or none before [initialize]. */
    val streamCache: StreamCacheAccess get() = environment?.streamCache ?: StreamCacheAccess.None

    /** Shared between stream/download chains: "which provider served this track last time". */
    private val lastGood = ConcurrentHashMap<String, String>()

    /**
     * Builds and initializes all providers. Idempotent; safe to call multiple times (the first call
     * wins). [ytDlp] is the platform's yt-dlp provider, with the id [YTDLP_ID], or null where there
     * is none.
     */
    fun initialize(env: AudioSourceEnvironment, ytDlp: AudioSourceProvider?) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            environment = env
            // Catalog order = declaration order. New providers are added here. Invidious and Deezer
            // start disabled for existing builds (via AudioBackendSettings forward-compat); the user
            // enables them in Settings.
            val built = listOfNotNull<AudioSourceProvider>(
                InnertubeAudioSource(env),
                ytDlp,
                InvidiousAudioSource(env),
                DeezerAudioSource(env),
                SubsonicAudioSource(env)
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
    fun streamChain(): AudioSourceChain =
        buildChain(AudioBackendSettings.KEY_STREAM)

    /** Download chain using the order/toggles the user saved (download). */
    fun downloadChain(): AudioSourceChain =
        buildChain(AudioBackendSettings.KEY_DOWNLOAD)

    /**
     * Builds a chain honoring the order the user chose (not the declaration order): maps the
     * enabled ids to their providers in the same order they appear in prefs, so the Settings
     * drag&drop actually changes the fallback priority when there is more than one provider.
     */
    private fun buildChain(key: String): AudioSourceChain {
        val env = ensureReady() ?: return AudioSourceChain(emptyList(), lastGood = lastGood)
        val order = AudioBackendSettings.loadOrder(env.settings, key, knownIds)
        val enabled = AudioBackendSettings.enabledIds(order)
        val byId = providers.associateBy { it.capabilities.id }
        val chosen = enabled.mapNotNull { byId[it] }
        return AudioSourceChain(
            chosen.ifEmpty { providers.filter { it.capabilities.id == YTDLP_ID } },
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
     * Suspending because reading the list crosses JNI. Does nothing before [initialize].
     */
    suspend fun refreshAddons(): Unit = withContext(Dispatchers.IO) {
        // `withContext(IO)` is not decoration. `AddonRepository.list` crosses JNI, and called from a
        // `LaunchedEffect` this would otherwise run on the main thread, which is the ANR shape
        // tracked as point N.
        val env = ensureReady() ?: return@withContext
        val installed: List<InstalledAddon> = AddonRepository.list()
        val addonProviders = installed
            .filter { it.enabled && (it.canStream || it.canDownload) }
            .map { AddonAudioSource(it) }

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
            env.settings,
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

    private fun ensureReady(): AudioSourceEnvironment? {
        val env = environment
        if (!initialized || env == null) {
            // Should not happen: each platform initializes it at start-up. Safeguard.
            Log.w(TAG, "Registry accessed before initialize() — providers empty")
        }
        return env
    }
}
