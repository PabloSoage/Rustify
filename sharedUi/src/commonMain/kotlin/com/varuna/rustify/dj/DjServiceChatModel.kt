// sharedUi/src/commonMain/kotlin/com/varuna/rustify/dj/DjServiceChatModel.kt
package com.varuna.rustify.dj

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.sp
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.appString
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.chat_empty_desktop
import com.varuna.rustify.ui.resources.chat_no_service
import com.varuna.rustify.ui.resources.chat_no_model_short
import com.varuna.rustify.ui.resources.chat_title_desktop
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The [ChatModel] where no model runs on the device (iOS, the web): the service set for the DJ, with the
 * DJ on "External AI". Never one the user did not choose for it, the free one it starts with included.
 */
class DjServiceChatModel(private val platform: AppPlatform, override val device: String) : ChatModel {
    override val title: StringResource = Res.string.chat_title_desktop
    override val intro: StringResource = Res.string.chat_empty_desktop
    override val supportsImages: Boolean = false
    override val countsTokens: Boolean = false

    /** A client for the service, or null with the DJ on anything else. */
    fun client(readTimeoutMs: Int = 120_000): OpenAiChat? {
        if (DjSettings.mode(platform) != DjMode.API) return null
        return OpenAiChat(DjSettings.apiBaseUrl(platform), DjSettings.apiModel(platform), DjSettings.apiKey(platform), readTimeoutMs)
    }

    @Composable
    override fun subtitle(): String =
        if (DjSettings.mode(platform) == DjMode.API) {
            "${DjSettings.apiModel(platform)} · ${DjSettings.apiBaseUrl(platform).substringAfter("://").substringBefore('/')}"
        } else {
            stringResource(Res.string.chat_no_model_short)
        }

    @Composable
    override fun isReady(): Boolean = DjSettings.mode(platform) == DjMode.API

    override suspend fun prepare() = Unit

    override suspend fun stream(
        system: String,
        history: List<ChatTurn>,
        question: String,
        image: ImageBitmap?,
        onText: (String) -> Unit,
    ): String {
        val chat = client() ?: throw IllegalStateException(appString(Res.string.chat_no_model_short))
        val messages = buildList {
            add(OpenAiChat.Message("system", system))
            ChatModel.recent(history).forEach {
                add(OpenAiChat.Message(if (it.fromUser) "user" else "assistant", it.text.trim()))
            }
            add(OpenAiChat.Message("user", question))
        }
        return chat.stream(messages, temperature = 0.7, onText = onText)
    }

    override suspend fun countTokens(text: String): Int? = null

    @Composable
    override fun SetupPanel() {
        Text(stringResource(Res.string.chat_no_service), color = Color.Gray, fontSize = 13.sp)
    }

    // Never asked: [supportsImages] is false.
    @Composable
    override fun rememberImagePicker(onPicked: (ImageBitmap?) -> Unit): () -> Unit = {}
}
