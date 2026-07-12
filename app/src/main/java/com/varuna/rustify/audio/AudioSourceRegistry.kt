package com.varuna.rustify.audio

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * E60 — Registro único de backends de audio. Construye los providers disponibles
 * (hoy sólo [YtDlpAudioSource]; E61 Invidious y E62 Deemix se añadirán aquí),
 * los inicializa al arrancar la app y construye las cadenas de stream/descarga
 * leyendo el orden guardado por el usuario en [AudioBackendSettings].
 *
 * El reproductor y el DownloadManager consumen `[AudioSourceRegistry.stream/download]Chain`
 * en vez de conocer yt-dlp directamente.
 */
object AudioSourceRegistry {

    private const val TAG = "AudioSourceRegistry"

    @Volatile private var providers: List<AudioSourceProvider> = emptyList()
    @Volatile private var knownIds: List<String> = emptyList()
    @Volatile private var initialized = false

    /** Compartido entre cadenas stream/download: "qué provider sirvió este track la última vez". */
    private val lastGood = ConcurrentHashMap<String, String>()

    /** Construye e inicializa todos los providers. Idempotente; seguro llamar varias veces. */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Orden del catálogo = orden de declaración. Nuevos providers se añaden aquí.
            val built = listOf<AudioSourceProvider>(
                YtDlpAudioSource(appContext)
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

    /** Cadena de streaming con el orden/toggles guardados por el usuario (stream). */
    fun streamChain(context: Context): AudioSourceChain {
        ensureReady()
        val order = AudioBackendSettings.loadOrder(context, AudioBackendSettings.KEY_STREAM, knownIds)
        val enabled = AudioBackendSettings.enabledIds(order)
        val chosen = providers.filter { it.capabilities.id in enabled }
        return AudioSourceChain(
            chosen.ifEmpty { providers.filter { it.capabilities.id == YtDlpAudioSource.ID } },
            lastGood = lastGood
        )
    }

    /** Cadena de descarga con el orden/toggles guardados por el usuario (download). */
    fun downloadChain(context: Context): AudioSourceChain {
        ensureReady()
        val order = AudioBackendSettings.loadOrder(context, AudioBackendSettings.KEY_DOWNLOAD, knownIds)
        val enabled = AudioBackendSettings.enabledIds(order)
        val chosen = providers.filter { it.capabilities.id in enabled }
        return AudioSourceChain(
            chosen.ifEmpty { providers.filter { it.capabilities.id == YtDlpAudioSource.ID } },
            lastGood = lastGood
        )
    }

    /** Invalida la caché "provider que funcionó" para un track (llamar tras 403/410 / retry forzado). */
    fun invalidateLastGood(trackId: String) { lastGood.remove(trackId) }

    /** Lista de catálogo (id + capabilities) para la UI de Ajustes, en orden de declaración. */
    fun catalog(): List<AudioSourceCapabilities> = providers.map { it.capabilities }

    /** IDs conocidos en orden de declaración (para forward-compat al guardar). */
    fun knownIds(): List<String> = knownIds

    private fun ensureReady() {
        if (!initialized) {
            // No debería pasar: MainActivity lo inicializa en onCreate. Salvaguarda.
            Log.w(TAG, "Registry accessed before initialize() — providers empty")
        }
    }
}