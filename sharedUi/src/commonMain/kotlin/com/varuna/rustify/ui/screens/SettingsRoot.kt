// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SettingsRoot.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.varuna.rustify.Screen
import com.varuna.rustify.ui.AppShell
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.PlatformBackHandler
import com.varuna.rustify.ui.SettingsSlot
import com.varuna.rustify.ui.ShellHost
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.egg_found_msg
import com.varuna.rustify.ui.resources.egg_found_title
import com.varuna.rustify.ui.resources.egg_jojo_msg
import com.varuna.rustify.ui.resources.settings_lang_en
import com.varuna.rustify.ui.resources.settings_lang_es
import com.varuna.rustify.ui.resources.settings_lang_ja
import com.varuna.rustify.ui.resources.settings_lang_system
import com.varuna.rustify.ui.resources.settings_language
import com.varuna.rustify.ui.resources.settings_logout
import com.varuna.rustify.ui.resources.settings_rustify_link
import com.varuna.rustify.ui.resources.settings_session
import com.varuna.rustify.ui.resources.settings_share_as_rustify
import com.varuna.rustify.ui.resources.settings_share_as_rustify_desc
import com.varuna.rustify.ui.resources.settings_title
import com.varuna.rustify.ui.resources.settings_wrapper_blank
import com.varuna.rustify.ui.resources.settings_wrapper_custom
import com.varuna.rustify.ui.resources.settings_wrapper_host
import com.varuna.rustify.ui.resources.settings_wrapper_host_desc
import com.varuna.rustify.ui.resources.spotify_sign_in
import com.varuna.rustify.util.AppLinksHosts
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Settings: the category menu and each category's sections (docs serie-4.x/27, phase E). The
 * sections every platform has are here; the ones only one platform has (Android's yt-dlp, Drive,
 * storage, DJ, audio back-ends…) the shell draws at their [SettingsSlot], so a category keeps the
 * order it always had on the phone and the desktop shows what it has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(shell: AppShell, host: ShellHost) {
    // Category-based settings (sub-screens): null = category menu; otherwise the open category.
    // The state is hoisted in the root so it survives opening and returning from sub-screens.
    val settingsCategory = host.settingsCategory
    val onCategoryChange = host.onSettingsCategory
    PlatformBackHandler(enabled = settingsCategory != null) { onCategoryChange(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(Res.string.settings_title), color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (settingsCategory != null) onCategoryChange(null) else host.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                actions = {
                    // Check for updates (only in the root settings menu).
                    if (settingsCategory == null) shell.SettingsActions(host)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
          when (settingsCategory) {
            null -> SettingsCategoryMenu { onCategoryChange(it) }
            "audio" -> {
                Spacer(modifier = Modifier.height(8.dp))
                shell.SettingsSections(SettingsSlot.AUDIO, host)
            }
            "downloads" -> shell.SettingsSections(SettingsSlot.DOWNLOADS, host)
            "integrations" -> {
                Spacer(modifier = Modifier.height(8.dp))
                shell.SettingsSections(SettingsSlot.INTEGRATIONS, host)
            }
            "advanced" -> {
                Spacer(modifier = Modifier.height(8.dp))
                shell.SettingsSections(SettingsSlot.ADVANCED, host)
                AdvancedLinks(
                    onMetrics = { host.navigate(Screen.Metrics) },
                    onMatchEditor = { host.navigate(Screen.MatchEditor) },
                    onNanoChat = { host.navigate(Screen.NanoChat) },
                    onAudioLab = { host.navigate(Screen.AudioLab) },
                )
            }
            else -> {
                shell.SettingsSections(SettingsSlot.GENERAL, host)
                LanguageSection(shell, host)
                RustifyLinkSection()
                shell.SettingsSections(SettingsSlot.GENERAL_DJ, host)
                SessionSection(host)
                VersionLine()
                Spacer(modifier = Modifier.height(32.dp))
            }
          }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color(0xFF1DB954),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun LanguageSection(shell: AppShell, host: ShellHost) {
    val prefs = LocalAppPlatform.current.prefs()
    Spacer(modifier = Modifier.height(24.dp))
    SectionTitle(stringResource(Res.string.settings_language))
    SectionCard {
        var selectedLang by remember { mutableStateOf(prefs.getString("app_language") ?: "system") }
        val languages = listOf(
            "system" to stringResource(Res.string.settings_lang_system),
            "en" to stringResource(Res.string.settings_lang_en),
            "es" to stringResource(Res.string.settings_lang_es),
            "ja" to stringResource(Res.string.settings_lang_ja)
        )
        languages.forEach { (code, name) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedLang = code
                        prefs.putString("app_language", code)
                        shell.applyLanguage(code)
                        host.onLanguageChanged(code)
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedLang == code,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                )
                Spacer(modifier = Modifier.padding(start = 12.dp))
                Text(name, color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

// The host shared Spotify links are wrapped in, and whether they are.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RustifyLinkSection() {
    val prefs = LocalAppPlatform.current.prefs()
    // null pref (never set) → preselect default. Explicit "" (user cleared it) → blank/fallback.
    var wrapperHost by remember {
        mutableStateOf(prefs.getString("rustify_wrapper_host") ?: AppLinksHosts.DEFAULT_HOST)
    }
    // "Custom…" mode: user typing a free host not in verifiedHosts.
    var wrapperHostCustom by remember {
        mutableStateOf(wrapperHost.isNotBlank() && wrapperHost !in AppLinksHosts.verifiedHosts)
    }
    var wrapperMenuExpanded by remember { mutableStateOf(false) }
    // First run (pref never set): persist the preselected default so behavior matches the UI.
    LaunchedEffect(Unit) {
        if (!prefs.contains("rustify_wrapper_host")) prefs.putString("rustify_wrapper_host", AppLinksHosts.DEFAULT_HOST)
    }
    var shareAsRustify by remember { mutableStateOf(prefs.getBoolean("share_as_rustify_link", false)) }

    Spacer(modifier = Modifier.height(24.dp))
    SectionTitle(stringResource(Res.string.settings_rustify_link))
    SectionCard {
        // Persist helper: blank → empty pref (fallback rustify://); else store trimmed host.
        val persistWrapperHost: (String) -> Unit = { value ->
            wrapperHost = value
            prefs.putString("rustify_wrapper_host", if (value.isBlank()) "" else value.trim())
        }
        val customOption = stringResource(Res.string.settings_wrapper_custom)
        val blankOption = stringResource(Res.string.settings_wrapper_blank)
        val fieldColors = TextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = Color(0xFF121212),
            unfocusedContainerColor = Color(0xFF121212)
        )
        val selectionLabel = when {
            wrapperHostCustom -> customOption
            wrapperHost.isBlank() -> blankOption
            else -> wrapperHost
        }

        ExposedDropdownMenuBox(
            expanded = wrapperMenuExpanded,
            onExpandedChange = { wrapperMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = selectionLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(Res.string.settings_wrapper_host)) },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = wrapperMenuExpanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors = fieldColors
            )
            DropdownMenu(
                expanded = wrapperMenuExpanded,
                onDismissRequest = { wrapperMenuExpanded = false }
            ) {
                AppLinksHosts.verifiedHosts.forEach { host ->
                    DropdownMenuItem(
                        text = { Text(host) },
                        onClick = {
                            wrapperHostCustom = false
                            wrapperMenuExpanded = false
                            persistWrapperHost(host)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(blankOption) },
                    onClick = {
                        wrapperHostCustom = false
                        wrapperMenuExpanded = false
                        persistWrapperHost("")
                    }
                )
                DropdownMenuItem(
                    text = { Text(customOption) },
                    onClick = {
                        wrapperHostCustom = true
                        wrapperMenuExpanded = false
                        // Keep current value; user edits it in the free-text field below.
                    }
                )
            }
        }

        if (wrapperHostCustom) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = wrapperHost,
                onValueChange = { persistWrapperHost(it) },
                label = { Text(customOption) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(stringResource(Res.string.settings_wrapper_host_desc), color = Color.Gray, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(Res.string.settings_share_as_rustify), color = Color.White, fontSize = 14.sp)
                Text(stringResource(Res.string.settings_share_as_rustify_desc), color = Color.Gray, fontSize = 12.sp)
            }
            Switch(
                checked = shareAsRustify,
                onCheckedChange = { checked ->
                    shareAsRustify = checked
                    prefs.putBoolean("share_as_rustify_link", checked)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF1DB954)
                )
            )
        }
    }
}

@Composable
private fun SessionSection(host: ShellHost) {
    val coroutineScope = rememberCoroutineScope()
    Spacer(modifier = Modifier.height(24.dp))
    SectionTitle(stringResource(Res.string.settings_session))
    if (host.isSignedIn) {
        Button(
            onClick = {
                coroutineScope.launch {
                    host.spotifyRepo.logout()
                    // The rest of the app keeps working; only what needs the account goes.
                    host.signedOut()
                    host.back()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(Res.string.settings_logout), color = Color.White, fontWeight = FontWeight.Bold)
        }
    } else {
        Button(
            onClick = host.signIn,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(Res.string.spotify_sign_in), color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

// Easter egg (level 1): tap the version 7 times to reveal an animated crab. Tap it 9 more times to
// trigger the nested egg (level 2), which opens standle.net in the app's language.
@Composable
private fun ColumnScope.VersionLine() {
    val platform = LocalAppPlatform.current
    var eggTaps by remember { mutableIntStateOf(0) }
    var showEgg by remember { mutableStateOf(false) }
    var showJojo by remember { mutableStateOf(false) }
    var oraCount by remember { mutableIntStateOf(0) }
    // Resolved here, in the screen's composition, so the dialogs show the app's language.
    val eggTitle = stringResource(Res.string.egg_found_title)
    val eggMsg = stringResource(Res.string.egg_found_msg)
    val eggJojoMsg = stringResource(Res.string.egg_jojo_msg)
    val webLang = androidx.compose.ui.text.intl.Locale.current.language.let {
        when { it.startsWith("es") -> "es"; it.startsWith("ja") -> "ja"; else -> "en" }
    }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        "Rustify ${platform.appVersion} 🦀",
        color = Color(0xFF555555), fontSize = 12.sp,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            // No ripple: the default highlight looked poor over the text.
            .noRippleClickable { eggTaps++; if (eggTaps >= 7) { eggTaps = 0; oraCount = 0; showEgg = true } }
            .padding(vertical = 10.dp)
    )

    if (showEgg) {
        JojoOraOra(
            title = eggTitle,
            msg = eggMsg,
            oraCount = oraCount,
            onOra = { oraCount++; if (oraCount >= 9) { showEgg = false; showJojo = true } },
            onClose = { showEgg = false }
        )
    }

    if (showJojo) {
        JojoTimeStop(
            message = eggJojoMsg,
            onContinue = { showJojo = false; platform.openUrl("https://standle.net/$webLang") },
            onDismiss = { showJojo = false }
        )
    }
}
