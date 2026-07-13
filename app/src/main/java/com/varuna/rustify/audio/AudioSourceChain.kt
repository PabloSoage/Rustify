package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * E60 — Motor de fallback en cadena. Recorre los providers por prioridad (ya
 * ordenados desde [AudioBackendSettings]) con [perProviderTimeoutMs] y salta al
 * siguiente ante fallo/timeout, cacheando qué provider funcionó por `trackId`
 * para intentar primero ése la próxima vez (ultilidad de single-provider hoy,
 * clave cuando existan E61/E62).
 *
 * Hay dos órdenes independientes (stream vs descarga), por decisión del usuario:
 * se instancian dos cadenas distintas desde [AudioSourceRegistry].
 */
class AudioSourceChain(
    private val providers: List<AudioSourceProvider>,
    private val perProviderTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** trackId -> provider.id que sirvió el stream la última vez. Compartido entre cadenas
     *  para que el cacheo persista entre llamadas (el registry lo mantiene). */
    private val lastGood: ConcurrentHashMap<String, String> = ConcurrentHashMap()
) {

    /** Resuelve URL reproducible; devuelve (providerId, StreamInfo) o failure con todos los errores. */
    suspend fun resolveStreamUrl(track: FullTrack, hint: String? = null): Result<Pair<String, StreamInfo>> {
        val trackId = track.id ?: return Result.failure(IllegalStateException("track has no id"))
        val ordered = reorderPreferred(providers, lastGood[trackId])
        val errors = mutableListOf<Throwable>()
        for (p in ordered) {
            if (!p.capabilities.canStream) continue
            try {
                if (!p.isAvailableFor(track)) continue
            } catch (e: Exception) { errors += e; continue }
            val r = runCatching {
                withTimeout(perProviderTimeoutMs) { p.resolveStreamUrl(track, hint).getOrThrow() }
            }
            if (r.isSuccess) {
                val info = r.getOrNull()!!
                lastGood[trackId] = p.capabilities.id
                return Result.success(p.capabilities.id to info)
            }
            r.onFailure { e ->
                errors += e
                if (lastGood[trackId] == p.capabilities.id) lastGood.remove(trackId)
            }
        }
        return Result.failure(AudioSourceChainException(errors))
    }

    /** Descarga a [dst]; mismo patrón de fallback filtrando por capabilities.canDownload. */
    suspend fun downloadTo(
        track: FullTrack,
        dst: File,
        onProgress: (Int) -> Unit = {}
    ): Result<Pair<String, File>> {
        val trackId = track.id ?: return Result.failure(IllegalStateException("track has no id"))
        val ordered = reorderPreferred(providers, lastGood[trackId])
        val errors = mutableListOf<Throwable>()
        for (p in ordered) {
            if (!p.capabilities.canDownload) continue
            try {
                if (!p.isAvailableFor(track)) continue
            } catch (e: Exception) { errors += e; continue }
            // A full download (yt-dlp -x mp3 320K) routinely exceeds the resolve timeout; use a much
            // larger ceiling so real downloads aren't cancelled mid-transfer (E60 fix).
            val r = runCatching {
                withTimeout(DOWNLOAD_TIMEOUT_MS) { p.downloadTo(track, dst, onProgress).getOrThrow() }
            }
            if (r.isSuccess) {
                lastGood[trackId] = p.capabilities.id
                return Result.success(p.capabilities.id to r.getOrNull()!!)
            }
            r.onFailure { e ->
                errors += e
                if (lastGood[trackId] == p.capabilities.id) lastGood.remove(trackId)
            }
        }
        return Result.failure(AudioSourceChainException(errors))
    }

    /** Invalida la caché "provider que funcionó" para un track (p. ej. ante 403/410). */
    fun invalidate(trackId: String) { lastGood.remove(trackId) }

    private fun reorderPreferred(
        all: List<AudioSourceProvider>,
        preferredId: String?
    ): List<AudioSourceProvider> {
        if (preferredId.isNullOrBlank()) return all
        val pref = all.firstOrNull { it.capabilities.id == preferredId } ?: return all
        return listOf(pref) + all.filter { it.capabilities.id != preferredId }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        /** Downloads are full file transfers (can take minutes); resolve-sized timeouts would abort them. */
        const val DOWNLOAD_TIMEOUT_MS = 20 * 60_000L
    }
}