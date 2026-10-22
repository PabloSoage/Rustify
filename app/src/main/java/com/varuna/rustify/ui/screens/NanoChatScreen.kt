package com.varuna.rustify.ui.screens

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import androidx.compose.ui.text.withStyle
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.dj.ChatAction
import com.varuna.rustify.dj.ChatActions
import com.varuna.rustify.dj.DjEngine
import com.varuna.rustify.dj.OnDeviceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A chat with Gemini Nano on the phone (4.7.1), under Settings because it is a tool, not part of the
 * player: the same model the DJ uses, through Android's AICore, on the NPU. Nothing leaves the phone.
 *
 * The Prompt API keeps no conversation, so the recent turns are sent again with each question,
 * trimmed to the model's token limit. A photo can go with a question; whether the model reads
 * images depends on the model the phone has, and the answer says so if it cannot.
 *
 * Since 4.8.1 it can also act on the app — play, queue, search, start a DJ mix, pause, skip — through
 * [ChatActions]: the model ends its answer with an action line and this screen carries it out. The
 * conversation stays on the phone; an action that searches asks Spotify, as any search in the app.
 */
private data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val image: Bitmap? = null,
    val stats: String? = null,
    /** What the app did for this answer ("Playing …"), when it asked for an action. */
    val done: String? = null,
    /** Songs to pick from: search results, or what an action played. */
    val tracks: List<FullTrack> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NanoChatScreen(
    onBack: () -> Unit,
    spotifyRepo: SpotifyRepository,
    nowPlaying: FullTrack?,
    queue: List<FullTrack>,
    onPlayTracks: (List<FullTrack>) -> Unit,
    onEnqueueTracks: (List<FullTrack>) -> Unit,
    onControl: (ChatAction) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val green = Color(0xFF1DB954)
    val status by OnDeviceModel.status.collectAsState()
    val modelName by OnDeviceModel.modelName.collectAsState()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val statsTemplate = stringResource(R.string.nano_chat_stats)
    val failedTemplate = stringResource(R.string.nano_chat_failed)
    val doneStrings = ChatDoneStrings(
        playing = stringResource(R.string.nano_chat_done_playing),
        queued = stringResource(R.string.nano_chat_done_queued),
        results = stringResource(R.string.nano_chat_done_results),
        mix = stringResource(R.string.nano_chat_done_mix),
        notFound = stringResource(R.string.nano_chat_done_not_found),
        paused = stringResource(R.string.nano_chat_done_paused),
        resumed = stringResource(R.string.nano_chat_done_resumed),
        next = stringResource(R.string.nano_chat_done_next),
        previous = stringResource(R.string.nano_chat_done_previous),
        failed = stringResource(R.string.nano_chat_done_failed),
    )

    /** Carries out what the model asked for; answers what to show under its reply. */
    suspend fun perform(action: ChatAction): Pair<String, List<FullTrack>> = runCatching {
        suspend fun first(query: String) = spotifyRepo.searchTracks(query, limit = 5).items.firstOrNull { it.id != null }
        when (action) {
            is ChatAction.Play -> {
                val track = first(action.query) ?: return@runCatching String.format(doneStrings.notFound, action.query) to emptyList()
                // A song, then music like it: "play Queen" should not stop after one track.
                val radio = runCatching { spotifyRepo.getTrackRadio(track.id!!) }.getOrDefault(emptyList())
                val set = (listOf(track) + radio).distinctBy { it.id }.take(30)
                onPlayTracks(set)
                String.format(doneStrings.playing, label(track)) to listOf(track)
            }
            is ChatAction.Queue -> {
                val track = first(action.query) ?: return@runCatching String.format(doneStrings.notFound, action.query) to emptyList()
                onEnqueueTracks(listOf(track))
                String.format(doneStrings.queued, label(track)) to listOf(track)
            }
            is ChatAction.Search -> {
                val found = spotifyRepo.searchTracks(action.query, limit = 8).items.filter { it.id != null }
                if (found.isEmpty()) String.format(doneStrings.notFound, action.query) to emptyList()
                else String.format(doneStrings.results, action.query) to found
            }
            is ChatAction.Dj -> {
                val result = DjEngine(context, spotifyRepo).run(request = action.request, nowPlaying = nowPlaying, queue = queue)
                if (result.tracks.isEmpty()) String.format(doneStrings.notFound, action.request) to emptyList()
                else {
                    onPlayTracks(result.tracks)
                    String.format(doneStrings.mix, result.intro, result.tracks.size) to result.tracks.take(5)
                }
            }
            ChatAction.Pause -> { onControl(action); doneStrings.paused to emptyList() }
            ChatAction.Resume -> { onControl(action); doneStrings.resumed to emptyList() }
            ChatAction.Next -> { onControl(action); doneStrings.next to emptyList() }
            ChatAction.Previous -> { onControl(action); doneStrings.previous to emptyList() }
        }
    }.getOrElse { e -> String.format(doneStrings.failed, e.message ?: e.javaClass.simpleName) to emptyList() }

    LaunchedEffect(Unit) {
        OnDeviceModel.init(context)
        if (OnDeviceModel.refresh() == OnDeviceModel.Status.READY) OnDeviceModel.warmup()
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch { image = withContext(Dispatchers.IO) { loadScaled(context, uri) } }
    }

    fun send() {
        val question = input.trim()
        if (question.isEmpty() || busy || status != OnDeviceModel.Status.READY) return
        val attached = image
        input = ""
        image = null
        val history = messages.toList()
        messages.add(ChatMessage(true, question, attached))
        messages.add(ChatMessage(false, ""))
        val answerIndex = messages.lastIndex
        busy = true
        scope.launch {
            val started = System.nanoTime()
            var firstTokenMs = -1L
            val answer = StringBuilder()
            val result = runCatching {
                val system = ChatActions.systemPrompt(
                    nowPlaying?.let { label(it) },
                    queue.dropWhile { it.id != nowPlaying?.id }.drop(1).map { label(it) }
                )
                val prompt = system + "\n" + buildPrompt(history, question)
                val request = if (attached != null) generateContentRequest(ImagePart(attached), TextPart(prompt)) { temperature = 0.7f }
                    else generateContentRequest(TextPart(prompt)) { temperature = 0.7f }
                OnDeviceModel.model.generateContent(request, object : StreamingCallback {
                    override fun onNewText(text: String) {
                        if (firstTokenMs < 0) firstTokenMs = (System.nanoTime() - started) / 1_000_000
                        answer.append(text)
                        val snapshot = ChatActions.visibleText(answer.toString())
                        scope.launch { messages[answerIndex] = messages[answerIndex].copy(text = snapshot) }
                    }
                })
            }
            val totalMs = (System.nanoTime() - started) / 1_000_000
            result.onSuccess { response ->
                // Some builds stream nothing and only return the whole answer at the end.
                val text = answer.toString().ifBlank { response.candidates.firstOrNull()?.text.orEmpty() }
                val tokens = runCatching {
                    OnDeviceModel.model.countTokens(generateContentRequest(TextPart(text)) {}).totalTokens
                }.getOrDefault(0)
                val genMs = (totalMs - firstTokenMs.coerceAtLeast(0)).coerceAtLeast(1)
                val stats = String.format(
                    statsTemplate,
                    firstTokenMs.coerceAtLeast(0) / 1000.0, totalMs / 1000.0, tokens * 1000.0 / genMs
                )
                val action = ChatActions.parse(text)?.takeIf { ChatActions.isRequest(question) }
                messages[answerIndex] = ChatMessage(false, ChatActions.visibleText(text), stats = stats)
                if (action != null) {
                    val (done, tracks) = perform(action)
                    messages[answerIndex] = messages[answerIndex].copy(done = done, tracks = tracks)
                }
            }.onFailure { e ->
                messages[answerIndex] = ChatMessage(false, String.format(failedTemplate, e.message ?: e.javaClass.simpleName))
            }
            busy = false
        }
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Column {
                        Text(stringResource(R.string.nano_chat_title), color = Color.White)
                        Text(
                            listOfNotNull(modelName, stringResource(R.string.nano_chat_on_device)).joinToString(" · "),
                            color = Color.Gray, fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back), tint = Color.White)
                    }
                },
                actions = {
                    if (messages.isNotEmpty() && !busy) {
                        IconButton(onClick = { messages.clear() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.nano_chat_clear), tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (status != OnDeviceModel.Status.READY) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    NanoModelPanel()
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                if (messages.isEmpty()) {
                    item { Text(stringResource(R.string.nano_chat_empty), color = Color.Gray, fontSize = 14.sp) }
                }
                items(messages) { m ->
                    Bubble(m, green, onPlay = { t -> onPlayTracks(listOf(t)) }, onQueue = { t -> onEnqueueTracks(listOf(t)) })
                }
            }
            image?.let { bmp ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                    IconButton(onClick = { image = null }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.nano_chat_remove_image), tint = Color.Gray)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, enabled = !busy) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.nano_chat_attach), tint = Color.LightGray)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(R.string.nano_chat_hint), color = Color.Gray) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF242424), unfocusedContainerColor = Color(0xFF242424),
                        cursorColor = green
                    )
                )
                IconButton(onClick = { send() }, enabled = !busy && input.isNotBlank() && status == OnDeviceModel.Status.READY) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.nano_chat_send),
                        tint = if (!busy && input.isNotBlank()) green else Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, green: Color, onPlay: (FullTrack) -> Unit, onQueue: (FullTrack) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (m.fromUser) Color(0xFF1F3A2A) else Color(0xFF242424))
                .padding(12.dp)
        ) {
            m.image?.let {
                Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(160.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(6.dp))
            }
            Text(if (m.fromUser) androidx.compose.ui.text.AnnotatedString(m.text) else simpleMarkdown(m.text.ifEmpty { "…" }), color = Color.White, fontSize = 15.sp)
            m.done?.let { Text(it, color = green, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
            m.tracks.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { onPlay(t) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = green, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(t.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(t.artists.joinToString(", ") { it.name }, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                    }
                    IconButton(onClick = { onQueue(t) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.nano_chat_add_to_queue), tint = Color.LightGray)
                    }
                }
            }
            m.stats?.let { Text(it, color = green.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** "Song — Artist", how a track is named to the model and in what the chat says it did. */
private fun label(t: FullTrack): String =
    listOfNotNull(t.name.ifBlank { null }, t.artists.firstOrNull()?.name).joinToString(" — ")

/** The lines that say what an action did, resolved once from resources. */
private data class ChatDoneStrings(
    val playing: String, val queued: String, val results: String, val mix: String, val notFound: String,
    val paused: String, val resumed: String, val next: String, val previous: String, val failed: String,
)

/** The recent turns and the new question, as one prompt; at most six earlier turns. */
private fun buildPrompt(history: List<ChatMessage>, question: String): String {
    val turns = history.filter { it.text.isNotBlank() }.takeLast(6)
    if (turns.isEmpty()) return question
    return buildString {
        turns.forEach { append(if (it.fromUser) "User: " else "Assistant: ").append(it.text.trim()).append("\n") }
        append("User: ").append(question).append("\nAssistant:")
    }
}

/** A picked photo, decoded and scaled so its longer side is at most 1024 px. */
private fun loadScaled(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 1024) {
            val f = 1024f / longest
            decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

/**
 * The little Markdown the model writes — `**bold**` and `* ` bullets — shown as such rather than as
 * asterisks. Anything else stays as text; a half-streamed `**` simply waits for its pair.
 */
internal fun simpleMarkdown(text: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        text.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            val line = if (raw.trimStart().startsWith("* ")) "• " + raw.trimStart().removePrefix("* ") else raw
            var rest = line
            while (true) {
                val a = rest.indexOf("**")
                val b = if (a >= 0) rest.indexOf("**", a + 2) else -1
                if (a < 0 || b < 0) { append(rest); break }
                append(rest.substring(0, a))
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                    append(rest.substring(a + 2, b))
                }
                rest = rest.substring(b + 2)
            }
        }
    }
