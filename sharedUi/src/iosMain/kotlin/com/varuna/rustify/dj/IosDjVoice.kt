// sharedUi/src/iosMain/kotlin/com/varuna/rustify/dj/IosDjVoice.kt
package com.varuna.rustify.dj

import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.IosLocale
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.darwin.NSObject

/**
 * [DjVoiceOutput] on iOS: the system's own voice, iOS's "native" engine, as Android's TextToSpeech is
 * the phone's. It reads the same settings and lowers the music while it speaks: [duck] is told true
 * when a phrase starts and false when it ends. A new phrase replaces the one being spoken. The online
 * voices the phone also has play an MP3, which iOS has no player for outside the engine yet.
 */
class IosDjVoice(private val platform: AppPlatform, private val duck: (Boolean) -> Unit = {}) : DjVoiceOutput {
    private val synth = AVSpeechSynthesizer()
    private var previewOnDone: (() -> Unit)? = null

    // Held here: the synthesizer's delegate is weak.
    private val finished = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            if (synthesizer.isSpeaking()) return
            duck(false)
            previewOnDone?.let {
                previewOnDone = null
                it()
            }
        }
    }.also { synth.delegate = it }

    override val engines: List<String> = listOf("native")

    override fun init() = Unit

    override fun refreshConfig() = Unit

    override fun speak(text: String, force: Boolean) {
        if ((!DjSettings.voiceEnabled(platform) && !force) || text.isBlank()) return
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val language = DjSettings.voiceLanguage(platform).ifBlank { IosLocale.languageCode }
        val utterance = AVSpeechUtterance(string = text)
        utterance.voice = DjSettings.voiceNativeName(platform).takeIf { it.isNotBlank() }
            ?.let { AVSpeechSynthesisVoice.voiceWithIdentifier(it) }
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(language)
        duck(true)
        synth.speakUtterance(utterance)
    }

    override fun preview(onDone: (() -> Unit)?) {
        previewOnDone = onDone
        speak(DjPhrases.previewPhrase(DjSettings.voiceLanguage(platform)), force = true)
    }

    override fun stop() {
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        duck(false)
    }

    override fun queryVoices(langCode: String, onResult: (List<Pair<String, String>>) -> Unit) {
        val voices = AVSpeechSynthesisVoice.speechVoices().mapNotNull { it as? AVSpeechSynthesisVoice }
            .filter { langCode.isBlank() || it.language.startsWith(langCode, ignoreCase = true) }
            .map { it.identifier to "${it.name} (${it.language})" }
        onResult(voices)
    }

    override suspend fun probeEdge(): Long? = null
    override suspend fun probeGoogleTranslate(): Long? = null
    override suspend fun probeVoice(voice: String): Long? = null
}
