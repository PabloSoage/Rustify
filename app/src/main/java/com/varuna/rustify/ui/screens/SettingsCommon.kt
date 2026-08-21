@file:Suppress("SpellCheckingInspection")
@file:SuppressLint("UseKtx")

package com.varuna.rustify.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import com.varuna.rustify.R
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.sync.DriveSyncManager
import com.varuna.rustify.sync.DriveSyncPrefs
import com.varuna.rustify.sync.GoogleDriveSync
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.varuna.rustify.util.AppLinksHosts
import com.varuna.rustify.util.BatteryOptimization
import com.varuna.rustify.util.LogCapture
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// SettingsCommon.kt -- extraido de SettingsScreen.kt en la 3.5.
//
// Piezas compartidas por las secciones de Ajustes: filas, menus y los dos huevos de Pascua.
//
// SettingsScreen.kt eran 3 796 lineas, de las que 1 875 estaban dentro de una sola funcion. Este
// corte se lleva los ayudantes de nivel superior, que son la mitad mecanica: cada uno es una
// seccion independiente de la pantalla y ninguno comparte estado con los demas. Lo que queda en
// SettingsScreen.kt es el composable grande, que es otra conversacion.
//
// Las funciones movidas pasaron de private a internal: en Kotlin un private de nivel superior
// es privado *del fichero*, asi que moverlas sin mas las habria hecho invisibles para la pantalla
// que las llama.

// Clickable without the default indication (ripple/highlight), which looked poor over text and emoji.
@Composable
internal fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * Level 1 egg: the crab vibrates and each tap fires an "ORA!" with an elastic bounce, while
 * ゴゴゴ symbols rise in the background. After 9 taps it chains into [JojoTimeStop] (level 2).
 * Texts arrive already localized (as resolved Strings) to respect the app's language.
 */
@Composable
internal fun JojoOraOra(title: String, msg: String, oraCount: Int, onOra: () -> Unit, onClose: () -> Unit) {
    val purple = Color(0xFF8E24AA)
    val gold = Color(0xFFFFD700)
    val infinite = rememberInfiniteTransition(label = "ora")
    // Very fast crab vibration.
    val shake by infinite.animateFloat(
        initialValue = -7f, targetValue = 7f,
        animationSpec = infiniteRepeatable(tween(80, easing = LinearEasing), RepeatMode.Reverse), label = "shake"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.94f, targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(130, easing = LinearEasing), RepeatMode.Reverse), label = "pulse"
    )
    // Elastic punch of the "ORA!" on each tap.
    val punch = remember { Animatable(1f) }
    LaunchedEffect(oraCount) {
        if (oraCount > 0) { punch.snapTo(1.7f); punch.animateTo(1f, spring(dampingRatio = 0.34f, stiffness = 440f)) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF20A0A0A)), contentAlignment = Alignment.Center) {
            // ゴゴゴ symbols rising in the background.
            for (i in 0 until 6) {
                val rise by infinite.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2200, delayMillis = i * 170, easing = LinearEasing), RepeatMode.Restart),
                    label = "go$i"
                )
                Text(
                    "ゴ", color = purple.copy(alpha = (1f - rise).coerceIn(0f, 1f) * 0.35f),
                    fontSize = (16 + (i % 3) * 9).sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).offset { IntOffset(x = (28 + i * 58).dp.roundToPx(), y = -(rise * 420).dp.roundToPx()) }
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(title, color = gold, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Text(
                    "🦀", fontSize = 92.sp,
                    modifier = Modifier
                        .graphicsLayer { rotationZ = shake; scaleX = pulse; scaleY = pulse }
                        .noRippleClickable { onOra() }
                )
                Spacer(Modifier.height(18.dp))
                if (oraCount > 0) {
                    Text(
                        "ORA!", color = purple, fontSize = 46.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.graphicsLayer { scaleX = punch.value; scaleY = punch.value; rotationZ = shake * 0.6f }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(("ORA ".repeat(oraCount)).trim(), color = gold.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3)
                }
                Spacer(Modifier.height(20.dp))
                Text(msg, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                TextButton(onClick = onClose) { Text("😎", color = Color(0xFF1DB954), fontSize = 22.sp) }
            }
        }
    }
}

/**
 * Nested egg (level 2): a full-screen "time stop" effect. Rotating golden rays, a double
 * "time stopped" flash, the 「ザ・ワールド」 title entering with an elastic bounce, and rising ゴ
 * symbols. "To Be Continued ➜" opens standle.net; やれやれだぜ closes it.
 * Built entirely with Compose animations, with no new resources or dependencies.
 */
