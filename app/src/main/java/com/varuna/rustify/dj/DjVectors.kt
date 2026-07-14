package com.varuna.rustify.dj

import com.varuna.rustify.bridge.FullTrack
import kotlin.math.sqrt

/**
 * E90 — Similitud de canciones por METADATOS, on-device, sin modelo ni red ni claves.
 *
 * Cada track se convierte en un vector disperso de tokens ponderados (artistas > título > álbum) y se
 * comparan por **coseno**. Es la alternativa ligera a una base de datos vectorial tipo Qdrant: para una
 * biblioteca personal (miles de temas) el coseno por fuerza bruta es instantáneo, así que no hace falta
 * ni un ANN ni un servidor. Se usa para ordenar candidatos por parecido a una semilla/centroide.
 */
object DjVectors {

    private val STOP = setOf(
        "the", "a", "an", "of", "and", "feat", "ft", "with", "remaster", "remastered", "version",
        "edit", "radio", "live", "original", "mix", "de", "la", "el", "los", "las", "y", "con"
    )

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 2 && it !in STOP }

    /** Vector disperso token→peso de un track (los artistas pesan más que título/álbum). */
    fun vectorize(track: FullTrack): Map<String, Float> {
        val v = HashMap<String, Float>()
        fun add(text: String, w: Float) { tokens(text).forEach { v[it] = (v[it] ?: 0f) + w } }
        track.artists.forEach { add(it.name, 3f) }
        add(track.name, 1.5f)
        track.album?.name?.let { add(it, 1f) }
        return v
    }

    /** Centroide (suma) de los vectores de una lista de tracks — el "sonido medio" del conjunto. */
    fun centroid(tracks: List<FullTrack>): Map<String, Float> {
        val c = HashMap<String, Float>()
        tracks.forEach { t -> vectorize(t).forEach { (k, w) -> c[k] = (c[k] ?: 0f) + w } }
        return c
    }

    fun cosine(a: Map<String, Float>, b: Map<String, Float>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var dot = 0f
        for ((k, w) in small) large[k]?.let { dot += w * it }
        if (dot == 0f) return 0f
        var na = 0f; for (w in a.values) na += w * w
        var nb = 0f; for (w in b.values) nb += w * w
        return dot / (sqrt(na) * sqrt(nb))
    }

    /** Ordena [candidates] por similitud coseno DESC respecto al centroide [seed]. */
    fun rankBySimilarity(candidates: List<FullTrack>, seed: Map<String, Float>): List<FullTrack> {
        if (seed.isEmpty()) return candidates
        return candidates.sortedByDescending { cosine(vectorize(it), seed) }
    }
}
