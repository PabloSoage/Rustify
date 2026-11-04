// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SettingsDjSection.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.LocalAppServices
import com.varuna.rustify.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// Health dot color (same scheme as the DJ model latency indicator).
private fun voiceDotColor(l: com.varuna.rustify.dj.DjProviders.Latency?): Color =
    when (l ?: com.varuna.rustify.dj.DjProviders.Latency.UNKNOWN) {
        com.varuna.rustify.dj.DjProviders.Latency.FAST -> Color(0xFF1DB954)
        com.varuna.rustify.dj.DjProviders.Latency.OK -> Color(0xFFFFC107)
        com.varuna.rustify.dj.DjProviders.Latency.SLOW -> Color(0xFFFF7043)
        com.varuna.rustify.dj.DjProviders.Latency.DOWN -> Color(0xFFE53935)
        else -> Color.Gray
    }

/**
 * General's DJ section: the mode and the OpenAI-compatible endpoint, the voice and the models. Over
 * the platform's preferences and voice (`AppServices.djVoice`, whose engines are the ones offered);
 * under "local", the platform's own panel (`AppServices.LocalDjSettings`: Gemini Nano on Android).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjSettingsSection() {
    val platform = LocalAppPlatform.current
    val services = LocalAppServices.current
    val voice = services.djVoice
    val prefs = platform.prefs()
    // DJ AI: mode (heuristic / API / local) plus configuration for the OpenAI-compatible endpoint.
    var djMode by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_MODE) ?: "heuristic") }
    var djApiBaseUrl by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_API_BASE_URL) ?: com.varuna.rustify.dj.DjSettings.DEFAULT_API_BASE_URL) }
    var djApiModel by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_API_MODEL) ?: com.varuna.rustify.dj.DjSettings.DEFAULT_API_MODEL) }
    var djApiKey by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_API_KEY) ?: "") }
    Column {
                Spacer(modifier = Modifier.height(24.dp))

                // DJ AI section.
                Text(
                    text = stringResource(Res.string.settings_dj),
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
                        Text(stringResource(Res.string.settings_dj_mode), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(Res.string.settings_dj_mode_desc), color = Color.Gray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        val djModes = listOf(
                            "heuristic" to stringResource(Res.string.dj_mode_heuristic),
                            "api" to stringResource(Res.string.dj_mode_api),
                            "local" to stringResource(Res.string.dj_mode_local)
                        )
                        djModes.forEach { (code, name) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        djMode = code
                                        prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_MODE, code)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = djMode == code,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                )
                                Spacer(modifier = Modifier.padding(start = 12.dp))
                                Text(name, color = Color.White, fontSize = 15.sp)
                            }
                        }

                        if (djMode == "api") {
                            Spacer(modifier = Modifier.height(12.dp))

                            // AI provider: free/keyless list plus a latency indicator and add/remove controls.
                            Text(stringResource(Res.string.settings_dj_provider), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(6.dp))
                            var providersVersion by remember { mutableIntStateOf(0) }
                            val djProviders = remember(providersVersion) { com.varuna.rustify.dj.DjProviders.visibleProviders(platform) }
                            val djSelectedId = remember(providersVersion) { com.varuna.rustify.dj.DjProviders.selectedId(platform) }
                            val djLatencies = remember { androidx.compose.runtime.mutableStateMapOf<String, com.varuna.rustify.dj.DjProviders.Latency>() }
                            androidx.compose.runtime.LaunchedEffect(providersVersion) {
                                djProviders.forEach { p ->
                                    val ms = com.varuna.rustify.dj.DjProviders.measureLatency(p.baseUrl)
                                    djLatencies[p.id] = com.varuna.rustify.dj.DjProviders.classify(ms)
                                }
                            }
                            djProviders.forEach { p ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            com.varuna.rustify.dj.DjProviders.select(platform, p)
                                            djApiBaseUrl = p.baseUrl; djApiModel = p.model; djApiKey = p.apiKey
                                            providersVersion++
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = djSelectedId == p.id,
                                        onClick = null,
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                    )
                                    Spacer(modifier = Modifier.padding(start = 8.dp))
                                    val dotColor = when (djLatencies[p.id] ?: com.varuna.rustify.dj.DjProviders.Latency.UNKNOWN) {
                                        com.varuna.rustify.dj.DjProviders.Latency.FAST -> Color(0xFF1DB954)
                                        com.varuna.rustify.dj.DjProviders.Latency.OK -> Color(0xFFFFC107)
                                        com.varuna.rustify.dj.DjProviders.Latency.SLOW -> Color(0xFFFF7043)
                                        com.varuna.rustify.dj.DjProviders.Latency.DOWN -> Color(0xFFE53935)
                                        else -> Color.Gray
                                    }
                                    Text("●", color = dotColor, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.padding(start = 8.dp))
                                    Text(p.label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        com.varuna.rustify.dj.DjProviders.removeProvider(platform, p)
                                        providersVersion++
                                    }) {
                                        Text(stringResource(Res.string.settings_dj_remove), color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                            }
                            var showAddProvider by remember { mutableStateOf(false) }
                            TextButton(onClick = { showAddProvider = true }) {
                                Text(stringResource(Res.string.settings_dj_add_provider), color = Color(0xFF1DB954))
                            }
                            if (showAddProvider) {
                                var npLabel by remember { mutableStateOf("") }
                                var npUrl by remember { mutableStateOf("") }
                                var npModel by remember { mutableStateOf("") }
                                var npKey by remember { mutableStateOf("") }
                                AlertDialog(
                                    onDismissRequest = { showAddProvider = false },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            if (npUrl.isNotBlank() && npModel.isNotBlank()) {
                                                com.varuna.rustify.dj.DjProviders.addCustom(platform, npLabel, npUrl, npModel, npKey)
                                                showAddProvider = false; providersVersion++
                                            }
                                        }) { Text(stringResource(Res.string.settings_dj_add_provider), color = Color(0xFF1DB954)) }
                                    },
                                    dismissButton = { TextButton(onClick = { showAddProvider = false }) { Text(stringResource(Res.string.common_cancel)) } },
                                    title = { Text(stringResource(Res.string.settings_dj_add_provider)) },
                                    text = {
                                        Column {
                                            OutlinedTextField(value = npLabel, onValueChange = { npLabel = it }, label = { Text(stringResource(Res.string.common_name)) }, singleLine = true)
                                            Spacer(Modifier.height(6.dp))
                                            OutlinedTextField(value = npUrl, onValueChange = { npUrl = it }, label = { Text(stringResource(Res.string.settings_dj_base_url)) }, singleLine = true)
                                            Spacer(Modifier.height(6.dp))
                                            OutlinedTextField(value = npModel, onValueChange = { npModel = it }, label = { Text(stringResource(Res.string.common_model)) }, singleLine = true)
                                            Spacer(Modifier.height(6.dp))
                                            OutlinedTextField(value = npKey, onValueChange = { npKey = it }, label = { Text(stringResource(Res.string.common_api_key_optional)) }, singleLine = true)
                                        }
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            val djFieldColors = TextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF121212),
                                unfocusedContainerColor = Color(0xFF121212)
                            )
                            OutlinedTextField(
                                value = djApiBaseUrl,
                                onValueChange = { djApiBaseUrl = it; prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_API_BASE_URL, it) },
                                label = { Text(stringResource(Res.string.settings_dj_base_url)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = djFieldColors
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = djApiModel,
                                onValueChange = { djApiModel = it; prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_API_MODEL, it) },
                                label = { Text(stringResource(Res.string.settings_dj_model)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = djFieldColors
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = djApiKey,
                                onValueChange = { djApiKey = it; prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_API_KEY, it) },
                                label = { Text(stringResource(Res.string.settings_dj_api_key)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = djFieldColors
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(Res.string.settings_dj_api_desc), color = Color.Gray, fontSize = 12.sp)
                        }

                        if (djMode == "local") {
                            Spacer(modifier = Modifier.height(8.dp))
                            services.LocalDjSettings()
                        }

                        // DJ voice (hybrid: native TTS plus an optional cloud endpoint).
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(Res.string.settings_dj_voice), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        // Said, not hidden: the settings stay, and apply once there is a voice.
                        if (!services.can(com.varuna.rustify.ui.AppServices.Feature.DJ_VOICE)) {
                            Text(stringResource(Res.string.settings_dj_voice_none), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        val djVoiceFieldColors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF121212),
                            unfocusedContainerColor = Color(0xFF121212)
                        )
                        var djVoiceEnabled by remember { mutableStateOf(prefs.getBoolean(com.varuna.rustify.dj.DjSettings.KEY_VOICE_ENABLED, true)) }
                        var djVoiceLang by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_LANG) ?: "") }
                        var djVoiceCloudUrl by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_URL) ?: "") }
                        var djVoiceCloudKey by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_KEY) ?: "") }
                        var djTtsEngine by remember { mutableStateOf(com.varuna.rustify.dj.DjSettings.ttsEngine(platform)) }
                        var djVoiceNativeName by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_NATIVE_NAME) ?: "") }
                        var djCloudVoice by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_VOICE) ?: "alloy") }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(Res.string.settings_dj_voice_enabled), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = djVoiceEnabled,
                                onCheckedChange = {
                                    djVoiceEnabled = it
                                    prefs.putBoolean(com.varuna.rustify.dj.DjSettings.KEY_VOICE_ENABLED, it)
                                },
                                colors = com.varuna.rustify.ui.theme.RustifySwitch.colors()
                            )
                        }
                        var voiceLangExpanded by remember { mutableStateOf(false) }
                        LaunchedEffect(djMode) { voiceLangExpanded = false }
                        val systemDefaultLabel = stringResource(Res.string.settings_dj_voice_lang_system)
                        val allVoiceLangs = listOf("en", "es", "ja", "fr", "de", "pt", "it", "ko", "zh")
                        val voiceLangOptions = remember(djMode, systemDefaultLabel) {
                            val appLang = prefs.getString("app_language") ?: "system"
                            val displayLocale = if (appLang == "system") java.util.Locale.getDefault() else java.util.Locale.forLanguageTag(appLang)
                            val filteredLangs = if (djMode == "api") allVoiceLangs else listOf("en", "es")
                            listOf("" to systemDefaultLabel) + filteredLangs.map { code ->
                                code to java.util.Locale.forLanguageTag(code).getDisplayName(displayLocale)
                            }
                        }
                        val voiceLangLabel = voiceLangOptions.firstOrNull { it.first == djVoiceLang }?.second ?: djVoiceLang
                        ExposedDropdownMenuBox(
                            expanded = voiceLangExpanded,
                            onExpandedChange = { voiceLangExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = voiceLangLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(Res.string.settings_dj_voice_lang)) },
                                singleLine = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceLangExpanded)
                                },
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                colors = djVoiceFieldColors
                            )
                            DropdownMenu(
                                expanded = voiceLangExpanded,
                                onDismissRequest = { voiceLangExpanded = false }
                            ) {
                                voiceLangOptions.forEach { (code, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            djVoiceLang = code
                                            prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_LANG, code)
                                            voiceLangExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        // Voice engine (native Android / keyless Pollinations / own OpenAI endpoint).
                        Spacer(modifier = Modifier.height(8.dp))
                        var ttsEngineExpanded by remember { mutableStateOf(false) }
                        val ttsEngineOptions = listOf(
                            "native" to stringResource(Res.string.settings_dj_tts_native),
                            "edge" to stringResource(Res.string.settings_dj_tts_edge),
                            "gtranslate" to stringResource(Res.string.settings_dj_tts_gtranslate),
                            "pollinations" to stringResource(Res.string.settings_dj_tts_pollinations),
                            "openai" to stringResource(Res.string.settings_dj_tts_openai)
                        ).filter { it.first in voice.engines }  // those this platform has (Android: all)
                        val ttsEngineLabel = ttsEngineOptions.firstOrNull { it.first == djTtsEngine }?.second ?: djTtsEngine
                        ExposedDropdownMenuBox(expanded = ttsEngineExpanded, onExpandedChange = { ttsEngineExpanded = it }) {
                            OutlinedTextField(
                                value = ttsEngineLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(Res.string.settings_dj_tts_engine)) },
                                singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ttsEngineExpanded) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                colors = djVoiceFieldColors
                            )
                            DropdownMenu(expanded = ttsEngineExpanded, onDismissRequest = { ttsEngineExpanded = false }) {
                                ttsEngineOptions.forEach { (code, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            djTtsEngine = code
                                            prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_TTS_ENGINE, code)
                                            ttsEngineExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        when (djTtsEngine) {
                            // Specific native voice, filtered by the chosen voice language.
                            "native" -> {
                                var nativeVoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                                LaunchedEffect(djVoiceLang) {
                                    voice.queryVoices(djVoiceLang) { nativeVoices = it }
                                }
                                var nativeVoiceExpanded by remember { mutableStateOf(false) }
                                val systemVoiceLabel = stringResource(Res.string.settings_dj_voice_native_system)
                                val nativeOptions = remember(nativeVoices, systemVoiceLabel) {
                                    listOf("" to systemVoiceLabel) + nativeVoices
                                }
                                val nativeVoiceLabel = nativeOptions.firstOrNull { it.first == djVoiceNativeName }?.second
                                    ?: (djVoiceNativeName.ifBlank { systemVoiceLabel })
                                Spacer(modifier = Modifier.height(8.dp))
                                ExposedDropdownMenuBox(expanded = nativeVoiceExpanded, onExpandedChange = { nativeVoiceExpanded = it }) {
                                    OutlinedTextField(
                                        value = nativeVoiceLabel,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(Res.string.settings_dj_voice_native)) },
                                        singleLine = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = nativeVoiceExpanded) },
                                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                        colors = djVoiceFieldColors
                                    )
                                    DropdownMenu(expanded = nativeVoiceExpanded, onDismissRequest = { nativeVoiceExpanded = false }) {
                                        nativeOptions.forEach { (id, name) ->
                                            DropdownMenuItem(
                                                text = { Text(name) },
                                                onClick = {
                                                    djVoiceNativeName = id
                                                    prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_NATIVE_NAME, id)
                                                    voice.refreshConfig()
                                                    nativeVoiceExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            // Microsoft Edge: free neural voices with no token. Voice selector plus health check.
                            "edge" -> {
                                var edgeExpanded by remember { mutableStateOf(false) }
                                val edgeHealth = remember { mutableStateOf<com.varuna.rustify.dj.DjProviders.Latency?>(null) }
                                androidx.compose.runtime.LaunchedEffect(Unit) {
                                    edgeHealth.value = com.varuna.rustify.dj.DjProviders.classify(voice.probeEdge())
                                }
                                val edgeVoices = com.varuna.rustify.dj.DjSettings.EDGE_VOICES
                                val selVoice = if (djCloudVoice.contains("Neural")) djCloudVoice else com.varuna.rustify.dj.DjSettings.EDGE_DEFAULT_VOICE
                                val selLabel = edgeVoices.firstOrNull { it.first == selVoice }?.second ?: selVoice
                                Spacer(modifier = Modifier.height(8.dp))
                                ExposedDropdownMenuBox(expanded = edgeExpanded, onExpandedChange = { edgeExpanded = it }) {
                                    OutlinedTextField(
                                        value = selLabel,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(Res.string.settings_dj_voice_pick)) },
                                        singleLine = true,
                                        leadingIcon = { Text("●", color = voiceDotColor(edgeHealth.value), fontSize = 12.sp) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = edgeExpanded) },
                                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                        colors = djVoiceFieldColors
                                    )
                                    DropdownMenu(expanded = edgeExpanded, onDismissRequest = { edgeExpanded = false }) {
                                        edgeVoices.forEach { (id, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    djCloudVoice = id
                                                    prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_VOICE, id)
                                                    edgeExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                Text(stringResource(Res.string.settings_dj_tts_edge_note), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            // Google Translate: voice per language (no voice selection), free and tokenless.
                            "gtranslate" -> {
                                val gtHealth = remember { mutableStateOf<com.varuna.rustify.dj.DjProviders.Latency?>(null) }
                                androidx.compose.runtime.LaunchedEffect(djVoiceLang) {
                                    gtHealth.value = com.varuna.rustify.dj.DjProviders.classify(voice.probeGoogleTranslate())
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("●", color = voiceDotColor(gtHealth.value), fontSize = 12.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(Res.string.settings_dj_tts_gtranslate_note), color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                            // OpenAI voices (keyless Pollinations or own OpenAI) plus the endpoint fields.
                            else -> {
                                var cloudVoiceExpanded by remember { mutableStateOf(false) }
                                // Health of the Pollinations voices (same ● indicator as the DJ model):
                                // a short real ping per voice. Pollinations' free audio API was deprecated
                                // (openai-audio → 404), so these usually show RED; the indicator makes that
                                // explicit so the user picks the native voice or their own OpenAI-compatible
                                // endpoint instead of waiting for a fallback.
                                val voiceHealth = remember { androidx.compose.runtime.mutableStateMapOf<String, com.varuna.rustify.dj.DjProviders.Latency>() }
                                if (djTtsEngine == "pollinations") {
                                    androidx.compose.runtime.LaunchedEffect(Unit) {
                                        com.varuna.rustify.dj.DjSettings.OPENAI_VOICES.forEach { v ->
                                            launch {
                                                val ms = voice.probeVoice(v)
                                                voiceHealth[v] = com.varuna.rustify.dj.DjProviders.classify(ms)
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                ExposedDropdownMenuBox(expanded = cloudVoiceExpanded, onExpandedChange = { cloudVoiceExpanded = it }) {
                                    OutlinedTextField(
                                        value = djCloudVoice,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(Res.string.settings_dj_voice_pick)) },
                                        singleLine = true,
                                        leadingIcon = if (djTtsEngine == "pollinations") {
                                            { Text("●", color = voiceDotColor(voiceHealth[djCloudVoice]), fontSize = 12.sp) }
                                        } else null,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cloudVoiceExpanded) },
                                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                        colors = djVoiceFieldColors
                                    )
                                    DropdownMenu(expanded = cloudVoiceExpanded, onDismissRequest = { cloudVoiceExpanded = false }) {
                                        com.varuna.rustify.dj.DjSettings.OPENAI_VOICES.forEach { v ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        if (djTtsEngine == "pollinations") {
                                                            Text("●", color = voiceDotColor(voiceHealth[v]), fontSize = 12.sp)
                                                            Spacer(Modifier.width(8.dp))
                                                        }
                                                        Text(v.replaceFirstChar { it.uppercase() })
                                                    }
                                                },
                                                onClick = {
                                                    djCloudVoice = v
                                                    prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_VOICE, v)
                                                    cloudVoiceExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                if (djTtsEngine == "pollinations") {
                                    Text(
                                        stringResource(Res.string.settings_dj_tts_pollinations_note),
                                        color = Color.Gray, fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = djVoiceCloudUrl,
                                        onValueChange = { djVoiceCloudUrl = it; prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_URL, it) },
                                        label = { Text(stringResource(Res.string.settings_dj_voice_cloud_url)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = djVoiceFieldColors
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = djVoiceCloudKey,
                                        onValueChange = { djVoiceCloudKey = it; prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_VOICE_CLOUD_KEY, it) },
                                        label = { Text(stringResource(Res.string.settings_dj_voice_cloud_key)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = djVoiceFieldColors
                                    )
                                }
                            }
                        }

                        // Previews the selected voice with a test phrase (ignores the "DJ enabled" toggle,
                        // so it can be heard while choosing a voice from Settings).
                        var previewLoading by remember { mutableStateOf(false) }
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                previewLoading = true
                                voice.init()
                                voice.refreshConfig()
                                voice.preview(onDone = { previewLoading = false })
                            },
                            enabled = !previewLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (previewLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.common_loading), color = Color.Black, fontSize = 14.sp)
                            } else {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(Res.string.settings_dj_voice_preview), fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Automatic DJ source (favorites / balanced / discover).
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(Res.string.settings_dj_auto_source), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        var djAutoSource by remember { mutableStateOf(prefs.getString(com.varuna.rustify.dj.DjSettings.KEY_AUTO_SOURCE) ?: "balanced") }
                        val djAutoSources = listOf(
                            "favorites" to stringResource(Res.string.dj_auto_source_favorites),
                            "balanced" to stringResource(Res.string.dj_auto_source_balanced),
                            "discover" to stringResource(Res.string.dj_auto_source_discover)
                        )
                        djAutoSources.forEach { (code, name) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        djAutoSource = code
                                        prefs.putString(com.varuna.rustify.dj.DjSettings.KEY_AUTO_SOURCE, code)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = djAutoSource == code,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                                )
                                Spacer(modifier = Modifier.padding(start = 12.dp))
                                Text(name, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }

    }
}
