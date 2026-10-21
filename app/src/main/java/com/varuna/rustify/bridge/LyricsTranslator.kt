package com.varuna.rustify.bridge

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
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
        /**
         * Nothing to translate: already in the target language, a language ML Kit cannot translate,
         * romanised text (`ja-Latn`: romaji is not what the Japanese model reads) or `und`.
         */
        data class NotNeeded(val language: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
        /** Progress only: the language's model is being downloaded first (~30 MB, once). */
        data class Downloading(val language: String) : Outcome()
    }

    /**
     * The language a script gives away, before asking ML Kit (4.7.1). Over a whole song the
     * statistical guess can land on English when a Japanese chorus has English lines in it; kana
     * cannot be anything but Japanese, hangul anything but Korean. Han characters without kana are
     * read as Chinese. Null when the script says nothing (Latin, Cyrillic…): ML Kit decides then.
     */
    fun scriptLanguage(text: String): String? {
        var kana = 0; var hangul = 0; var han = 0; var letters = 0
        text.codePoints().forEach { cp ->
            if (!Character.isLetter(cp)) return@forEach
            letters++
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.HAN -> han++
                else -> {}
            }
        }
        // A few CJK characters in a Latin song (a title, a name) do not make it a CJK song.
        if (letters == 0 || (kana + hangul + han) * 5 < letters) return null
        return when {
            kana > 0 -> TranslateLanguage.JAPANESE
            hangul >= han -> TranslateLanguage.KOREAN
            else -> TranslateLanguage.CHINESE
        }
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
            val detected = scriptLanguage(sample)
                ?: LanguageIdentification.getClient().identifyLanguage(sample).await()
            val source = TranslateLanguage.fromLanguageTag(detected)
            if (detected == "und" || source == null || source == target) {
                Outcome.NotNeeded(detected)
            } else {
                val translator = Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
                )
                try {
                    val models = RemoteModelManager.getInstance()
                    val missing = listOf(source, target).filter { it != TranslateLanguage.ENGLISH }
                        .any { !models.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await() }
                    if (missing) onProgress(Outcome.Downloading(source))
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    // Line by line, and shown as it goes: on a slow phone a line takes a couple of
                    // seconds, so waiting for the whole song meant minutes of a spinner. (Translating
                    // the song in one call does not help: the model splits it back into sentences.)
                    // Repeated lines — every chorus — are translated once.
                    // Blank lines stay blank: they are the gaps between verses, not text.
                    val done = HashMap<String, String>()
                    val out = MutableList(lines.size) { "" }
                    // Lyrics sites often interleave a romanised line under each CJK one; the model
                    // can only echo those back (in capitals), so they get no translation.
                    val cjk = source in setOf(TranslateLanguage.JAPANESE, TranslateLanguage.KOREAN, TranslateLanguage.CHINESE)
                    for ((i, l) in lines.withIndex()) {
                        if (l.isBlank() || (cjk && scriptLanguage(l) == null)) continue
                        out[i] = done.getOrPut(l) { translator.translate(l).await() }
                            .takeUnless { it.equals(l, ignoreCase = true) }.orEmpty()
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
