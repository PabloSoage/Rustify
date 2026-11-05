package com.varuna.rustify.ui.screens

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
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.dj.ChatAction
import com.varuna.rustify.dj.ChatActions
import com.varuna.rustify.dj.ChatModel
import com.varuna.rustify.dj.ChatTurn
import com.varuna.rustify.dj.DjEngine
import com.varuna.rustify.ui.appString
import com.varuna.rustify.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.TimeSource

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
 *
 * The screen is shared (:sharedUi); the model is the platform's [ChatModel] (`AppServices.chatModel`),
 * which on Android is Gemini Nano as above.
 */
private data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val image: ImageBitmap? = null,
    val stats: String? = null,
    /** What the app did for this answer ("Playing …"), when it asked for an action. */
    val done: String? = null,
    /** Songs to pick from: search results, or what an action played. */
    val tracks: List<FullTrack> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NanoChatScreen(
    chat: ChatModel,
    onBack: () -> Unit,
    spotifyRepo: SpotifyRepository,
    nowPlaying: FullTrack?,
    queue: List<FullTrack>,
    onPlayTracks: (List<FullTrack>) -> Unit,
    onEnqueueTracks: (List<FullTrack>) -> Unit,
    onControl: (ChatAction) -> Unit,
) {
    // The DJ's engine reads its settings and its local mode through these, as the DJ screen does.
    val platform = com.varuna.rustify.ui.LocalAppPlatform.current
    val services = com.varuna.rustify.ui.LocalAppServices.current
    val scope = rememberCoroutineScope()
    val green = Color(0xFF1DB954)
    val ready = chat.isReady()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val statsTemplate = stringResource(Res.string.nano_chat_stats)

    /** Carries out what the model asked for; answers what to show under its reply. */
    suspend fun perform(action: ChatAction): Pair<String, List<FullTrack>> = runCatching {
        suspend fun first(query: String) = spotifyRepo.searchTracks(query, limit = 5).items.firstOrNull { it.id != null }
        when (action) {
            is ChatAction.Play -> {
                val track = first(action.query) ?: return@runCatching appString(Res.string.nano_chat_done_not_found, action.query) to emptyList()
                // A song, then music like it: "play Queen" should not stop after one track.
                val radio = runCatching { spotifyRepo.getTrackRadio(track.id!!) }.getOrDefault(emptyList())
                val set = (listOf(track) + radio).distinctBy { it.id }.take(30)
                onPlayTracks(set)
                appString(Res.string.nano_chat_done_playing, label(track)) to listOf(track)
            }
            is ChatAction.Queue -> {
                val track = first(action.query) ?: return@runCatching appString(Res.string.nano_chat_done_not_found, action.query) to emptyList()
                onEnqueueTracks(listOf(track))
                appString(Res.string.nano_chat_done_queued, label(track)) to listOf(track)
            }
            is ChatAction.Search -> {
                val found = spotifyRepo.searchTracks(action.query, limit = 8).items.filter { it.id != null }
                if (found.isEmpty()) appString(Res.string.nano_chat_done_not_found, action.query) to emptyList()
                else appString(Res.string.nano_chat_done_results, action.query) to found
            }
            is ChatAction.Dj -> {
                val result = DjEngine(platform, spotifyRepo) { services.localDjProvider() }.run(request = action.request, nowPlaying = nowPlaying, queue = queue)
                if (result.tracks.isEmpty()) appString(Res.string.nano_chat_done_not_found, action.request) to emptyList()
                else {
                    onPlayTracks(result.tracks)
                    appString(Res.string.nano_chat_done_mix, result.intro, result.tracks.size) to result.tracks.take(5)
                }
            }
            ChatAction.Pause -> { onControl(action); appString(Res.string.nano_chat_done_paused) to emptyList() }
            ChatAction.Resume -> { onControl(action); appString(Res.string.nano_chat_done_resumed) to emptyList() }
            ChatAction.Next -> { onControl(action); appString(Res.string.nano_chat_done_next) to emptyList() }
            ChatAction.Previous -> { onControl(action); appString(Res.string.nano_chat_done_previous) to emptyList() }
        }
    }.getOrElse { e -> appString(Res.string.nano_chat_done_failed, e.message ?: e::class.simpleName.orEmpty()) to emptyList() }

    LaunchedEffect(Unit) { chat.prepare() }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // Only where the model reads images; the picker is the platform's (Android's photo picker).
    val pickImage = if (chat.supportsImages) chat.rememberImagePicker { picked -> image = picked } else null

    fun send() {
        val question = input.trim()
        if (question.isEmpty() || busy || !ready) return
        val attached = image
        input = ""
        image = null
        val history = messages.map { ChatTurn(it.fromUser, it.text) }
        messages.add(ChatMessage(true, question, attached))
        messages.add(ChatMessage(false, ""))
        val answerIndex = messages.lastIndex
        busy = true
        scope.launch {
            val started = TimeSource.Monotonic.markNow()
            var firstTokenMs = -1L
            val answer = StringBuilder()
            val result = runCatching {
                val system = ChatActions.systemPrompt(
                    nowPlaying?.let { label(it) },
                    queue.dropWhile { it.id != nowPlaying?.id }.drop(1).map { label(it) },
                    device = chat.device,
                )
                chat.stream(system, history, question, attached) { text ->
                    if (firstTokenMs < 0) firstTokenMs = started.elapsedNow().inWholeMilliseconds
                    answer.append(text)
                    val snapshot = ChatActions.visibleText(answer.toString())
                    scope.launch { messages[answerIndex] = messages[answerIndex].copy(text = snapshot) }
                }
            }
            val totalMs = started.elapsedNow().inWholeMilliseconds
            result.onSuccess { whole ->
                // Some models stream nothing and only return the whole answer at the end.
                val text = answer.toString().ifBlank { whole }
                val tokens = chat.countTokens(text)
                val genMs = (totalMs - firstTokenMs.coerceAtLeast(0)).coerceAtLeast(1)
                // A model that does not count tokens gets its times without the speed.
                val stats = if (tokens != null) {
                    fillDecimals(statsTemplate, firstTokenMs.coerceAtLeast(0) / 1000.0, totalMs / 1000.0, tokens * 1000.0 / genMs)
                } else {
                    appString(Res.string.chat_stats_times, oneDecimal(firstTokenMs.coerceAtLeast(0) / 1000.0), oneDecimal(totalMs / 1000.0))
                }
                val action = ChatActions.parse(text)?.takeIf { ChatActions.isRequest(question) }
                messages[answerIndex] = ChatMessage(false, ChatActions.visibleText(text), stats = stats)
                if (action != null) {
                    val (done, tracks) = perform(action)
                    messages[answerIndex] = messages[answerIndex].copy(done = done, tracks = tracks)
                }
            }.onFailure { e ->
                messages[answerIndex] = ChatMessage(false, appString(Res.string.nano_chat_failed, e.message ?: e::class.simpleName.orEmpty()))
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
                        Text(stringResource(chat.title), color = Color.White)
                        Text(chat.subtitle(), color = Color.Gray, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.settings_back), tint = Color.White)
                    }
                },
                actions = {
                    if (messages.isNotEmpty() && !busy) {
                        IconButton(onClick = { messages.clear() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(Res.string.nano_chat_clear), tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!ready) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    chat.SetupPanel()
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                if (messages.isEmpty()) {
                    item { Text(stringResource(chat.intro), color = Color.Gray, fontSize = 14.sp) }
                    // What this model cannot do, said where the photo button and the speed would be missed.
                    if (!chat.supportsImages) {
                        item { Text(stringResource(Res.string.chat_no_images), color = Color.Gray, fontSize = 12.sp) }
                    }
                    if (!chat.countsTokens) {
                        item { Text(stringResource(Res.string.chat_no_token_count), color = Color.Gray, fontSize = 12.sp) }
                    }
                }
                items(messages) { m ->
                    Bubble(m, green, onPlay = { t -> onPlayTracks(listOf(t)) }, onQueue = { t -> onEnqueueTracks(listOf(t)) })
                }
            }
            image?.let { bmp ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(bmp, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                    IconButton(onClick = { image = null }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.nano_chat_remove_image), tint = Color.Gray)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pickImage != null) {
                    IconButton(onClick = { pickImage() }, enabled = !busy) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(Res.string.nano_chat_attach), tint = Color.LightGray)
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(Res.string.nano_chat_hint), color = Color.Gray) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF242424), unfocusedContainerColor = Color(0xFF242424),
                        cursorColor = green
                    )
                )
                IconButton(onClick = { send() }, enabled = !busy && input.isNotBlank() && ready) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(Res.string.nano_chat_send),
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
                Image(it, null, contentScale = ContentScale.Crop,
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
                        Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.nano_chat_add_to_queue), tint = Color.LightGray)
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

/**
 * [template]'s `%1$.1f`, `%2$.1f`… with [values] in them, one decimal each, as `String.format` wrote
 * them on the phone: Compose Multiplatform's resources fill only `%1$s` and `%1$d`.
 */
internal fun fillDecimals(template: String, vararg values: Double): String =
    com.varuna.rustify.ui.formatTemplate(template, *values.toTypedArray())

/** [x] to one decimal as `%.1f` writes it in the default locale, as the phone always showed it. */
internal fun oneDecimal(x: Double): String = com.varuna.rustify.ui.formatTemplate("%.1f", x)

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
