package com.varuna.rustify.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.audio.CustomDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GREENC = Color(0xFF1DB954)
private val BGC = Color(0xFF121212)
private val CARDC = Color(0xFF1E1E1E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDownloadScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<CustomDownload.Probe?>(null) }
    var status by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(CustomDownload.folder(context)) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }

    val doneFmt = stringResource(R.string.cd_done)
    val errFmt = stringResource(R.string.cd_error)
    val emptyFormats = stringResource(R.string.cd_empty_formats)

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            CustomDownload.setFolder(context, uri.toString())
            folder = uri.toString()
        }
    }

    fun analyze() {
        if (url.isBlank() || probing) return
        probing = true; probe = null; status = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) { CustomDownload.probe(url) }
            probing = false
            result.onSuccess { p ->
                probe = p
                if (p.video.isEmpty() && p.audio.isEmpty()) status = emptyFormats
            }.onFailure { e -> status = errFmt.format(e.message ?: "error") }
        }
    }

    fun startDownload(f: CustomDownload.Fmt) {
        if (downloadingId != null) return
        if (folder.isBlank()) { folderPicker.launch(null); return }
        downloadingId = f.id; progress = 0; status = ""
        val base = probe?.title ?: "download"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                CustomDownload.download(context, url, f.id, f.isAudio, folder, base) { p ->
                    // el callback llega en hilo de yt-dlp; publica el progreso en Main
                    scope.launch(Dispatchers.Main) { progress = p }
                }
            }
            downloadingId = null
            result.onSuccess { name ->
                status = doneFmt.format(name)
                Toast.makeText(context, doneFmt.format(name), Toast.LENGTH_SHORT).show()
            }.onFailure { e -> status = errFmt.format(e.message ?: "error") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cd_title), color = Color.White, fontWeight = FontWeight.Bold) },
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
                    label = { Text(stringResource(R.string.cd_url_hint)) },
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
                    ) { Text(stringResource(R.string.cd_analyze), color = Color.Black) }
                    OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Folder, null, tint = GREENC, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cd_pick_folder), color = Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            item {
                val folderLabel = if (folder.isBlank()) stringResource(R.string.cd_no_folder)
                    else Uri.decode(folder.substringAfterLast("%3A").substringAfterLast("/").ifBlank { folder })
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
                                val m = p.durationSec / 60; val s = p.durationSec % 60
                                Text(String.format("%d:%02d", m, s), color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                }

                if (p.video.isNotEmpty()) {
                    item { SectionHdr(stringResource(R.string.cd_video)) }
                    items(p.video.size) { i -> FormatRow(p.video[i], downloadingId, progress) { startDownload(p.video[i]) } }
                }
                if (p.audio.isNotEmpty()) {
                    item { SectionHdr(stringResource(R.string.cd_audio)) }
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
private fun FormatRow(f: CustomDownload.Fmt, downloadingId: String?, progress: Int, onClick: () -> Unit) {
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
