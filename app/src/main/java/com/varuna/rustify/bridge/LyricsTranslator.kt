package com.varuna.rustify.bridge

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.varuna.rustify.ui.LyricsTranslation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lyrics in your language (4.7), translated on the phone with ML Kit: no account, no server, and
 * no network once the language's model (~30 MB, once per language) is on the device.
 *
 * One translation per line, so it sits under the line it translates and follows the synced
 * highlight. Since 4.9 the lines are translated with the whole song as context by Gemini Nano where
 * the phone has it ([LyricsInContext]); ML Kit, which only ever sees one sentence, translates what is
 * left. Kept in memory per track and language for the session.
 */
object LyricsTranslator {
    // What a translation comes to is the shared [LyricsTranslation], so the track screen reads it on
    // every platform.

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

    private val cache = ConcurrentHashMap<String, LyricsTranslation>()

    /** The app's language as an ML Kit code, English when it is not one ML Kit knows. */
    fun targetLanguage(): String =
        TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH

    /**
     * What to translate, decided line by line (4.7.1): [source] is the language the translated lines
     * are in, [lines] their indices, [dominant] the most common language of the song (what to name
     * when there is nothing to translate).
     */
    data class Plan(val source: String?, val lines: Set<Int>, val dominant: String)

    /**
     * Guessing one language for the whole song got Spanish lyrics that open in English read as
     * English, and the model then "translated" the Spanish into broken Spanish. So each line carries
     * its own language: the source is the commonest one that is not [target] (and that ML Kit can
     * translate), only its lines are translated, and lines already in [target] are left alone.
     *
     * [languages] has one entry per lyrics line, null for a blank one, "und" when unknown. A source
     * on fewer than a fifth of the lines is a stray phrase, not something to translate. Unknown lines
     * go with the source only when it is the song's main language.
     */
    fun plan(languages: List<String?>, target: String, translatable: (String) -> Boolean): Plan {
        val known = languages.filterNotNull().filter { it != "und" }
        if (known.isEmpty()) return Plan(null, emptySet(), "und")
        val counts = known.groupingBy { it }.eachCount()
        val dominant = counts.maxBy { it.value }.key
        val source = counts.filterKeys { it != target && translatable(it) }.maxByOrNull { it.value }
            ?.takeIf { it.value * 5 >= known.size }?.key
            ?: return Plan(null, emptySet(), dominant)
        val withUnknown = source == dominant
        val idx = languages.indices.filter { i ->
            val l = languages[i]
            l == source || (withUnknown && l == "und")
        }.toSet()
        return Plan(source, idx, dominant)
    }

    suspend fun translate(
        key: String,
        lines: List<String>,
        target: String = targetLanguage(),
        onProgress: (LyricsTranslation) -> Unit = {},
    ): LyricsTranslation {
        cache["$key|$target"]?.let { return it }
        if (lines.none { it.isNotBlank() }) return LyricsTranslation.NotNeeded("und")
        val outcome = runCatching {
            // Han without kana reads as Chinese line by line; in a song with kana it is Japanese.
            val songScript = scriptLanguage(lines.joinToString("\n"))
            val identifier = LanguageIdentification.getClient()
            val byLine = HashMap<String, String>()
            val languages = lines.map { l ->
                if (l.isBlank()) null
                else byLine.getOrPut(l) {
                    when (val s = scriptLanguage(l)) {
                        TranslateLanguage.CHINESE -> songScript ?: s
                        null -> identifier.identifyLanguage(l).await()
                        else -> s
                    }
                }
            }
            identifier.close()
            val plan = plan(languages, target) { TranslateLanguage.fromLanguageTag(it) != null }
            val source = plan.source
            if (source == null) {
                LyricsTranslation.NotNeeded(plan.dominant)
            } else {
                val out = MutableList(lines.size) { "" }
                val wanted = plan.lines.sorted().filter { lines[it].isNotBlank() }
                // Repeated lines — every chorus — are translated once, at their first appearance.
                val firsts = LinkedHashMap<String, Int>().apply { wanted.forEach { putIfAbsent(lines[it], it) } }
                val done = HashMap<String, String>()
                fun publish() {
                    for (i in wanted) done[lines[i]]?.let { t ->
                        out[i] = t.takeUnless { it.equals(lines[i], ignoreCase = true) }.orEmpty()
                    }
                    onProgress(LyricsTranslation.Translated(source, out.toList()))
                }

                // With the whole song in view first (LyricsInContext), then ML Kit for whatever is
                // left: all of it on a phone without Nano, the odd line Nano did not give back.
                inContext(source, target, lines, firsts.values.toList()) { got ->
                    got.forEach { (i, t) -> done[lines[i]] = t }
                    publish()
                }

                val missing = firsts.keys.filter { it !in done }
                if (missing.isNotEmpty()) {
                    val translator = Translation.getClient(
                        TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
                    )
                    try {
                        val models = RemoteModelManager.getInstance()
                        listOf(source, target).filter { it != TranslateLanguage.ENGLISH }
                            .firstOrNull { !models.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await() }
                            ?.let { onProgress(LyricsTranslation.Downloading(it)) }
                        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                        // Line by line, and shown as it goes: on a slow phone a line takes a couple
                        // of seconds, so waiting for the whole song meant minutes of a spinner.
                        for (l in missing) {
                            done[l] = translator.translate(l).await()
                            publish()
                        }
                    } finally {
                        translator.close()
                    }
                }
                publish()
                LyricsTranslation.Translated(source, out)
            }
        }.getOrElse { LyricsTranslation.Failed(it.message ?: it.javaClass.simpleName) }
        if (outcome !is LyricsTranslation.Failed) cache["$key|$target"] = outcome
        return outcome
    }

    /**
     * Translates the lines at [indices] with Gemini Nano, a block at a time, each block reading the
     * whole song. Does nothing on a phone where the model is not ready, and stops at the first
     * failed block: what is left goes to ML Kit.
     */
    private suspend fun inContext(
        source: String,
        target: String,
        lines: List<String>,
        indices: List<Int>,
        onBlock: (Map<Int, String>) -> Unit,
    ) {
        val nano = com.varuna.rustify.dj.OnDeviceModel
        if (nano.refresh() != com.varuna.rustify.dj.OnDeviceModel.Status.READY) return
        val song = lines.filter { it.isNotBlank() }
        for (block in indices.chunked(LyricsInContext.BLOCK_LINES)) {
            val prompt = LyricsInContext.prompt(source, target, song, block.map { it to lines[it] })
            val started = android.os.SystemClock.elapsedRealtime()
            val answer = runCatching {
                kotlinx.coroutines.withTimeout(30_000) {
                    nano.model.generateContent(
                        com.google.mlkit.genai.prompt.generateContentRequest(com.google.mlkit.genai.prompt.TextPart(prompt)) {
                            temperature = 0.2f
                            maxOutputTokens = 256
                        }
                    )
                }.candidates.firstOrNull()?.text.orEmpty()
            }.onFailure { android.util.Log.w("LyricsTranslator", "on-device block failed: ${it.message}") }
                .getOrNull() ?: return
            val got = LyricsInContext.parse(answer, block.toSet())
            android.util.Log.i(
                "LyricsTranslator",
                "on-device block: ${got.size}/${block.size} lines in ${android.os.SystemClock.elapsedRealtime() - started} ms"
            )
            onBlock(got)
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }
}
