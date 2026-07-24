package com.varuna.rustify.dj

import com.varuna.rustify.bridge.FullTrack
import kotlin.math.sqrt

/**
 * Song similarity by metadata, on-device, with no model, network, or keys.
 *
 * Each track becomes a sparse vector of weighted tokens (artists > title > album), compared by cosine
 * similarity. This is the lightweight alternative to a vector database like Qdrant: for a personal
 * library (thousands of songs) brute-force cosine is instant, so neither an ANN nor a server is
 * needed. Used to rank candidates by resemblance to a seed/centroid.
 */
object DjVectors {

    private val STOP = setOf(
        "the", "a", "an", "of", "and", "feat", "ft", "with", "remaster", "remastered", "version",
        "edit", "radio", "live", "original", "mix", "de", "la", "el", "los", "las", "y", "con"
    )

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 2 && it !in STOP }

    /** Sparse token→weight vector of a track (artists weigh more than title/album). */
    fun vectorize(track: FullTrack): Map<String, Float> {
        val v = HashMap<String, Float>()
        fun add(text: String, w: Float) { tokens(text).forEach { v[it] = (v[it] ?: 0f) + w } }
        track.artists.forEach { add(it.name, 3f) }
        add(track.name, 1.5f)
        track.album?.name?.let { add(it, 1f) }
        return v
    }

    /** Centroid (sum) of the vectors of a list of tracks — the "average sound" of the set. */
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

    /** Sorts [candidates] by cosine similarity (descending) to the [seed] centroid. */
    fun rankBySimilarity(candidates: List<FullTrack>, seed: Map<String, Float>): List<FullTrack> {
        if (seed.isEmpty()) return candidates
        return candidates.sortedByDescending { cosine(vectorize(it), seed) }
    }
}
