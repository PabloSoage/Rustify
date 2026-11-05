// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SettingsAudioSections.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.engine.EffectsConfig
import com.varuna.rustify.engine.ReplayGainMode
import com.varuna.rustify.engine.StereoConfig
import com.varuna.rustify.player.AlbumShuffle
import com.varuna.rustify.player.AutoEqEntry
import com.varuna.rustify.player.AutoEqProfiles
import com.varuna.rustify.player.Blocklist
import com.varuna.rustify.ui.LocalAppPlatform
import com.varuna.rustify.ui.LocalAppServices
import com.varuna.rustify.ui.resources.*
import com.varuna.rustify.ui.theme.RustifySwitch
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// What Settings has about how music sounds and how the queue moves, on every platform (docs
// serie-4.x/27 §2.5). Each platform places these where it wants them; the phone, where it always did.

/**
 * Autoplay radio: when a queue runs out, songs like the last one follow instead of silence, and they
 * are added to the queue, somewhere you can find them again.
 */
@Composable
fun AutoplayRadioSection() {
    val audio = LocalAppServices.current.audioSettings
    var enabled by remember { mutableStateOf(audio.autoplayRadio()) }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(Res.string.settings_autoplay_radio),
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
                    stringResource(Res.string.settings_autoplay_radio_enabled),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        audio.setAutoplayRadio(on)
                    },
                    colors = RustifySwitch.colors()
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.settings_autoplay_radio_desc),
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Loudness normalisation (4.2): loud recordings turned down to YouTube's reference, so consecutive
 * songs from different uploads sound equally loud. On by default; local files are never touched.
 */
@Composable
fun NormalizeVolumeSection() {
    val prefs = LocalAppServices.current.audioSettings.prefs
    var enabled by remember { mutableStateOf(prefs.getBoolean(EffectsConfig.KEY_NORMALIZE, true)) }
    // Its own heading: under "When a queue ends" it read as something that happens at the end.
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(Res.string.settings_sound),
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
                    stringResource(Res.string.settings_normalize_volume),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        prefs.putBoolean(EffectsConfig.KEY_NORMALIZE, on)
                    },
                    colors = RustifySwitch.colors()
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(Res.string.settings_normalize_volume_desc), color = Color.Gray, fontSize = 12.sp)

            // ReplayGain for local files (4.10): the tags the user's tagger wrote.
            var rgMode by remember {
                mutableStateOf(EffectsConfig.fromSettings(prefs).level.replayGain)
            }
            var preamp by remember { mutableStateOf(prefs.getInt(EffectsConfig.KEY_REPLAYGAIN_PREAMP, 4)) }
            val chipColors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFF1DB954), selectedLabelColor = Color.White
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.settings_replaygain), color = Color.White, fontSize = 15.sp)
            Text(stringResource(Res.string.settings_replaygain_desc), color = Color.Gray, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(
                    ReplayGainMode.OFF to Res.string.settings_replaygain_off,
                    ReplayGainMode.AUTO to Res.string.settings_replaygain_auto,
                    ReplayGainMode.TRACK to Res.string.settings_replaygain_track,
                    ReplayGainMode.ALBUM to Res.string.settings_replaygain_album
                ).forEach { (mode, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = rgMode == mode,
                        onClick = { rgMode = mode; prefs.putString(EffectsConfig.KEY_REPLAYGAIN_MODE, mode.name) },
                        label = { Text(stringResource(label)) },
                        colors = chipColors
                    )
                }
            }
            if (rgMode != ReplayGainMode.OFF) {
                Text(stringResource(Res.string.settings_replaygain_preamp), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 2, 4, 6).forEach { db ->
                        androidx.compose.material3.FilterChip(
                            selected = preamp == db,
                            onClick = { preamp = db; prefs.putInt(EffectsConfig.KEY_REPLAYGAIN_PREAMP, db) },
                            label = { Text("+$db dB") },
                            colors = chipColors
                        )
                    }
                }
            }
        }
    }
}

/**
 * The equaliser (4.4): on/off, the presets, and its bands as sliders. Moving a band turns the preset
 * into a custom curve. What the platform offers decides how many bands there are.
 */
