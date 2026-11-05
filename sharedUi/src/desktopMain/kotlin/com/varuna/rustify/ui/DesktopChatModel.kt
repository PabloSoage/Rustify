// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopChatModel.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.dj.ChatModel
import com.varuna.rustify.dj.ChatTurn
import com.varuna.rustify.dj.DjMode
import com.varuna.rustify.dj.DjSettings
import com.varuna.rustify.dj.LocalLlmSettings
import com.varuna.rustify.dj.LocalModelManager
import com.varuna.rustify.dj.OpenAiChat
import com.varuna.rustify.platform.Log
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.chat_empty_desktop
import com.varuna.rustify.ui.resources.chat_no_model
import com.varuna.rustify.ui.resources.chat_no_model_short
import com.varuna.rustify.ui.resources.chat_title_desktop
import com.varuna.rustify.ui.resources.settings_dj_local_detecting
import com.varuna.rustify.ui.resources.settings_dj_local_rescan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the desktop's model calls answer: an OpenAI-compatible endpoint ([baseUrl]), the [model] to
 * name, its key when it has one, and how to say where it is ([where]: "Ollama", a host).
 */
data class ChatTarget(val baseUrl: String, val model: String, val apiKey: String, val where: String)

/**
 * The model the DJ asks, as the desktop's [ChatModel] and lyrics translator: with the DJ on "External
 * AI", the service set for it in Settings; otherwise a model server on this computer, the one the
 * DJ's "local" mode would ask ([LocalLlmSettings.target]: Ollama, LM Studio, llama-server, or
 * Rustify's own when it is on disk, started if it is not running). Never a service the user did not
 * choose for the DJ.
 *
 * Asked through [OpenAiChat], streamed. Text only: whether a server reads images depends on the
 * model it runs, and the answer does not say how many tokens it took, so the screen says both.
 */
class DesktopChatModel(
    private val platform: AppPlatform,
    private val localModels: LocalModelManager,
    /** The local mode's target ([LocalLlmSettings.target] with Rustify's own server). */
    private val localTarget: suspend () -> LocalLlmSettings.Target?,
) : ChatModel {
    private val target = MutableStateFlow<ChatTarget?>(null)
    private val looking = MutableStateFlow(true)

    override val title: StringResource = Res.string.chat_title_desktop
    override val intro: StringResource = Res.string.chat_empty_desktop
    override val device: String = "this computer"
    override val supportsImages: Boolean = false
    override val countsTokens: Boolean = false

    /** What a request goes to now, or null when nothing would answer. */
    suspend fun resolve(): ChatTarget? = when (DjSettings.mode(platform)) {
        DjMode.API -> {
            val url = DjSettings.apiBaseUrl(platform)
            ChatTarget(url, DjSettings.apiModel(platform), DjSettings.apiKey(platform), hostOf(url))
        }
        else -> localTarget()?.let { ChatTarget(it.baseUrl, it.model, apiKey = "", where = it.kind.label) }
    }

    /** A client for [resolve]'s target, or null when there is none; for the lyrics translation. */
    suspend fun client(readTimeoutMs: Int = 120_000): OpenAiChat? =
        resolve()?.let { OpenAiChat(it.baseUrl, it.model, it.apiKey, readTimeoutMs) }

    @Composable
    override fun subtitle(): String {
        val t by target.collectAsState()
        return t?.let { "${it.model} · ${it.where}" } ?: stringResource(Res.string.chat_no_model_short)
    }

    @Composable
    override fun isReady(): Boolean {
        // Rustify's own server, started from the panel below: asked again once it runs.
        val status by localModels.status.collectAsState()
        LaunchedEffect(status) { if (status is LocalModelManager.Status.Running) prepare() }
        return target.collectAsState().value != null
    }

    override suspend fun prepare() {
        looking.value = true
        target.value = runCatching { resolve() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        looking.value = false
    }

    override suspend fun stream(
        system: String,
        history: List<ChatTurn>,
        question: String,
        image: ImageBitmap?,
        onText: (String) -> Unit,
    ): String {
        val t = target.value ?: resolve()?.also { target.value = it }
            ?: throw IllegalStateException(appString(Res.string.chat_no_model_short))
        val messages = buildList {
            add(OpenAiChat.Message("system", system))
            ChatModel.recent(history).forEach {
                add(OpenAiChat.Message(if (it.fromUser) "user" else "assistant", it.text.trim()))
            }
            add(OpenAiChat.Message("user", question))
        }
        Log.i(TAG, "asking ${t.where} at ${t.baseUrl}, model ${t.model}")
        return try {
            OpenAiChat(t.baseUrl, t.model, t.apiKey).stream(messages, temperature = 0.7, onText = onText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The server may have gone (Ollama quit, a model unloaded): what answers is looked for
            // again, and the screen shows the setup panel when nothing does.
            prepare()
            throw e
        }
    }

    override suspend fun countTokens(text: String): Int? = null

    @Composable
    override fun SetupPanel() {
        val isLooking by looking.collectAsState()
        val scope = rememberCoroutineScope()
        Column {
            if (isLooking) {
                Text(stringResource(Res.string.settings_dj_local_detecting), color = Color.Gray, fontSize = 13.sp)
            } else {
                Text(stringResource(Res.string.chat_no_model), color = Color.Gray, fontSize = 13.sp)
                TextButton(onClick = { scope.launch { prepare() } }) {
                    Text(stringResource(Res.string.settings_dj_local_rescan), color = Color(0xFF1DB954))
                }
            }
            Spacer(Modifier.height(8.dp))
            // The same panel as Settings' "local" mode: which server and model, and Rustify's own.
            DesktopLocalDjPanel(platform, localModels)
        }
    }

    // Never asked: [supportsImages] is false.
    @Composable
    override fun rememberImagePicker(onPicked: (ImageBitmap?) -> Unit): () -> Unit = {}

    private companion object {
        const val TAG = "DesktopChat"

        /** `text.pollinations.ai`, for saying where an external service is. */
        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').ifBlank { url }
    }
}
