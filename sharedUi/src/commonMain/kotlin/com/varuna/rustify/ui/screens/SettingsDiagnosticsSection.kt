// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SettingsDiagnosticsSection.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.desktop_logs_desc
import com.varuna.rustify.ui.resources.settings_diagnostics
import com.varuna.rustify.ui.resources.settings_view_logs
import org.jetbrains.compose.resources.stringResource

/**
 * Diagnostics section (log viewer) in Settings' Advanced slot.
 * Shows what Rustify has logged in memory and offers a button to open [com.varuna.rustify.Navigation.Screen.LogViewer].
 */
@Composable
fun DiagnosticsSection(onViewLogs: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(Res.string.settings_diagnostics),
        color = Color(0xFF1DB954),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.desktop_logs_desc), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onViewLogs,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.settings_view_logs), color = Color.White)
            }
        }
    }
}
