// sharedUi/src/iosMain/kotlin/com/varuna/rustify/dj/IosDjVoice.kt
package com.varuna.rustify.dj

import com.varuna.rustify.platform.IO
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.IosLocale
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.darwin.NSObject

/**
 * [DjVoiceOutput] on iOS: supports both the system's own voice ([AVSpeechSynthesizer], the "native"
 * engine) and online voices (Pollinations, Google Translate, OpenAI).
 *
 * Online voices play their MP3 audio through [AVAudioPlayer] with music ducking ([duck]).
 */
class IosDjVoice(private val platform: AppPlatform, private val duck: (Boolean) -> Unit = {}) : DjVoiceOutput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val synth = AVSpeechSynthesizer()
    private var activePlayer: AVAudioPlayer? = null
    private var previewOnDone: (() -> Unit)? = null

    // Held here: the synthesizer's delegate is weak.
    private val finishedSynth = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            if (synthesizer.isSpeaking()) return
            duck(false)
            finishPreview()
        }
    }.also { synth.delegate = it }

    // Held here: AVAudioPlayer delegate
    private val finishedPlayer = object : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            activePlayer = null
            duck(false)
            finishPreview()
        }
    }

    override val engines: List<String> = listOf("native", "pollinations", "gtranslate", "openai")

    override fun init() = Unit

    override fun refreshConfig() = Unit

    override fun speak(text: String, force: Boolean) {
        if ((!DjSettings.voiceEnabled(platform) && !force) || text.isBlank()) return
        stopCurrent()

        val engine = DjSettings.ttsEngine(platform)
        if (engine in listOf("pollinations", "gtranslate", "openai")) {
            scope.launch {
                val bytes = runCatching { OnlineDjTts.fetchAudio(platform, engine, text) }.getOrNull()
                if (bytes != null && bytes.size >= 512) {
                    val played = playMp3(bytes)
                    if (!played) {
                        speakNative(text)
                    }
                } else {
                    speakNative(text)
                }
            }
        } else {
            speakNative(text)
        }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun playMp3(bytes: ByteArray): Boolean {
        return runCatching {
            val tmpPath = "${NSTemporaryDirectory()}dj_voice.mp3"
            val nsData = bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
            NSFileManager.defaultManager.createFileAtPath(tmpPath, nsData, null)
            val url = NSURL.fileURLWithPath(tmpPath)
            val player = AVAudioPlayer(contentsOfURL = url, error = null)
            player.delegate = finishedPlayer
            player.prepareToPlay()
            activePlayer = player
            duck(true)
            player.play()
            true
        }.getOrDefault(false)
    }

    private fun speakNative(text: String) {
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
        stopCurrent()
        duck(false)
    }

    private fun stopCurrent() {
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        activePlayer?.stop()
        activePlayer = null
    }

    private fun finishPreview() {
        val cb = previewOnDone ?: return
        previewOnDone = null
        cb()
    }

    override fun queryVoices(langCode: String, onResult: (List<Pair<String, String>>) -> Unit) {
        val voices = AVSpeechSynthesisVoice.speechVoices().mapNotNull { it as? AVSpeechSynthesisVoice }
            .filter { langCode.isBlank() || it.language.startsWith(langCode, ignoreCase = true) }
            .map { it.identifier to "${it.name} (${it.language})" }
        onResult(voices)
    }

    override suspend fun probeEdge(): Long? = null
    override suspend fun probeGoogleTranslate(): Long? = OnlineDjTts.probeGoogleTranslate(platform)
    override suspend fun probeVoice(voice: String): Long? = OnlineDjTts.probeVoice(voice)
}
