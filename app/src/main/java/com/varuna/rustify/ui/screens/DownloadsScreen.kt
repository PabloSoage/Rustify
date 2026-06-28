package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.rustify.R
import com.varuna.rustify.bridge.DownloadManager
import com.varuna.rustify.bridge.DownloadStatus
import com.varuna.rustify.bridge.DownloadTask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val downloads by DownloadManager.downloads.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Descargas", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (downloads.any { it.status == DownloadStatus.COMPLETE || it.status == DownloadStatus.ERROR }) {
                        TextButton(onClick = { DownloadManager.clearCompleted() }) {
                            Text("Limpiar", color = Color(0xFF1DB954))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        },
        containerColor = Color(0xFF121212),
        modifier = modifier
    ) { innerPadding ->
        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("No hay descargas activas", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                contentPadding = innerPadding,
                modifier = Modifier.fillMaxSize()
            ) {
                items(downloads, key = { it.id }) { task ->
                    DownloadRow(task = task)
                }
            }
        }
    }
}

@Composable
fun DownloadRow(task: DownloadTask) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF282828), shape = MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center
        ) {
            when (task.status) {
                DownloadStatus.COMPLETE -> Icon(Icons.Default.CheckCircle, contentDescription = "Complete", tint = Color(0xFF1DB954))
                DownloadStatus.ERROR -> Icon(Icons.Default.Error, contentDescription = "Error", tint = Color.Red)
                else -> Icon(Icons.Default.FileDownload, contentDescription = "Downloading", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.artist,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                
                if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.RESOLVING) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when(task.status) {
                            DownloadStatus.QUEUED -> "En cola"
                            DownloadStatus.RESOLVING -> "Resolviendo..."
                            DownloadStatus.DOWNLOADING -> "${task.progress}%"
                            else -> ""
                        },
                        color = Color(0xFF1DB954),
                        style = MaterialTheme.typography.labelSmall
                    )
                } else if (task.status == DownloadStatus.ERROR && task.errorMessage != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = task.errorMessage,
                        color = Color.Red,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.RESOLVING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (task.status == DownloadStatus.DOWNLOADING) task.progress / 100f else 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF1DB954),
                    trackColor = Color(0xFF333333),
                )
            }
        }
    }
}
