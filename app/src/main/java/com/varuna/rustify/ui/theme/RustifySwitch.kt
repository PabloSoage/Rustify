package com.varuna.rustify.ui.theme

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One look for every switch: white thumb on the green track when on. Left to the theme, a switch
 * got a black thumb (the theme's onPrimary, which is right for text on green buttons) and sat next
 * to ones that set white by hand — the settings screen had both, a row apart.
 */
object RustifySwitch {
    @Composable
    fun colors(): SwitchColors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = Color(0xFF1DB954),
        uncheckedThumbColor = Color(0xFFB3B3B3),
        uncheckedTrackColor = Color(0xFF3E3E3E),
        uncheckedBorderColor = Color(0xFF3E3E3E)
    )
}
