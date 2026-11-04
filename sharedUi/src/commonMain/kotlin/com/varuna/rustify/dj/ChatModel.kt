// sharedUi/src/commonMain/kotlin/com/varuna/rustify/dj/ChatModel.kt
package com.varuna.rustify.dj

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.StringResource

/** An earlier turn of the chat, as it is sent back to the model with the next question. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/**
 * The language model behind the chat (`NanoChatScreen`), as the shared screen sees it; each platform
 * hands its own in through `AppServices.chatModel`. Android's is Gemini Nano through ML Kit's Prompt
 * API, on the phone, as the screen always called it. What a model cannot do is said by
 * [supportsImages] and [countsTokens], and the screen leaves that control out and says why.
 *
 * The conversation and its actions ([ChatActions]) are the screen's, the same on every platform: the
 * model is only asked for the text.
 */
interface ChatModel {
    /** The screen's title, which names what answers ("Chat with Gemini Nano"). */
    val title: StringResource

    /** What the empty conversation says: what to ask, and where the conversation goes. */
    val intro: StringResource

    /** Whether a photo can go with a question. */
    val supportsImages: Boolean

    /** Whether [countTokens] counts, for the tokens per second under each answer. */
    val countsTokens: Boolean

    /** Under the title: which model, and where ("Gemini Nano · on this phone"). */
    @Composable
    fun subtitle(): String

    /** Whether it can answer now; until it can, the screen shows [SetupPanel] and sends nothing. */
    @Composable
    fun isReady(): Boolean

    /** Called when the chat opens: gets the model ready, so the first answer is not the slow one. */
    suspend fun prepare()

    /**
     * Answers [question], after the [system] instructions and the [history] before it (every turn
     * shown, oldest first; the model keeps as many as it can take), with [image] when there is one.
     * [onText] gets each piece as it is written, on whatever thread the model writes on; the answer is
     * returned whole at the end, which is all some models give.
     */
    suspend fun stream(
        system: String,
        history: List<ChatTurn>,
        question: String,
        image: ImageBitmap?,
        onText: (String) -> Unit,
    ): String

    /** How many tokens [text] is, or null when this model does not say ([countsTokens] false). */
    suspend fun countTokens(text: String): Int?

    /** Shown above the conversation while [isReady] is false: what is missing, and the way to get it. */
    @Composable
    fun SetupPanel()

    /**
     * Lets the user pick a photo; the returned function opens the picker. [onPicked] gets the photo,
     * or null when it could not be read. Only asked for when [supportsImages].
     */
    @Composable
    fun rememberImagePicker(onPicked: (ImageBitmap?) -> Unit): () -> Unit

    companion object {
        /** How many earlier turns go with a question, at most. */
        const val MAX_TURNS = 6

        /** The turns a question carries: the last [MAX_TURNS] that say something. */
        fun recent(history: List<ChatTurn>): List<ChatTurn> = history.filter { it.text.isNotBlank() }.takeLast(MAX_TURNS)
    }
}
