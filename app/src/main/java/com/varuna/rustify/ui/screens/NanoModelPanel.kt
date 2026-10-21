package com.varuna.rustify.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.R
import com.varuna.rustify.dj.OnDeviceModel
import kotlinx.coroutines.launch

/**
 * Gemini Nano on this phone (4.7.1): where it stands, the download with its progress and size, a
 * check for a newer model, and the way to free its space.
 *
 * There is no "delete" in the API, and there should not be one here: the model belongs to Android's
 * AICore and is shared with other apps (keyboard, recorder…), which AICore manages. What the app
 * can do honestly is open AICore's page in the system settings, where its storage is.
 */
@Composable
internal fun NanoModelPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val green = Color(0xFF1DB954)
    val status by OnDeviceModel.status.collectAsState()
    val progress by OnDeviceModel.progress.collectAsState()
    val modelName by OnDeviceModel.modelName.collectAsState()
    LaunchedEffect(Unit) {
        OnDeviceModel.init(context)
        OnDeviceModel.refresh()
    }
    val size = OnDeviceModel.knownSizeBytes(context)
    fun bytes(n: Long) = Formatter.formatShortFileSize(context, n)

    Column(Modifier.fillMaxWidth()) {
        val text = when (status) {
            OnDeviceModel.Status.READY -> stringResource(R.string.settings_dj_local_ready)
            OnDeviceModel.Status.DOWNLOADING -> stringResource(R.string.settings_dj_local_downloading)
            OnDeviceModel.Status.DOWNLOADABLE -> stringResource(R.string.settings_dj_local_downloadable)
            OnDeviceModel.Status.UNSUPPORTED -> stringResource(R.string.settings_dj_local_unsupported)
            else -> stringResource(R.string.settings_dj_local_note)
        }
        Text(text, color = if (status == OnDeviceModel.Status.READY) green else Color.Gray, fontSize = 12.sp)

        // Model and size, when known.
        val details = listOfNotNull(
            modelName,
            size.takeIf { it > 0 }?.let { stringResource(R.string.settings_dj_local_size, bytes(it)) }
        )
        if (status == OnDeviceModel.Status.READY && details.isNotEmpty()) {
            Text(details.joinToString(" · "), color = Color.Gray, fontSize = 12.sp)
        }

        if (status == OnDeviceModel.Status.DOWNLOADING) {
            Spacer(Modifier.height(6.dp))
            val p = progress
            if (p != null && p.total > 0) {
                LinearProgressIndicator(
                    progress = { (p.downloaded.toFloat() / p.total).coerceIn(0f, 1f) },
                    color = green, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.settings_dj_local_progress, bytes(p.downloaded), bytes(p.total)),
                    color = Color.Gray, fontSize = 12.sp
                )
            } else {
                // AICore downloads in its own process; after an app restart only "downloading" is known.
                LinearProgressIndicator(color = green, modifier = Modifier.fillMaxWidth())
            }
        }

        Row {
            if (status == OnDeviceModel.Status.DOWNLOADABLE) {
                TextButton(onClick = { OnDeviceModel.download() }) {
                    Text(
                        if (size > 0) stringResource(R.string.settings_dj_local_download_size, bytes(size))
                        else stringResource(R.string.settings_dj_local_download),
                        color = green
                    )
                }
            }
            if (status == OnDeviceModel.Status.READY) {
                // AICore updates the model by itself; asking again shows "download" if a newer one waits.
                TextButton(onClick = { scope.launch { OnDeviceModel.refresh() } }) {
                    Text(stringResource(R.string.settings_dj_local_check_update), color = green)
                }
            }
            if (status == OnDeviceModel.Status.READY || status == OnDeviceModel.Status.DOWNLOADING) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.google.android.aicore"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) {
                    Text(stringResource(R.string.settings_dj_local_manage), color = Color.LightGray)
                }
            }
        }
        if (status == OnDeviceModel.Status.READY) {
            Text(stringResource(R.string.settings_dj_local_manage_desc), color = Color.Gray, fontSize = 11.sp)
        }
    }
}
