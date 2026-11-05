// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SettingsParts.kt
package com.varuna.rustify.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.audio_lab_title
import com.varuna.rustify.ui.resources.home_metrics
import com.varuna.rustify.ui.resources.nano_chat_title
import com.varuna.rustify.ui.resources.settings_cat_advanced
import com.varuna.rustify.ui.resources.settings_cat_audio
import com.varuna.rustify.ui.resources.settings_cat_downloads
import com.varuna.rustify.ui.resources.settings_cat_general
import com.varuna.rustify.ui.resources.settings_cat_integrations
import com.varuna.rustify.ui.resources.settings_edit_matches
import org.jetbrains.compose.resources.stringResource

// The pieces every Settings section uses (rows, the category menu) and the two easter eggs, moved
// from the app's SettingsCommon.kt with the screen's frame (docs serie-4.x/27, phase E). The
// sections only Android has still use them from the app, in the same package.

// Clickable without the default indication (ripple/highlight), which looked poor over text and emoji.
@Composable
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * Level 1 egg: the crab vibrates and each tap fires an "ORA!" with an elastic bounce, while
 * ゴゴゴ symbols rise in the background. After 9 taps it chains into [JojoTimeStop] (level 2).
 * Texts arrive already localized (as resolved Strings) to respect the app's language.
 */
@Composable
fun JojoOraOra(title: String, msg: String, oraCount: Int, onOra: () -> Unit, onClose: () -> Unit) {
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
fun JojoTimeStop(message: String, onContinue: () -> Unit, onDismiss: () -> Unit) {
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
                    val a0 = (rayAngle + i * step) * kotlin.math.PI / 180.0
                    val a1 = (rayAngle + i * step + step) * kotlin.math.PI / 180.0
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

@Composable
fun backendFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedContainerColor = Color(0xFF121212), unfocusedContainerColor = Color(0xFF121212),
    focusedLabelColor = Color(0xFF1DB954), focusedIndicatorColor = Color(0xFF1DB954)
)

// Settings category menu (sub-screens). Each row opens its category.
@Composable
fun SettingsCategoryMenu(onPick: (String) -> Unit) {
    val cats = listOf(
        "general" to stringResource(Res.string.settings_cat_general),
        "audio" to stringResource(Res.string.settings_cat_audio),
        "downloads" to stringResource(Res.string.settings_cat_downloads),
        "integrations" to stringResource(Res.string.settings_cat_integrations),
        "advanced" to stringResource(Res.string.settings_cat_advanced)
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

// "Advanced" category: access to metrics, the match editor, the audio lab and the on-device chat.
@Composable
fun AdvancedLinks(
    onMetrics: () -> Unit,
    onMatchEditor: () -> Unit,
    onNanoChat: () -> Unit = {},
    onAudioLab: () -> Unit = {},
) {
    Spacer(Modifier.height(16.dp))
    val services = com.varuna.rustify.ui.LocalAppServices.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buildList {
            add(stringResource(Res.string.home_metrics) to onMetrics)
            add(stringResource(Res.string.settings_edit_matches) to onMatchEditor)
            if (services.audioLab != null && services.can(com.varuna.rustify.ui.AppServices.Feature.AUDIO_LAB)) {
                add(stringResource(Res.string.audio_lab_title) to onAudioLab)
            }
            val chat = services.chatModel()
            if (chat != null) {
                // Named by the model it talks to: Gemini Nano on the phone, the DJ's model on the desktop.
                add(stringResource(chat.title) to onNanoChat)
            }
        }.forEach { (label, action) ->
            Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(label, color = Color.White, modifier = Modifier.weight(1f))
                Text("›", color = Color.Gray)
            }
        }
    }
}

// Standard row of title + description + Switch (avoids repeating the same boilerplate for every setting).
@Composable
fun SettingSwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