@Composable
internal fun JojoTimeStop(message: String, onContinue: () -> Unit, onDismiss: () -> Unit) {
    val gold = Color(0xFFFFD700)
    val infinite = rememberInfiniteTransition(label = "jojo")
    val rayAngle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "rays"
    )
    // Title: appears with an elastic bounce. Flash: a double white "time stopped" flash.
    val titleScale = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        flash.animateTo(1f, tween(80)); flash.animateTo(0f, tween(200))
        flash.animateTo(0.75f, tween(70)); flash.animateTo(0f, tween(280))
        titleScale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 300f))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF060606))) {
            val w = maxWidth
            val h = maxHeight

            // Rotating radial golden rays (aura effect).
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = kotlin.math.hypot(size.width, size.height)
                val rays = 24
                val step = 360.0 / rays
                for (i in 0 until rays) {
                    if (i % 2 != 0) continue
                    val a0 = Math.toRadians(rayAngle + i * step)
                    val a1 = Math.toRadians(rayAngle + i * step + step)
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx, cy)
                        lineTo(cx + r * kotlin.math.cos(a0).toFloat(), cy + r * kotlin.math.sin(a0).toFloat())
                        lineTo(cx + r * kotlin.math.cos(a1).toFloat(), cy + r * kotlin.math.sin(a1).toFloat())
                        close()
                    }
                    drawPath(path, color = gold.copy(alpha = 0.07f))
                }
            }

            // ゴ symbols rising and fading out, staggered.
            val goCount = 8
            for (i in 0 until goCount) {
                val rise by infinite.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2400, delayMillis = i * 150, easing = LinearEasing), RepeatMode.Restart),
                    label = "go$i"
                )
                Text(
                    "ゴ",
                    color = gold.copy(alpha = (1f - rise).coerceIn(0f, 1f) * 0.55f),
                    fontSize = (18 + (i % 3) * 10).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset { IntOffset(x = (w * ((i + 0.5f) / goCount)).roundToPx(), y = (-(h * 0.12f) - h * 0.72f * rise).roundToPx()) }
                )
            }

            // Central content.
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "「ザ・ワールド」",
                    color = gold, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        scaleX = titleScale.value; scaleY = titleScale.value
                        rotationZ = (1f - titleScale.value) * -12f
                    }
                )
                Spacer(Modifier.height(6.dp))
                Text("ゴ ゴ ゴ ゴ", color = gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Text(message, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                TextButton(onClick = onContinue) {
                    Text("To Be Continued  ➜", color = gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                TextButton(onClick = onDismiss) { Text("やれやれだぜ", color = Color.Gray) }
            }

            // Flash overlay (time stopped).
            if (flash.value > 0.001f) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// "Audio Backends" section (drag and drop + toggles).
// ---------------------------------------------------------------------------

@Composable
internal fun backendFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedContainerColor = Color(0xFF121212), unfocusedContainerColor = Color(0xFF121212),
    focusedLabelColor = Color(0xFF1DB954), focusedIndicatorColor = Color(0xFF1DB954)
)

// Health dot color (same scheme as the DJ model latency indicator).
internal fun voiceDotColor(l: com.varuna.rustify.dj.DjProviders.Latency?): Color =
    when (l ?: com.varuna.rustify.dj.DjProviders.Latency.UNKNOWN) {
        com.varuna.rustify.dj.DjProviders.Latency.FAST -> Color(0xFF1DB954)
        com.varuna.rustify.dj.DjProviders.Latency.OK -> Color(0xFFFFC107)
        com.varuna.rustify.dj.DjProviders.Latency.SLOW -> Color(0xFFFF7043)
        com.varuna.rustify.dj.DjProviders.Latency.DOWN -> Color(0xFFE53935)
        else -> Color.Gray
    }

// Settings category menu (sub-screens). Each row opens its category.
@Composable
internal fun SettingsCategoryMenu(onPick: (String) -> Unit) {
    val cats = listOf(
        "general" to stringResource(R.string.settings_cat_general),
        "audio" to stringResource(R.string.settings_cat_audio),
        "downloads" to stringResource(R.string.settings_cat_downloads),
        "integrations" to stringResource(R.string.settings_cat_integrations),
        "advanced" to stringResource(R.string.settings_cat_advanced)
    )
    Spacer(Modifier.height(12.dp))
    cats.forEach { (key, label) ->
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onPick(key) }
        ) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("›", color = Color.Gray, fontSize = 20.sp)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

// "Downloads" category: custom downloads folder plus access to the download screen.
@Composable
internal fun DownloadsCategory(context: Context, onOpenCustom: () -> Unit) {
    val green = Color(0xFF1DB954)
    // Standard downloads folder (in addition to the custom one).
    DownloadFolderSection(context)
    var folder by remember { mutableStateOf(com.varuna.rustify.audio.CustomDownload.folder(context)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            com.varuna.rustify.audio.CustomDownload.setFolder(context, uri.toString())
            folder = uri.toString()
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_custom_download), color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val folderLabel = if (folder.isBlank()) stringResource(R.string.cd_no_folder)
                else Uri.decode(folder.substringAfterLast("%3A").substringAfterLast("/")).ifBlank { folder }
            Text("📁 $folderLabel", color = Color.Gray, fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { picker.launch(null) }) { Text(stringResource(R.string.cd_pick_folder), color = green) }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onOpenCustom, colors = ButtonDefaults.buttonColors(containerColor = green), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.cd_title), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// "Advanced" category: hash inspector plus access to metrics, the match editor, and logs.
@Composable
internal fun AdvancedLinks(onMetrics: () -> Unit, onMatchEditor: () -> Unit, onLogs: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            stringResource(R.string.home_metrics) to onMetrics,
            stringResource(R.string.settings_edit_matches) to onMatchEditor
        ).forEach { (label, action) ->
            Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(label, color = Color.White, modifier = Modifier.weight(1f))
                Text("›", color = Color.Gray)
            }
        }
    }
}

// Standard row of title + description + Switch (avoids repeating the same boilerplate for every setting).
@Composable
internal fun SettingSwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            if (desc.isNotBlank()) Text(desc, color = Color.Gray, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1DB954))
        )
    }
}

