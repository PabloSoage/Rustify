// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/Toasts.kt
package com.varuna.rustify.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** A message for [Toasts], with a serial so the same text twice shows twice. */
data class ToastMessage(val message: String, val long: Boolean, val serial: Long)

/**
 * The phone's toasts where the system has none (the desktop's window, iOS): a dark pill over the bottom, as long as Android keeps one
 * (2 s, or 3.5 s for a long one). A new message replaces the one showing.
 */
@Composable
fun Toasts(toasts: StateFlow<ToastMessage?>, modifier: Modifier = Modifier) {
    val toast by toasts.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(toast?.serial) {
        val t = toast ?: return@LaunchedEffect
        visible = true
        delay(if (t.long) 3_500 else 2_000)
        visible = false
    }
    AnimatedVisibility(visible, modifier.padding(bottom = 110.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(
            toast?.message.orEmpty(),
            color = Color.White,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .background(Color(0xE6323232), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
