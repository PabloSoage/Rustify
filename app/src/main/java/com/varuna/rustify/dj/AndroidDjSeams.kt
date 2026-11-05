// app/src/main/java/com/varuna/rustify/dj/AndroidDjSeams.kt
package com.varuna.rustify.dj

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** [DjVoiceOutput] on the phone: [DjVoice], as the DJ screen and the autonomous DJ called it. */
class AndroidDjVoice(context: Context) : DjVoiceOutput {
    private val app = context.applicationContext

    override fun init() = DjVoice.init(app)
    override fun speak(text: String, force: Boolean) = DjVoice.speak(app, text, force)
    override fun stop() = DjVoice.stop()

    override val engines: List<String> = listOf("native", "edge", "gtranslate", "pollinations", "openai")
    override fun refreshConfig() = DjVoice.refreshConfig(app)
    override fun preview(onDone: (() -> Unit)?) = DjVoice.preview(app, onDone)
    override fun queryVoices(langCode: String, onResult: (List<Pair<String, String>>) -> Unit) =
        DjVoice.queryVoices(app, langCode, onResult)
    override suspend fun probeEdge(): Long? = DjVoice.probeEdge()
    override suspend fun probeGoogleTranslate(): Long? = DjVoice.probeGoogleTranslate(app)
    override suspend fun probeVoice(voice: String): Long? = DjVoice.probeVoice(app, voice)
}

/**
 * [DjListener] on the phone: [DjSpeech], after RECORD_AUDIO. Granted already, it listens at once;
 * otherwise it asks, and listens when the answer is yes — what the DJ screen's mic button did.
 */
@Composable
fun rememberAndroidDjListener(): DjListener {
    val context = LocalContext.current
    val speech = remember { DjSpeech(context) }
    // What to do once the permission is granted: the listen that asked for it.
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val then = pending[0]
        pending[0] = null
        if (granted) then?.invoke()
    }
    return remember(speech, micPermLauncher) {
        object : DjListener {
            override fun isAvailable(): Boolean = speech.isAvailable()

            override fun listen(languageTag: String, onResult: (String) -> Unit, onError: () -> Unit) {
                val start = { speech.start(languageTag = languageTag, onResult = onResult, onError = { onError() }) }
                val granted = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) start() else {
                    pending[0] = start
                    micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }

            override fun stop() = speech.stop()
        }
    }
}
