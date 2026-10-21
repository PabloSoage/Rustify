package com.varuna.rustify.bridge

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lyrics in your language (4.7), translated on the phone with ML Kit: no account, no server, and
 * no network once the language's model (~30 MB, once per language) is on the device.
 *
 * Line by line, so a translation sits under the line it translates and follows the synced
 * highlight. Kept in memory per track and language for the session.
 */
object LyricsTranslator {

    sealed class Outcome {
        data class Translated(val from: String, val lines: List<String>) : Outcome()
        /** Already in the target language, or a language ML Kit cannot translate. */
        data class NotNeeded(val language: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    private val cache = ConcurrentHashMap<String, Outcome>()

    /** The app's language as an ML Kit code, English when it is not one ML Kit knows. */
    fun targetLanguage(): String =
        TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH

    suspend fun translate(
        key: String,
        lines: List<String>,
        target: String = targetLanguage(),
        onProgress: (Outcome) -> Unit = {},
    ): Outcome {
        cache["$key|$target"]?.let { return it }
        val sample = lines.filter { it.isNotBlank() }.take(20).joinToString("\n")
        if (sample.isBlank()) return Outcome.NotNeeded("und")
        val outcome = runCatching {
            val detected = LanguageIdentification.getClient().identifyLanguage(sample).await()
            val source = TranslateLanguage.fromLanguageTag(detected)
            if (detected == "und" || source == null || source == target) {
                Outcome.NotNeeded(detected)
            } else {
                val translator = Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
                )
                try {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    // Line by line, and shown as it goes: on a slow phone a line takes a couple of
                    // seconds, so waiting for the whole song meant minutes of a spinner. (Translating
                    // the song in one call does not help: the model splits it back into sentences.)
                    // Repeated lines — every chorus — are translated once.
                    // Blank lines stay blank: they are the gaps between verses, not text.
                    val done = HashMap<String, String>()
                    val out = MutableList(lines.size) { "" }
                    for ((i, l) in lines.withIndex()) {
                        if (l.isBlank()) continue
                        out[i] = done.getOrPut(l) { translator.translate(l).await() }
                        onProgress(Outcome.Translated(detected, out.toList()))
                    }
                    Outcome.Translated(detected, out)
                } finally {
                    translator.close()
                }
            }
        }.getOrElse { Outcome.Failed(it.message ?: it.javaClass.simpleName) }
        if (outcome !is Outcome.Failed) cache["$key|$target"] = outcome
        return outcome
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }
}