@Composable
fun EqualizerSection() {
    val eq = LocalAppServices.current.audioSettings
    var enabled by remember { mutableStateOf(eq.eqEnabled()) }
    var preset by remember { mutableStateOf(eq.eqPreset()) }
    var bands by remember { mutableStateOf(eq.eqBands()) }
    val presets = remember { eq.eqPresets() }

    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.settings_equalizer), color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { on ->
                    enabled = on
                    eq.setEqEnabled(on)
                    bands = eq.eqBands()
                },
                    colors = RustifySwitch.colors()
                )
            }
            if (bands.isEmpty()) {
                Text(stringResource(Res.string.settings_equalizer_unavailable), color = Color.Gray, fontSize = 12.sp)
                return@Column
            }
            if (enabled) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(presets.size) { i ->
                        androidx.compose.material3.FilterChip(
                            selected = preset == i,
                            onClick = { preset = i; eq.setEqPreset(i); bands = eq.eqBands() },
                            label = { Text(presets[i], fontSize = 12.sp) }
                        )
                    }
                }
                bands.forEach { band ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = if (band.centerHz >= 1000) "${band.centerHz / 1000}k" else "${band.centerHz}"
                        Text(label, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(44.dp))
                        Slider(
                            value = band.levelMb.toFloat(),
                            valueRange = band.minMb.toFloat()..band.maxMb.toFloat(),
                            onValueChange = { v ->
                                eq.setEqBand(band.index, v.toInt())
                                preset = -1
                                bands = eq.eqBands()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Text("${band.levelMb / 100} dB", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                    }
                }
            }
        }
    }
}

/** Fading between songs (4.7): the end of one fades out and the start of the next fades in. */
@Composable
fun FadeBetweenSongsSection() {
    val audio = LocalAppServices.current.audioSettings
    var seconds by remember { mutableIntStateOf(audio.fadeSeconds()) }
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (seconds == 0) stringResource(Res.string.settings_fade_off)
                else stringResource(Res.string.settings_fade_seconds, seconds),
                color = Color.White, fontSize = 16.sp
            )
            Slider(
                value = seconds.toFloat(),
                onValueChange = { seconds = it.toInt() },
                onValueChangeFinished = { audio.setFadeSeconds(seconds) },
                valueRange = 0f..EffectsConfig.MAX_FADE_BETWEEN_SECONDS.toFloat(),
                steps = EffectsConfig.MAX_FADE_BETWEEN_SECONDS - 1,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF1DB954), activeTrackColor = Color(0xFF1DB954))
            )
            Text(stringResource(Res.string.settings_fade_desc), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

/** One setting that is a switch with its explanation under it, in its own card. */
@Composable
private fun AudioSwitchCard(title: StringResource, description: StringResource, checked: Boolean, onChange: (Boolean) -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(title),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = checked, onCheckedChange = onChange, colors = RustifySwitch.colors())
            }
            Text(stringResource(description), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

/** What "previous" does well into a song (4.9) — see `player::Action::PreviousTrack` in the core. */
@Composable
fun PreviousButtonSection() {
    val audio = LocalAppServices.current.audioSettings
    var always by remember { mutableStateOf(audio.previousAlways()) }
    AudioSwitchCard(Res.string.settings_previous_always, Res.string.settings_previous_always_desc, always) { on ->
        always = on
        audio.setPreviousAlways(on)
    }
}

/** A short fade on pause and resume (4.10). */
@Composable
fun PauseFadeSection() {
    val prefs = LocalAppServices.current.audioSettings.prefs
    var on by remember { mutableStateOf(prefs.getBoolean(EffectsConfig.KEY_PAUSE_FADE, true)) }
    AudioSwitchCard(Res.string.settings_pause_fade, Res.string.settings_pause_fade_desc, on) { v ->
        on = v
        prefs.putBoolean(EffectsConfig.KEY_PAUSE_FADE, v)
    }
}

/** Skipping silence (`18`). */
@Composable
fun SkipSilenceSection() {
    val audio = LocalAppServices.current.audioSettings
    var on by remember { mutableStateOf(audio.skipSilence()) }
    AudioSwitchCard(Res.string.settings_skip_silence, Res.string.settings_skip_silence_desc, on) { v ->
        on = v
        audio.setSkipSilence(v)
    }
}

/** Shuffle song by song, or album by album (4.10, [AlbumShuffle]). */
@Composable
fun ShuffleSection() {
    val prefs = LocalAppServices.current.audioSettings.prefs
    var byAlbum by remember { mutableStateOf(AlbumShuffle.enabled(prefs)) }
    AudioSwitchCard(Res.string.settings_shuffle_albums, Res.string.settings_shuffle_albums_desc, byAlbum) { on ->
        byAlbum = on
        AlbumShuffle.setEnabled(prefs, on)
    }
}

/**
 * AutoEq per output device (`21`): find the headphones in AutoEq's index, and their correction is
 * applied whenever music goes to this output. One profile per device.
 */
@Composable
fun AutoEqSection() {
    val ae = LocalAppServices.current.audioSettings
    val platform = LocalAppPlatform.current
    var device by remember { mutableStateOf(ae.currentOutput()) }
    var profiles by remember { mutableStateOf(AutoEqProfiles.all(ae.prefs)) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AutoEqEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val failed = stringResource(Res.string.settings_autoeq_failed)
    LaunchedEffect(Unit) {
        ae.refreshOutput()
        device = ae.currentOutput()
    }
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.settings_autoeq), color = Color.White, fontSize = 15.sp)
            Text(stringResource(Res.string.settings_autoeq_desc), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            val d = device
            if (d == null) {
                Text(stringResource(Res.string.settings_autoeq_no_device), color = Color(0xFFFFB74D), fontSize = 13.sp)
            } else {
                val key = d.key
                val current = profiles[key]
                Text(stringResource(Res.string.settings_autoeq_device, d.name), color = Color.White, fontSize = 14.sp)
                if (current != null) {
                    Text(stringResource(Res.string.settings_autoeq_using, current.name, current.source), color = Color(0xFF1DB954), fontSize = 13.sp)
                    androidx.compose.material3.TextButton(onClick = {
                        ae.setAutoEqProfile(key, null); profiles = AutoEqProfiles.all(ae.prefs)
                    }) { Text(stringResource(Res.string.settings_autoeq_remove), color = Color(0xFFCC2200)) }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.settings_autoeq_search)) },
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.material3.TextButton(enabled = query.isNotBlank() && !busy, onClick = {
                    busy = true
                    scope.launch { results = AutoEqProfiles.search(query); busy = false }
                }) { Text(stringResource(Res.string.settings_autoeq_find), color = Color(0xFF1DB954)) }
                results.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            busy = true
                            scope.launch {
                                val p = AutoEqProfiles.download(entry)
                                busy = false
                                if (p == null) platform.toast(failed)
                                else { ae.setAutoEqProfile(key, p); profiles = AutoEqProfiles.all(ae.prefs); results = emptyList(); query = "" }
                            }
                        }.padding(vertical = 6.dp)
                    ) {
                        Column {
                            Text(entry.name, color = Color.White, fontSize = 14.sp)
                            Text(entry.source, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** What the user blocked (`23` §2), to look at and take back. See [Blocklist]. */
@Composable
fun BlocklistSection() {
    val prefs = LocalAppServices.current.audioSettings.prefs
    val bl = Blocklist
    LaunchedEffect(Unit) { bl.current(prefs) }
    val lists by bl.lists.collectAsState()
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.settings_blocklist), color = Color.White, fontSize = 15.sp)
            Text(stringResource(Res.string.settings_blocklist_desc), color = Color.Gray, fontSize = 12.sp)
            if (lists.tracks.isEmpty() && lists.artists.isEmpty()) {
                Text(stringResource(Res.string.settings_blocklist_empty), color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            lists.artists.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.settings_blocklist_artist, a.name), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { bl.toggleArtist(prefs, a.id, a.name) }) {
                        Text(stringResource(Res.string.settings_blocklist_unblock), color = Color(0xFF1DB954))
                    }
                }
            }
            lists.tracks.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t.name, color = Color.White, fontSize = 14.sp)
                        if (t.detail.isNotBlank()) Text(t.detail, color = Color.Gray, fontSize = 12.sp)
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        bl.toggleTrack(prefs, FullTrack(id = t.id, name = t.name))
                    }) {
                        Text(stringResource(Res.string.settings_blocklist_unblock), color = Color(0xFF1DB954))
                    }
                }
            }
        }
    }
}

