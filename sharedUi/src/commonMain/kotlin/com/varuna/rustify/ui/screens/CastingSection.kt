// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/CastingSection.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.cast.CastDiscovery
import com.varuna.rustify.cast.CastSession
import com.varuna.rustify.player.LocalPlayer
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.settings_casting
import com.varuna.rustify.ui.resources.settings_casting_desc
import com.varuna.rustify.ui.resources.settings_casting_enabled
import com.varuna.rustify.ui.resources.settings_casting_idle
import com.varuna.rustify.ui.resources.settings_casting_none
import com.varuna.rustify.ui.resources.settings_casting_search
import com.varuna.rustify.ui.resources.settings_casting_searching
import com.varuna.rustify.ui.resources.settings_casting_stop
import com.varuna.rustify.ui.resources.settings_casting_to
import com.varuna.rustify.ui.resources.settings_local_network_permission_denied
import com.varuna.rustify.ui.resources.settings_open_app_settings
import com.varuna.rustify.ui.theme.RustifySwitch
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Casting to a DLNA / UPnP renderer on the local network (E16).
 *
 * CastSession decides when the local server stops being loopback-only.
 * Discovery deliberately opens nothing: it is a question sent to the network and answers coming
 * back. The port only exists once you pick a device and press play on it.
 */
@Composable
fun CastingSection() {
    val platform = LocalAppPlatform.current
    val store = remember(platform) { platform.prefs("rustify_settings") }
    val scope = rememberCoroutineScope()
    var enabled by remember {
        mutableStateOf(CastSession.isEnabled(store))
    }
    var searching by remember { mutableStateOf(false) }
    var devices by remember {
        mutableStateOf<List<CastDiscovery.Device>>(emptyList())
    }
    var active by remember { mutableStateOf(CastSession.device) }
    /** Why the last attempt did not start. Shown as-is: every reason is something the user can act on. */
    var failure by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var localNetworkDenied by remember { mutableStateOf(false) }
    val noneFound = stringResource(Res.string.settings_casting_none)
    val player = LocalPlayer.current

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(Res.string.settings_casting),
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(Res.string.settings_casting_enabled),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        if (on) {
                            scope.launch {
                                val ok = platform.ensureLocalNetwork()
                                if (ok) {
                                    enabled = true
                                    CastSession.setEnabled(store, true)
                                    localNetworkDenied = false
                                } else {
                                    localNetworkDenied = true
                                }
                            }
                        } else {
                            enabled = false
                            CastSession.setEnabled(store, false)
                            devices = emptyList()
                            active = null
                            failure = null
                            localNetworkDenied = false
                        }
                    },
                    colors = RustifySwitch.colors()
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.settings_casting_desc),
                color = Color.Gray,
                fontSize = 12.sp
            )

            if (localNetworkDenied) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.settings_local_network_permission_denied),
                    color = Color(0xFFE57373),
                    fontSize = 12.sp
                )
                TextButton(onClick = { platform.openAppSettings() }) {
                    Text(
                        stringResource(Res.string.settings_open_app_settings),
                        color = Color(0xFF1DB954),
                        fontSize = 12.sp
                    )
                }
            }

            if (enabled) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        active?.let { stringResource(Res.string.settings_casting_to, it.friendlyName) }
                            ?: stringResource(Res.string.settings_casting_idle),
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (active != null) {
                        TextButton(onClick = {
                            player.stopCasting()
                            active = null
                            failure = null
                        }) {
                            Text(
                                stringResource(Res.string.settings_casting_stop),
                                color = Color(0xFF1DB954)
                            )
                        }
                    } else {
                        TextButton(
                            enabled = !searching,
                            onClick = {
                                scope.launch {
                                    val ok = platform.ensureLocalNetwork()
                                    if (!ok) {
                                        localNetworkDenied = true
                                        return@launch
                                    }
                                    localNetworkDenied = false
                                    searching = true
                                    devices = CastDiscovery.search()
                                    searching = false
                                }
                            }
                        ) {
                            Text(
                                stringResource(
                                    if (searching) Res.string.settings_casting_searching
                                    else Res.string.settings_casting_search
                                ),
                                color = Color(0xFF1DB954)
                            )
                        }
                    }
                }

                if (active == null && !searching && devices.isEmpty()) {
                    Text(noneFound, color = Color.Gray, fontSize = 12.sp)
                }
                failure?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE57373), fontSize = 12.sp)
                }
                for (found in devices) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !starting) {
                                failure = null
                                starting = true
                                scope.launch {
                                    val why = player.startCastingTo(found)
                                    failure = why
                                    active = if (why == null) found else null
                                    starting = false
                                }
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(found.friendlyName, color = Color.White, fontSize = 14.sp)
                            Text(found.address, color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
