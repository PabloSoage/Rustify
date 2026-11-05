// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/dj/WebDjVoice.kt
package com.varuna.rustify.dj

import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.WebLocale

private fun utter(text: String, language: String, voice: String, onEnd: () -> Unit): Unit = js(
    """(function() {
        speechSynthesis.cancel();
        var u = new SpeechSynthesisUtterance(text);
        u.lang = language;
        var chosen = speechSynthesis.getVoices().find(function(v) { return v.voiceURI === voice; });
        if (chosen) { u.voice = chosen; }
        u.onend = function() { onEnd(); };
        u.onerror = function() { onEnd(); };
        speechSynthesis.speak(u);
    })()"""
)

private fun silence(): Unit = js("speechSynthesis.cancel()")

private fun voiceList(language: String): String = js(
    """JSON.stringify(speechSynthesis.getVoices()
        .filter(function(v) { return !language || v.lang.toLowerCase().indexOf(language.toLowerCase()) === 0; })
        .map(function(v) { return [v.voiceURI, v.name + ' (' + v.lang + ')']; }))"""
)

/** [DjVoiceOutput] in a browser: the Web Speech API, the system's voices, as "native" is elsewhere. */
class WebDjVoice(private val platform: AppPlatform, private val duck: (Boolean) -> Unit = {}) : DjVoiceOutput {
    private var previewOnDone: (() -> Unit)? = null

    override val engines: List<String> = listOf("native")

    override fun init() = Unit

    override fun refreshConfig() = Unit

    override fun speak(text: String, force: Boolean) {
        if ((!DjSettings.voiceEnabled(platform) && !force) || text.isBlank()) return
        duck(true)
        utter(text, DjSettings.voiceLanguage(platform).ifBlank { WebLocale.tag }, DjSettings.voiceNativeName(platform)) {
            duck(false)
            previewOnDone?.let {
                previewOnDone = null
                it()
            }
        }
    }

    override fun preview(onDone: (() -> Unit)?) {
        previewOnDone = onDone
        speak(DjPhrases.previewPhrase(DjSettings.voiceLanguage(platform)), force = true)
    }

    override fun stop() {
        silence()
        duck(false)
    }

    override fun queryVoices(langCode: String, onResult: (List<Pair<String, String>>) -> Unit) {
        val pairs = Regex("\\[\"((?:[^\"\\\\]|\\\\.)*)\",\"((?:[^\"\\\\]|\\\\.)*)\"\\]").findAll(voiceList(langCode))
        onResult(pairs.map { it.groupValues[1] to it.groupValues[2] }.toList())
    }

    override suspend fun probeEdge(): Long? = null
    override suspend fun probeGoogleTranslate(): Long? = null
    override suspend fun probeVoice(voice: String): Long? = null
}
