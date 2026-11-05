// sharedUi/src/commonMain/kotlin/com/varuna/rustify/dj/DjSeams.kt
package com.varuna.rustify.dj

/**
 * The DJ's voice, as the shared screens and [DjAutoController] see it: what speaks the intros and the
 * mood announcements. Android's is `DjVoice` (TextToSpeech and the cloud voices), which also lowers
 * the music while it speaks; each platform hands its own in through `AppServices.djVoice`.
 *
 * Like `DjVoice.speak`, a new phrase replaces the one being spoken.
 */
interface DjVoiceOutput {
    /** Gets the engine ready ahead of the first phrase; cheap to call again. */
    fun init()

    /** Speaks [text] unless the voice is off in Settings; [force] speaks it anyway (the preview). */
    fun speak(text: String, force: Boolean = false)

    /** Stops whatever is being spoken. */
    fun stop()

    /**
     * The voice engines this platform has, by the code Settings stores in [DjSettings.KEY_TTS_ENGINE]
     * ("native", "edge", "gtranslate", "pollinations", "openai"), in the order Settings lists them.
     */
    val engines: List<String>

    /** Applies the language and voice just chosen in Settings. */
    fun refreshConfig()

    /** Speaks the test phrase in the chosen voice, even with the voice off; [onDone] when it ends. */
    fun preview(onDone: (() -> Unit)?)

    /** The system's voices for [langCode] (blank: all) as `(id, label)`, delivered on the main thread. */
    fun queryVoices(langCode: String, onResult: (List<Pair<String, String>>) -> Unit)

    // How long each online voice takes to answer, in ms, or null when it does not; Settings shows a
    // dot by each ([DjProviders.classify]).
    suspend fun probeEdge(): Long?
    suspend fun probeGoogleTranslate(): Long?
    suspend fun probeVoice(voice: String): Long?
}

/**
 * Listening for a spoken request to the DJ ("something more upbeat"). Android's is `DjSpeech`
 * (SpeechRecognizer) behind the RECORD_AUDIO permission, which [listen] asks for first; a platform
 * with no recogniser answers false to [isAvailable].
 */
interface DjListener {
    fun isAvailable(): Boolean

    /** Listens for one phrase; [onError] when nothing could be heard or recognised. */
    fun listen(languageTag: String, onResult: (String) -> Unit, onError: () -> Unit)

    fun stop()
}
