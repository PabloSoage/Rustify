// app/src/main/java/com/varuna/rustify/dj/NanoChatModel.kt
package com.varuna.rustify.dj

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.nano_chat_empty
import com.varuna.rustify.ui.resources.nano_chat_on_device
import com.varuna.rustify.ui.resources.nano_chat_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * [ChatModel] on the phone: Gemini Nano through ML Kit's Prompt API, on the NPU, as the chat screen
 * called it before it moved to :sharedUi (4.7.1). Nothing leaves the phone.
 *
 * The Prompt API keeps no conversation, so the recent turns are sent again with each question, as
 * one prompt after the instructions. A photo goes in as an image part; whether the model reads it
 * depends on the model the phone has, and the answer says so if it cannot.
 */
class NanoChatModel(context: Context) : ChatModel {
    private val app = context.applicationContext

    override val title: StringResource = Res.string.nano_chat_title
    override val intro: StringResource = Res.string.nano_chat_empty
    override val device: String = "this phone"
    override val supportsImages: Boolean = true
    override val countsTokens: Boolean = true

    @Composable
    override fun subtitle(): String {
        val modelName by OnDeviceModel.modelName.collectAsState()
        return listOfNotNull(modelName, stringResource(Res.string.nano_chat_on_device)).joinToString(" · ")
    }

    @Composable
    override fun isReady(): Boolean = OnDeviceModel.status.collectAsState().value == OnDeviceModel.Status.READY

    override suspend fun prepare() {
        OnDeviceModel.init(app)
        if (OnDeviceModel.refresh() == OnDeviceModel.Status.READY) OnDeviceModel.warmup()
    }

    override suspend fun stream(
        system: String,
        history: List<ChatTurn>,
        question: String,
        image: ImageBitmap?,
        onText: (String) -> Unit,
    ): String {
        val prompt = system + "\n" + buildPrompt(history, question)
        val request = if (image != null) generateContentRequest(ImagePart(image.asAndroidBitmap()), TextPart(prompt)) { temperature = 0.7f }
            else generateContentRequest(TextPart(prompt)) { temperature = 0.7f }
        val response = OnDeviceModel.model.generateContent(request, object : StreamingCallback {
            override fun onNewText(text: String) = onText(text)
        })
        // Some builds stream nothing and only return the whole answer at the end.
        return response.candidates.firstOrNull()?.text.orEmpty()
    }

    override suspend fun countTokens(text: String): Int? = runCatching {
        OnDeviceModel.model.countTokens(generateContentRequest(TextPart(text)) {}).totalTokens
    }.getOrDefault(0)

    @Composable
    override fun SetupPanel() = com.varuna.rustify.ui.screens.NanoModelPanel()

    @Composable
    override fun rememberImagePicker(onPicked: (ImageBitmap?) -> Unit): () -> Unit {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) scope.launch { onPicked(withContext(Dispatchers.IO) { loadScaled(context, uri) }?.asImageBitmap()) }
        }
        return { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
}

/** The recent turns and the new question, as one prompt; at most six earlier turns. */
private fun buildPrompt(history: List<ChatTurn>, question: String): String {
    val turns = ChatModel.recent(history)
    if (turns.isEmpty()) return question
    return buildString {
        turns.forEach { append(if (it.fromUser) "User: " else "Assistant: ").append(it.text.trim()).append("\n") }
        append("User: ").append(question).append("\nAssistant:")
    }
}

/** A picked photo, decoded and scaled so its longer side is at most 1024 px. */
private fun loadScaled(context: Context, uri: Uri): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 1024) {
            val f = 1024f / longest
            decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()
