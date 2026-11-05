// sharedUi/src/iosMain/kotlin/com/varuna/rustify/dj/IosDjListener.kt
package com.varuna.rustify.dj

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * [DjListener] on iOS using Apple's [SFSpeechRecognizer] and [AVAudioEngine].
 *
 * Switches [AVAudioSession] to `.playAndRecord` with `.defaultToSpeaker` while actively listening,
 * and restores `.playback` immediately upon completion or stop so background music playback is not disturbed.
 */
class IosDjListener : DjListener {
    private var audioEngine: AVAudioEngine? = null
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest? = null
    private var recognitionTask: SFSpeechRecognitionTask? = null
    private var isListening = false

    override fun isAvailable(): Boolean {
        val status = SFSpeechRecognizer.authorizationStatus()
        if (status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusRestricted ||
            status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied
        ) {
            return false
        }
        val recognizer = SFSpeechRecognizer(locale = NSLocale.currentLocale)
        return recognizer.isAvailable()
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun listen(languageTag: String, onResult: (String) -> Unit, onError: () -> Unit) {
        stop()

        SFSpeechRecognizer.requestAuthorization { authStatus ->
            dispatch_async(dispatch_get_main_queue()) {
                if (authStatus != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    onError()
                    return@dispatch_async
                }

                val session = AVAudioSession.sharedInstance()
                session.requestRecordPermission { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (!granted) {
                            onError()
                            return@dispatch_async
                        }
                        startRecognition(languageTag, onResult, onError)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun startRecognition(languageTag: String, onResult: (String) -> Unit, onError: () -> Unit) {
        val locale = if (languageTag.isNotBlank()) NSLocale(languageTag) else NSLocale.currentLocale
        val recognizer = SFSpeechRecognizer(locale = locale)
        if (!recognizer.isAvailable()) {
            onError()
            return
        }

        val session = AVAudioSession.sharedInstance()
        try {
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                AVAudioSessionModeDefault,
                AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetooth,
                null
            )
        } catch (_: Throwable) {
            restorePlaybackSession()
            onError()
            return
        }

        val engine = AVAudioEngine()
        val request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true

        val inputNode = engine.inputNode
        val recordingFormat = inputNode.outputFormatForBus(0u)
        inputNode.installTapOnBus(0u, bufferSize = 1024u, format = recordingFormat) { buffer, _ ->
            if (buffer != null) {
                request.appendAudioPCMBuffer(buffer)
            }
        }

        try {
            engine.prepare()
            engine.startAndReturnError(null)
        } catch (_: Throwable) {
            inputNode.removeTapOnBus(0u)
            restorePlaybackSession()
            onError()
            return
        }

        audioEngine = engine
        recognitionRequest = request
        isListening = true

        var hasFinished = false
        val task = recognizer.recognitionTaskWithRequest(request) { result, error ->
            if (hasFinished) return@recognitionTaskWithRequest

            if (result != null) {
                val transcription = result.bestTranscription.formattedString
                if (result.isFinal()) {
                    hasFinished = true
                    stop()
                    onResult(transcription)
                }
            }
            if (error != null) {
                hasFinished = true
                stop()
                onError()
            }
        }
        recognitionTask = task
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun stop() {
        if (!isListening && audioEngine == null && recognitionTask == null) return
        isListening = false

        runCatching {
            audioEngine?.inputNode?.removeTapOnBus(0u)
            audioEngine?.stop()
        }
        audioEngine = null

        runCatching {
            recognitionRequest?.endAudio()
        }
        recognitionRequest = null

        runCatching {
            recognitionTask?.cancel()
        }
        recognitionTask = null

        restorePlaybackSession()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun restorePlaybackSession() {
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                AVAudioSessionCategoryPlayback,
                AVAudioSessionModeDefault,
                0u,
                null
            )
        }
    }
}
