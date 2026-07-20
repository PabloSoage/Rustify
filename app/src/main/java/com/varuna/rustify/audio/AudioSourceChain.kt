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
            // E101: isAvailableFor() must be INSIDE the timeout. It can do network I/O (e.g. Invidious
            // fetching its instance directory), and when it was outside, a stalled directory fetch hung
            // the whole resolution with no upper bound — a prime cause of the play-time ANR. A null
            // result means "not available for this track" → skip quietly (no error), same as before.
            val r = runCatching {
                withTimeout(perProviderTimeoutMs) {
                    if (!p.isAvailableFor(track)) null
                    else p.resolveStreamUrl(track, hint).getOrThrow()
                }
            }
            val info = r.getOrNull()
            if (r.isSuccess && info != null) {
                lastGood[trackId] = p.capabilities.id
                return Result.success(p.capabilities.id to info)
            }
            if (r.isSuccess) continue // provider unavailable for this track — skip without logging an error
            r.onFailure { e ->
                // Tag the error with the provider id so the aggregated message names who failed and why.
                errors += labelError(p.capabilities.id, e)
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
            } catch (e: Exception) { errors += labelError(p.capabilities.id, e); continue }
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
                errors += labelError(p.capabilities.id, e)
                if (lastGood[trackId] == p.capabilities.id) lastGood.remove(trackId)
            }
        }
        return Result.failure(AudioSourceChainException(errors))
    }

    /** Invalida la caché "provider que funcionó" para un track (p. ej. ante 403/410). */
    fun invalidate(trackId: String) { lastGood.remove(trackId) }

    /**
     * Wraps a provider failure with its id so [AudioSourceChainException]'s aggregated message reads
     * e.g. "ytdlp: resolver returned empty YouTube id" instead of an anonymous cause. A timeout is
     * reported explicitly (it arrives here as a [kotlinx.coroutines.TimeoutCancellationException]).
     */
    private fun labelError(providerId: String, e: Throwable): Throwable {
        val reason = when (e) {
            is kotlinx.coroutines.TimeoutCancellationException -> "timed out"
            else -> e.message ?: e::class.simpleName.orEmpty()
        }
        return Exception("$providerId: $reason", e)
    }

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