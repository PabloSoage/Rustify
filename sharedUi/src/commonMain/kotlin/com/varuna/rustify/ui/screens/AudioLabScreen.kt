package com.varuna.rustify.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.ui.AudioLabBridge
import com.varuna.rustify.ui.SpectrogramImage
import com.varuna.rustify.ui.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.compose.resources.stringResource

private const val BANDS = 64
private const val HISTORY = 160
private const val FLOOR_DB = -90f
private const val MEL = 52
private const val TONAL_FFT = 8192

/**
 * The audio lab (4.7.1), under Settings: the waveform the player is sending to the speaker, its
 * spectrum (FFT in the Rust core) and a spectrogram.
 *
 * The FFT is where the CPU's newer ARM extensions earn their place: the core carries the analysis
 * compiled for armv8.0, armv8.2 and armv9 (SVE2) and picks the best one this CPU reports at runtime.
 * On non-ARM CPUs (Desktop x86_64), it falls back to the scalar Rust engine.
 * The screen shows which ones the CPU has, lets you switch path live if multiple exist, and measures each.
 *
 * Below it, the key of what is playing and a mel spectrogram, both out of one matrix product per 16
 * frames (filterbank × spectra) — the shape SME's outer products and BF16's BFMMLA are for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioLabScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val green = Color(0xFF1DB954)

    val info = remember {
        runCatching {
            Json.parseToJsonElement(NativeEngine.dspInfoNative()).jsonObject
        }.getOrNull()
    }
    val features = remember {
        info?.get("features")?.jsonArray?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
    }
    val kernels = remember {
        info?.get("kernels")?.jsonArray?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            id to name
        }.orEmpty()
    }
    val best = remember { info?.get("best")?.jsonPrimitive?.content.orEmpty() }

    // Vector widths, when the CPU has the extension: SVE's, and the SME unit's in streaming mode.
    val sveBits = remember { info?.get("sve_bits")?.jsonPrimitive?.intOrNull }
    val smeBits = remember { info?.get("sme_bits")?.jsonPrimitive?.intOrNull }

    var kernel by remember { mutableStateOf("") } // "" = best
    var fftSize by remember { mutableIntStateOf(4096) }
    var wave by remember { mutableStateOf(FloatArray(0)) }
    var bands by remember { mutableStateOf(FloatArray(BANDS) { FLOOR_DB }) }
    var peakHold by remember { mutableStateOf(FloatArray(BANDS) { FLOOR_DB }) }
    var frameUs by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var benchRows by remember { mutableStateOf<List<Triple<String, String, Long>>>(emptyList()) }
    var benching by remember { mutableStateOf(false) }
    val spectrogram = remember { SpectrogramImage(HISTORY, BANDS) }
    var tick by remember { mutableIntStateOf(0) }
    val matKernels = remember {
        info?.get("mat_kernels")?.jsonArray?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            id to name
        }.orEmpty()
    }
    val matBest = remember { info?.get("mat_best")?.jsonPrimitive?.content.orEmpty() }
    var keyIndex by remember { mutableIntStateOf(-1) }
    var keyR by remember { mutableFloatStateOf(0f) }
    var chroma by remember { mutableStateOf(FloatArray(12)) }
    var melMode by remember { mutableStateOf(false) }
    // Mel columns arrive 16 at a time (one matrix product); they are drawn one per frame from here.
    val melQueue = remember { ArrayDeque<FloatArray>() }
    val melgram = remember { SpectrogramImage(HISTORY, MEL) }
    var matRows by remember { mutableStateOf<List<Triple<String, String, Long>>>(emptyList()) }

    DisposableEffect(Unit) {
        AudioLabBridge.onVisibilityChanged(true)
        onDispose {
            AudioLabBridge.onVisibilityChanged(false)
        }
    }

    LaunchedEffect(kernel, fftSize) {
        var avgUs = 0f
        val column = IntArray(BANDS)
        val melCol = IntArray(MEL)
        var resetTonal = true
        while (isActive) {
            val rate = AudioLabBridge.sampleRate()
            if (rate > 0) {
                val samples = AudioLabBridge.latestSamples(fftSize)
                val tonalSamples = AudioLabBridge.latestSamples(TONAL_FFT)
                val (dspOut, us, tonal) = withContext(Dispatchers.Default) {
                    val mark = kotlin.time.TimeSource.Monotonic.markNow()
                    val r = NativeEngine.dspAnalyzeNative(samples, rate, BANDS, kernel)
                    val elapsedUs = mark.elapsedNow().inWholeNanoseconds / 1000f
                    Triple(r, elapsedUs, NativeEngine.dspTonalNative(tonalSamples, rate, resetTonal))
                }
                resetTonal = false
                if (tonal != null && tonal.size >= 15) {
                    val cols = tonal[0].toInt()
                    keyIndex = tonal[1].toInt()
                    keyR = tonal[2]
                    chroma = tonal.copyOfRange(3, 15)
                    for (c in 0 until cols) {
                        val at = 15 + c * MEL
                        if (tonal.size >= at + MEL) melQueue.addLast(tonal.copyOfRange(at, at + MEL))
                    }
                    // Never let the queue lag more than a batch behind.
                    while (melQueue.size > 32) melQueue.removeFirst()
                }
                melQueue.removeFirstOrNull()?.let { mel ->
                    for (y in 0 until MEL) {
                        melCol[y] = heat((mel[MEL - 1 - y] - FLOOR_DB) / -FLOOR_DB)
                    }
                    melgram.shiftAndSetColumn(melCol)
                }
                if (dspOut != null && dspOut.size == BANDS + 2) {
                    avgUs = if (avgUs == 0f) us else avgUs * 0.9f + us * 0.1f
                    frameUs = avgUs
                    // Bars fall smoothly instead of flickering; peaks hold and sink slowly.
                    val prev = bands
                    bands = FloatArray(BANDS) { i -> maxOf(dspOut[i].coerceAtLeast(FLOOR_DB), prev[i] - 2.5f) }
                    val ph = peakHold
                    peakHold = FloatArray(BANDS) { i -> maxOf(bands[i], ph[i] - 0.6f) }
                    level = dspOut[BANDS + 1]
                    wave = samples.copyOfRange(samples.size - minOf(1024, samples.size), samples.size)
                    // Spectrogram: shift left one column, newest on the right.
                    for (b in 0 until BANDS) column[b] = heat((dspOut[BANDS - 1 - b] - FLOOR_DB) / -FLOOR_DB)
                    spectrogram.shiftAndSetColumn(column)
                    tick++
                }
            }
            delay(33)
        }
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(Res.string.audio_lab_title), color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.settings_back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(Res.string.audio_lab_offload_note), color = Color.Gray, fontSize = 12.sp)

            // CPU and path.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1E1E)).padding(12.dp)) {
                Text(stringResource(Res.string.audio_lab_cpu), color = Color.White, fontSize = 15.sp)
                Text(
                    if (features.isEmpty()) stringResource(Res.string.audio_lab_no_arm) else features.joinToString("  "),
                    color = Color.LightGray, fontSize = 12.sp
                )
                if (sveBits != null || smeBits != null) {
                    Text(
                        listOfNotNull(sveBits?.let { "SVE $it bits" }, smeBits?.let { "SME (streaming) $it bits" }).joinToString(" · "),
                        color = Color.LightGray, fontSize = 12.sp
                    )
                }
                Text(stringResource(Res.string.audio_lab_auto_measured), color = Color.Gray, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                // On ARM show live kernel switcher chips. On x86 / single kernel, hide switching row.
                if (kernels.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val autoName = kernels.firstOrNull { it.first == best }?.second.orEmpty()
                        FilterChip(
                            selected = kernel.isEmpty(), onClick = { kernel = "" },
                            label = { Text(stringResource(Res.string.audio_lab_auto, autoName)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.Black, labelColor = Color.White)
                        )
                        kernels.forEach { (id, name) ->
                            FilterChip(
                                selected = kernel == id, onClick = { kernel = id }, label = { Text(name) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.Black, labelColor = Color.White)
                            )
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1024, 2048, 4096, 8192).forEach { n ->
                        FilterChip(
                            selected = fftSize == n, onClick = { fftSize = n }, label = { Text("FFT $n") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.Black, labelColor = Color.White)
                        )
                    }
                }
                val currentRate = AudioLabBridge.sampleRate()
                Text(
                    stringResource(Res.string.audio_lab_frame, frameUs, if (currentRate > 0) currentRate else 0),
                    color = green, fontSize = 12.sp
                )
            }

            if (AudioLabBridge.sampleRate() == 0) {
                Text(stringResource(Res.string.audio_lab_play_something), color = Color.Gray, fontSize = 13.sp)
            }

            // Waveform.
            Text(stringResource(Res.string.audio_lab_waveform), color = Color.White, fontSize = 14.sp)
            Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0A0A))) {
                @Suppress("UNUSED_EXPRESSION") tick
                val w = wave
                val mid = size.height / 2
                drawLine(Color(0xFF333333), Offset(0f, mid), Offset(size.width, mid))
                if (w.size > 1) {
                    val path = Path()
                    w.forEachIndexed { i, s ->
                        val x = i * size.width / (w.size - 1)
                        val y = mid - s.coerceIn(-1f, 1f) * mid * 0.95f
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, green, style = Stroke(width = 2f))
                }
            }
            Text(
                stringResource(Res.string.audio_lab_peak, if (level > 0f) 20f * kotlin.math.log10(level) else FLOOR_DB),
                color = Color.Gray, fontSize = 12.sp
            )

            // Spectrum.
            Text(stringResource(Res.string.audio_lab_spectrum), color = Color.White, fontSize = 14.sp)
            Canvas(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0A0A))) {
                val b = bands
                val ph = peakHold
                val barW = size.width / BANDS
                for (i in 0 until BANDS) {
                    val f = ((b[i] - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
                    val h = f * size.height
                    drawRect(Color(heat(f)), topLeft = Offset(i * barW + 1f, size.height - h), size = Size(barW - 2f, h))
                    val p = ((ph[i] - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f) * size.height
                    drawRect(Color.White, topLeft = Offset(i * barW + 1f, size.height - p - 2f), size = Size(barW - 2f, 2f))
                }
            }
            Text(stringResource(Res.string.audio_lab_axis), color = Color.Gray, fontSize = 11.sp)

            // Spectrogram.
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.audio_lab_spectrogram), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = !melMode, onClick = { melMode = false }, label = { Text(stringResource(Res.string.audio_lab_spec_log)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.Black, labelColor = Color.White)
                )
                FilterChip(
                    selected = melMode, onClick = { melMode = true }, label = { Text(stringResource(Res.string.audio_lab_spec_mel)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.Black, labelColor = Color.White)
                )
            }
            Canvas(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                @Suppress("UNUSED_EXPRESSION") tick
                drawImage(
                    (if (melMode) melgram else spectrogram).asImageBitmap(),
                    srcOffset = IntOffset.Zero, srcSize = IntSize(HISTORY, if (melMode) MEL else BANDS),
                    dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Low
                )
            }

            // Key, from the chroma of the last seconds.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1E1E)).padding(12.dp)) {
                val notes = stringResource(Res.string.audio_lab_notes).split(' ')
                val keyKernelName = matKernels.firstOrNull { it.first == matBest }?.second.orEmpty()
                Text(stringResource(Res.string.audio_lab_key_title), color = Color.White, fontSize = 15.sp)
                val keyText = when {
                    keyIndex in 0..11 -> stringResource(Res.string.audio_lab_key_major, notes[keyIndex])
                    keyIndex in 12..23 -> stringResource(Res.string.audio_lab_key_minor, notes[keyIndex - 12])
                    else -> stringResource(Res.string.audio_lab_key_listening)
                } + if (keyIndex >= 0) "  ·  r = " + ((keyR * 100).toInt() / 100f) else ""
                Text(keyText, color = green, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                    val c = chroma
                    val barW = size.width / 12
                    for (i in 0 until 12) {
                        val h = c[i].coerceIn(0f, 1f) * size.height
                        val tonic = keyIndex >= 0 && i == keyIndex % 12
                        drawRect(if (tonic) green else Color(0xFF3A6B4C), topLeft = Offset(i * barW + 2f, size.height - h), size = Size(barW - 4f, h))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    notes.forEach { Text(it, color = Color.Gray, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
                Text(
                    stringResource(Res.string.audio_lab_key_note, keyKernelName),
                    color = Color.Gray, fontSize = 11.sp
                )
            }

            // Benchmark of every path this CPU can run.
            Button(
                onClick = {
                    benching = true
                    scope.launch {
                        benchRows = withContext(Dispatchers.Default) {
                            runCatching {
                                val a = Json.parseToJsonElement(NativeEngine.dspBenchNative(4096, 3000)).jsonArray
                                a.mapNotNull { el ->
                                    val obj = el as? JsonObject ?: return@mapNotNull null
                                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                    val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                    val ns = obj["ns"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                                    Triple(id, name, ns)
                                }
                            }.getOrDefault(emptyList())
                        }
                        matRows = withContext(Dispatchers.Default) {
                            runCatching {
                                val a = Json.parseToJsonElement(NativeEngine.dspMatBenchNative(TONAL_FFT, 200)).jsonArray
                                a.mapNotNull { el ->
                                    val obj = el as? JsonObject ?: return@mapNotNull null
                                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                    val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                    val ns = obj["ns"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                                    Triple(id, name, ns)
                                }
                            }.getOrDefault(emptyList())
                        }
                        benching = false
                    }
                },
                enabled = !benching,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (benching) Res.string.audio_lab_measuring else Res.string.audio_lab_measure), color = Color.White)
            }
            if (benchRows.isNotEmpty()) {
                val base = benchRows.first().third.coerceAtLeast(1)
                benchRows.forEach { (_, name, ns) ->
                    val text = if (benchRows.size > 1) {
                        stringResource(Res.string.audio_lab_bench_row, name, ns / 1000f, base.toFloat() / ns.coerceAtLeast(1))
                    } else {
                        stringResource(Res.string.audio_lab_bench_row_single, name, ns / 1000f)
                    }
                    Text(text, color = Color.LightGray, fontSize = 13.sp)
                }
            }
            if (matRows.isNotEmpty()) {
                val base = matRows.first().third.coerceAtLeast(1)
                matRows.forEach { (_, name, ns) ->
                    val text = if (matRows.size > 1) {
                        stringResource(Res.string.audio_lab_mat_row, name, ns / 1000f, base.toFloat() / ns.coerceAtLeast(1))
                    } else {
                        stringResource(Res.string.audio_lab_mat_row_single, name, ns / 1000f)
                    }
                    Text(text, color = Color.LightGray, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Dark blue → green → yellow → white, for 0..1 (packed ARGB int). */
private fun heat(f: Float): Int {
    val x = f.coerceIn(0f, 1f)
    val r: Float; val g: Float; val b: Float
    when {
        x < 0.33f -> { val t = x / 0.33f; r = 0.05f; g = 0.1f + 0.5f * t; b = 0.3f + 0.2f * t }
        x < 0.66f -> { val t = (x - 0.33f) / 0.33f; r = 0.05f + 0.9f * t; g = 0.6f + 0.3f * t; b = 0.5f - 0.4f * t }
        else -> { val t = (x - 0.66f) / 0.34f; r = 0.95f + 0.05f * t; g = 0.9f + 0.1f * t; b = 0.1f + 0.9f * t }
    }
    val ri = (r * 255).toInt().coerceIn(0, 255)
    val gi = (g * 255).toInt().coerceIn(0, 255)
    val bi = (b * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
}