/** Crossfeed, balance, mono and voice reduction (`22` §7). */
@Composable
fun StereoToolsSection() {
    val st = LocalAppServices.current.audioSettings
    var s by remember { mutableStateOf(st.stereo()) }
    fun update(n: StereoConfig) {
        s = n
        st.setStereo(n)
    }
    @Composable
    fun toggle(label: StringResource, desc: StringResource, on: Boolean, set: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(label), color = Color.White, fontSize = 14.sp)
                Text(stringResource(desc), color = Color.Gray, fontSize = 12.sp)
            }
            Switch(checked = on, onCheckedChange = set, colors = RustifySwitch.colors())
        }
        Spacer(Modifier.height(6.dp))
    }
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.settings_stereo), color = Color.White, fontSize = 15.sp)
            Text(stringResource(Res.string.settings_stereo_desc), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            toggle(Res.string.settings_stereo_crossfeed, Res.string.settings_stereo_crossfeed_desc, s.crossfeed) { update(s.copy(crossfeed = it)) }
            toggle(Res.string.settings_stereo_mono, Res.string.settings_stereo_mono_desc, s.mono) { update(s.copy(mono = it)) }
            toggle(Res.string.settings_stereo_voice, Res.string.settings_stereo_voice_desc, s.voiceReduction) { update(s.copy(voiceReduction = it)) }
            Text(stringResource(Res.string.settings_stereo_balance, s.balance), color = Color.White, fontSize = 14.sp)
            Slider(
                value = s.balance.toFloat(),
                onValueChange = { update(s.copy(balance = (it / 5).toInt() * 5)) },
                valueRange = -100f..100f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF1DB954), activeTrackColor = Color(0xFF1DB954))
            )
        }
    }
}
