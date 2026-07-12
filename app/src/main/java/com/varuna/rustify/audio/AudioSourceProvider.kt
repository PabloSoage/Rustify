package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import java.io.File

/**
 * E60 — Abstracción de backends de audio.
 *
 * Información sobre un stream reproducible por ExoPlayer, producida por un
 * [AudioSourceProvider]. El [uri] es lo único que ExoPlayer consume; el resto
 * es metadata orientativa (caducidad, mime) para que el motor decida saltos.
 */
data class StreamInfo(
    val uri: String,                  // http(s):// | file:// | content://
    val expiresAtMs: Long? = null,    // googlevideo ~6h; null = desconocida
    val mimeType: String? = null,     // "audio/webm", "audio/mp4"... opcional
    val requiresProxy: Boolean = false // p. ej. Deemix cifrado → datasource propio
)

/**
 * Capacidades declaradas por un provider: filtra lacadena (sólo canStream
 * participan en resolveStreamUrl; sólo canDownload en downloadTo) y alimenta
 * la UI de ajustes (nombre, requisitos, calidad).
 */
data class AudioSourceCapabilities(
    val id: String,                   // clave estable: "ytdlp" | "invidious" | "deemix"
    val displayNameRes: Int,          // R.string.* para la UI
    val canStream: Boolean,
    val canDownload: Boolean,
    val requiresToken: Boolean = false,
    val maxQualityKbps: Int? = null
)

/**
 * Contrato único que consumen reproductor ([com.varuna.rustify.player.AudioPlayerService])
 * y descargas ([com.varuna.rustify.bridge.DownloadManager]). Desacopla la obtención
 * de la URL/bytes de YouTube del anterior yt-dlp hardcodeado, permitiendo múltiples
 * providers encadenados con fallback (E61 Invidious / E62 Deemix).
 *
 * El [hint] de [resolveStreamUrl] es un `youtubeId` explícito (alternativa elegida
 * manualmente): cuando se pasa, el provider YouTube lo usa directo sin re-resolver
 * por metadata (paridad con `NativeEngine.resolveYouTubeIdNative(id, hint)`).
 */
interface AudioSourceProvider {
    val capabilities: AudioSourceCapabilities

    /** Bootstrap perezoso (yt-dlp init/update, health-check Invidious, validar token Deemix). No bloquea. */
    fun initialize() {}

    /** ¿Está listo para servir a este track ahora mismo? (token válido, instancia sana...). */
    suspend fun isAvailableFor(track: FullTrack): Boolean = capabilities.canStream

    /** Resuelve una URL/URI reproducible. Cancelable (coroutine) + respeta timeout externo. */
    suspend fun resolveStreamUrl(track: FullTrack, hint: String? = null): Result<StreamInfo>

    /** Descarga a un fichero temporal (el caller lo copia a SAF). onProgress 0..100. */
    suspend fun downloadTo(
        track: FullTrack,
        dst: File,
        onProgress: (Int) -> Unit = {}
    ): Result<File>
}

/**
 * Lanzada por [AudioSourceChain] cuando TODOS los providers de la cadena fallan.
 * Transporta la lista de errores por provider para diagnóstico.
 */
class AudioSourceChainException(val errors: List<Throwable>) : Exception(
    "All audio providers failed: " + errors.joinToString { it.message ?: it::class.simpleName.orEmpty() }
)