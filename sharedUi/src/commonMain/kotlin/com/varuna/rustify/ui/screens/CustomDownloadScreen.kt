package com.varuna.rustify.ui.screens

import com.varuna.rustify.audio.CustomDownloads
import com.varuna.rustify.audio.CustomFormat
import com.varuna.rustify.audio.CustomProbe
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.formatMinSec
import com.varuna.rustify.ui.rememberPickFolder
import com.varuna.rustify.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private val GREENC = Color(0xFF1DB954)
private val BGC = Color(0xFF121212)
private val CARDC = Color(0xFF1E1E1E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDownloadScreen(downloads: CustomDownloads, onBack: () -> Unit) {
    val platform = LocalAppPlatform.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<CustomProbe?>(null) }
    var status by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(downloads.folder()) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }

    val doneFmt = Res.string.cd_done
    val errFmt = Res.string.cd_error
    val emptyFormats = stringResource(Res.string.cd_empty_formats)

    val folderPicker = rememberPickFolder(write = true) { picked ->
        if (picked != null) {
            downloads.setFolder(picked)
            folder = picked
        }
    }

    fun analyze() {
        if (url.isBlank() || probing) return
        probing = true; probe = null; status = ""
        scope.launch {
            val result = downloads.probe(url)
            probing = false
            result.onSuccess { p ->
                probe = p
                if (p.video.isEmpty() && p.audio.isEmpty()) status = emptyFormats
            }.onFailure { e -> status = com.varuna.rustify.ui.appString(errFmt, e.message ?: "error") }
        }
    }

    fun startDownload(f: CustomFormat) {
        if (downloadingId != null) return
        if (folder.isBlank()) { folderPicker(); return }
        downloadingId = f.id; progress = 0; status = ""
        val base = probe?.title ?: "download"
        scope.launch {
            val result = downloads.download(url, f.id, f.isAudio, folder, base) { p ->
                // Callback arrives on yt-dlp's thread; publish progress on the main thread.
                scope.launch { progress = p }
            }
            downloadingId = null
            result.onSuccess { name ->
                val done = com.varuna.rustify.ui.appString(doneFmt, name)
                status = done
                platform.toast(done)
            }.onFailure { e -> status = com.varuna.rustify.ui.appString(errFmt, e.message ?: "error") }
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(Res.string.cd_title), color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BGC)
            )
        },
        containerColor = BGC
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(stringResource(Res.string.cd_url_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = GREENC, focusedLabelColor = GREENC
                    )
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { analyze() }, enabled = !probing && url.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = GREENC),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(Res.string.cd_analyze), color = Color.Black) }
                    OutlinedButton(onClick = { folderPicker() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Folder, null, tint = GREENC, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.cd_pick_folder), color = Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            item {
                val folderLabel = if (folder.isBlank()) stringResource(Res.string.cd_no_folder)
                    else downloads.folderLabel(folder)
                Text("📁 $folderLabel", color = Color.Gray, fontSize = 11.sp, maxLines = 1)
            }

            if (probing) {
                item { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GREENC) } }
            }
            if (status.isNotBlank()) {
                item { Text(status, color = Color.White, fontSize = 13.sp) }
            }

            probe?.let { p ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!p.thumbnail.isNullOrBlank()) {
                            AsyncImage(
                                model = p.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(CARDC)
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            if (p.durationSec > 0) {
                                val m = p.durationSec / 60L; val s = p.durationSec % 60L
                                Text(formatMinSec(m, s), color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                }

                if (p.video.isNotEmpty()) {
                    item { SectionHdr(stringResource(Res.string.cd_video)) }
                    items(p.video.size) { i -> FormatRow(p.video[i], downloadingId, progress) { startDownload(p.video[i]) } }
                }
                if (p.audio.isNotEmpty()) {
                    item { SectionHdr(stringResource(Res.string.cd_audio)) }
                    items(p.audio.size) { i -> FormatRow(p.audio[i], downloadingId, progress) { startDownload(p.audio[i]) } }
                }
            }
        }
    }
}

@Composable
private fun SectionHdr(text: String) {
    Text(text, color = GREENC, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun FormatRow(f: CustomFormat, downloadingId: String?, progress: Int, onClick: () -> Unit) {
    val busy = downloadingId != null
    val thisBusy = downloadingId == f.id
    Card(
        colors = CardDefaults.cardColors(containerColor = CARDC),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onClick() }
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.label(), color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                if (thisBusy) Text("$progress%", color = GREENC, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (thisBusy) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = GREENC, trackColor = Color(0xFF2A2A2A)
                )
            }
        }
    }
}

/** The custom downloads' card in Settings > Downloads: their folder, and the way to the screen. */
@Composable
fun CustomDownloadSection(downloads: CustomDownloads, onOpen: () -> Unit) {
    var folder by remember { mutableStateOf(downloads.folder()) }
    val picker = rememberPickFolder(write = true) { picked ->
        if (picked != null) {
            downloads.setFolder(picked)
            folder = picked
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(Res.string.settings_custom_download), color = GREENC, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = CARDC), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val folderLabel = if (folder.isBlank()) stringResource(Res.string.cd_no_folder) else downloads.folderLabel(folder)
            Text("📁 $folderLabel", color = Color.Gray, fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = picker) { Text(stringResource(Res.string.cd_pick_folder), color = GREENC) }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = GREENC), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(Res.string.cd_title), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
