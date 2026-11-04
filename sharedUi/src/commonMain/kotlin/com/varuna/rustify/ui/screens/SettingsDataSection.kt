// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SettingsDataSection.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.rememberOpenFile
import com.varuna.rustify.ui.rememberSaveFile
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.settings_data_export
import com.varuna.rustify.ui.resources.settings_data_export_button
import com.varuna.rustify.ui.resources.settings_data_export_desc
import com.varuna.rustify.ui.resources.settings_data_export_failed
import com.varuna.rustify.ui.resources.settings_data_export_ok
import com.varuna.rustify.ui.resources.settings_data_restore_button
import com.varuna.rustify.ui.resources.settings_data_restore_ok
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource

/**
 * Export and restore everything the engine keeps — point L.
 *
 * Next to the stream cache because both answer "what is this app holding, and can I get it back".
 * The Drive backup that already exists is a *restore* mechanism — it answers "my phone died". This
 * answers "I want to read what this knows about me" and "I am leaving", which a cloud backup does
 * not. The core writes and reads the file, so what one platform exports another restores (moved from
 * the app's SettingsStorageSections.kt, docs serie-4.x/27 phase E).
 *
 * **No credential is ever in the file.** Not the Spotify session, not the ARL. An export is a thing
 * that gets mailed to a laptop or handed to someone for help, and the one thing it must never be is
 * a way to hand over an account. That is enforced in the core, with a test.
 */
@Composable
fun DataExportSection() {
    val platform = LocalAppPlatform.current
    val okMsg = stringResource(Res.string.settings_data_export_ok)
    val failMsg = stringResource(Res.string.settings_data_export_failed)
    val restoredMsg = stringResource(Res.string.settings_data_restore_ok)

    val export = rememberSaveFile("application/json") { result ->
        if (result != null) platform.toast(if (result.isSuccess) okMsg else failMsg)
    }

    val restore = rememberOpenFile(
        listOf("application/json", "*/*"),
        read = { _, bytes ->
            val answer = RustifyJson.parseToJsonElement(NativeEngine.restoreData(bytes.decodeToString())) as JsonObject
            // The core's own message when it refuses — an export from a newer version, or a file
            // that is not one at all. Shown rather than flattened into "failed", because those
            // two need different things from the user.
            if (answer["success"]?.jsonPrimitive?.booleanOrNull == true) restoredMsg
            else answer["error"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: failMsg
        },
    ) { result ->
        if (result != null) platform.toast(result.getOrElse { failMsg }, long = true)
    }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(Res.string.settings_data_export),
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
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.settings_data_export_desc),
                color = Color.Gray,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { export("rustify-data.json") { it.write(NativeEngine.exportData("Rustify").encodeToByteArray()) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(Res.string.settings_data_export_button), color = Color.Black)
                }
                OutlinedButton(
                    onClick = restore,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(Res.string.settings_data_restore_button), color = Color.White)
                }
            }
        }
    }
}
