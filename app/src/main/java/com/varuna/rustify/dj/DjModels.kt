package com.varuna.rustify.dj

import com.varuna.rustify.bridge.FullTrack

/**
 * E90 — DJ IA. Modelos compartidos entre los providers y el motor.
 *
 * Diseño (ver docs/90-ai-dj-assistant.md): un [DjProvider] recibe el [DjContext] (contexto del
 * usuario: top artistas/canciones de las métricas + cola actual) y una petición en lenguaje
 * natural, y devuelve un [DjPlan] con una **frase de intro** y **semillas** (artistas/canciones/
 * queries) — NUNCA URIs finales. El [DjEngine] resuelve esas semillas a tracks reales vía
 * SpotifyRepository (search/radio) y construye la cola.
 */

/** Modo de proveedor de DJ, persistido en `rustify_settings` bajo la clave [DjSettings.KEY_MODE]. */
enum class DjMode { HEURISTIC, API, LOCAL }

/**
 * Una semilla que el [DjEngine] resolverá a tracks. El LLM/heurística devuelve semillas
 * "blandas" (nombres/queries), no ids ni URIs — la app las materializa contra Spotify.
 */
data class DjSeed(
    val type: Type,
    /** Texto de la semilla: nombre de artista, "Artista - Canción", o una query de búsqueda libre. */
    val value: String
) {
    enum class Type { ARTIST, TRACK, QUERY }
}

/**
 * Contexto que se le pasa al provider. Todo se deriva SOLO de lecturas públicas
 * (ListeningTracker.loadEvents + estado del player). Ver [DjContextBuilder].
 */
data class DjContext(
    val topArtists: List<String>,
    val topTracks: List<String>,
    /** "Artista — Canción" de la pista actual, o null. */
    val nowPlaying: String?,
    /** Nombres de las próximas pistas en cola (para que el DJ no repita). */
    val queuePreview: List<String>,
    /** Idioma de la app (es/en/ja/…), para que el LLM responda la intro en ese idioma. */
    val language: String
)

/**
 * Resultado del provider. [intro] es la frase hablada/mostrada del DJ; una de [tracks] (ya
 * resueltas) o [seeds] (a resolver por el engine) estará poblada según el provider.
 */
data class DjPlan(
    val intro: String,
    val seeds: List<DjSeed> = emptyList(),
    val tracks: List<FullTrack> = emptyList()
)

/**
 * Contrato de un proveedor de DJ. Puro: no toca el player ni la UI; solo produce un plan.
 * Debe degradar con gracia (no lanzar) devolviendo un plan con seeds derivadas del contexto
 * cuando falle una llamada externa.
 */
interface DjProvider {
    suspend fun plan(context: DjContext, request: String): DjPlan
}
