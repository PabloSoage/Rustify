package com.varuna.rustify.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.R
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.player.AudioOffload
import com.varuna.rustify.player.AudioPlayerService
import com.varuna.rustify.player.AudioTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val BANDS = 64
private const val HISTORY = 160
private const val FLOOR_DB = -90f

/**
 * The audio lab (4.7.1), under Settings: the waveform the player is sending to the speaker, its
 * spectrum (FFT in the Rust core) and a spectrogram.
 *
 * The FFT is where the CPU's newer ARM extensions earn their place: the core carries the analysis
 * compiled for armv8.0, armv8.2 and armv9 (SVE2) and picks the best one this CPU reports at runtime.
 * The screen shows which ones the CPU has, lets you switch path live, and measures each.
 *
 * While it is open audio offload is paused — with offload the DSP decodes and the app never sees
 * the PCM — and it comes back on leaving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioLabScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val green = Color(0xFF1DB954)

    val info = remember { runCatching { JSONObject(NativeEngine.dspInfoNative()) }.getOrNull() }
    val features = remember { info?.optJSONArray("features")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty() }
    val kernels = remember {
        info?.optJSONArray("kernels")?.let { a -> List(a.length()) { a.getJSONObject(it).let { k -> k.getString("id") to k.getString("name") } } }.orEmpty()
    }
    val best = remember { info?.optString("best").orEmpty() }
    // Vector widths, when the CPU has the extension: SVE's, and the SME unit's in streaming mode.
    val sveBits = remember { info?.takeIf { it.has("sve_bits") && !it.isNull("sve_bits") }?.optInt("sve_bits") }
    val smeBits = remember { info?.takeIf { it.has("sme_bits") && !it.isNull("sme_bits") }?.optInt("sme_bits") }

    var kernel by remember { mutableStateOf("") } // "" = best
    var fftSize by remember { mutableIntStateOf(4096) }
    var wave by remember { mutableStateOf(FloatArray(0)) }
    var bands by remember { mutableStateOf(FloatArray(BANDS) { FLOOR_DB }) }
    var peakHold by remember { mutableStateOf(FloatArray(BANDS) { FLOOR_DB }) }
    var frameUs by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var benchRows by remember { mutableStateOf<List<Triple<String, String, Long>>>(emptyList()) }
    var benching by remember { mutableStateOf(false) }
    val spectrogram = remember { Bitmap.createBitmap(HISTORY, BANDS, Bitmap.Config.ARGB_8888) }
    var tick by remember { mutableIntStateOf(0) }

    // While the screen is visible, not while it exists: leaving the app with the lab open must give
    // offload back, or the battery pays for a screen nobody is looking at.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        fun open(on: Boolean) {
            AudioOffload.labOpen = on
            AudioTap.listening = on
            AudioPlayerService.instance?.applyAudioOffload()
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> open(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> open(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) open(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            open(false)
        }
    }

    LaunchedEffect(kernel, fftSize) {
        var avgUs = 0f
        val column = IntArray(BANDS)
        while (isActive) {
            val rate = AudioTap.sampleRate
            if (rate > 0) {
                val samples = AudioTap.latest(fftSize)
                val (out, us) = withContext(Dispatchers.Default) {
                    val t = System.nanoTime()
                    val r = NativeEngine.dspAnalyzeNative(samples, rate, BANDS, kernel)
                    r to (System.nanoTime() - t) / 1000f
                }
                if (out != null && out.size == BANDS + 2) {
                    avgUs = if (avgUs == 0f) us else avgUs * 0.9f + us * 0.1f
                    frameUs = avgUs
                    // Bars fall smoothly instead of flickering; peaks hold and sink slowly.
                    val prev = bands
                    bands = FloatArray(BANDS) { i -> maxOf(out[i].coerceAtLeast(FLOOR_DB), prev[i] - 2.5f) }
                    val ph = peakHold
                    peakHold = FloatArray(BANDS) { i -> maxOf(bands[i], ph[i] - 0.6f) }
                    level = out[BANDS + 1]
                    wave = samples.copyOfRange(samples.size - minOf(1024, samples.size), samples.size)
                    // Spectrogram: shift left one column, newest on the right.
                    for (b in 0 until BANDS) column[BANDS - 1 - b] = heat((out[b] - FLOOR_DB) / -FLOOR_DB)
                    val px = IntArray(HISTORY * BANDS)
                    spectrogram.getPixels(px, 0, HISTORY, 0, 0, HISTORY, BANDS)
                    for (y in 0 until BANDS) {
                        System.arraycopy(px, y * HISTORY + 1, px, y * HISTORY, HISTORY - 1)
                        px[y * HISTORY + HISTORY - 1] = column[y]
                    }
                    spectrogram.setPixels(px, 0, HISTORY, 0, 0, HISTORY, BANDS)
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
                title = { Text(stringResource(R.string.audio_lab_title), color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back), tint = Color.White)
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
            Text(stringResource(R.string.audio_lab_offload_note), color = Color.Gray, fontSize = 12.sp)

            // CPU and path.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1E1E)).padding(12.dp)) {
                Text(stringResource(R.string.audio_lab_cpu), color = Color.White, fontSize = 15.sp)
                Text(
                    if (features.isEmpty()) stringResource(R.string.audio_lab_no_arm) else features.joinToString("  "),
                    color = Color.LightGray, fontSize = 12.sp
                )
                if (sveBits != null || smeBits != null) {
                    Text(
                        listOfNotNull(sveBits?.let { "SVE $it bits" }, smeBits?.let { "SME (streaming) $it bits" }).joinToString(" · "),
                        color = Color.LightGray, fontSize = 12.sp
                    )
                }
                Text(stringResource(R.string.audio_lab_auto_measured), color = Color.Gray, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val autoName = kernels.firstOrNull { it.first == best }?.second.orEmpty()
                    FilterChip(selected = kernel.isEmpty(), onClick = { kernel = "" },
                        label = { Text(stringResource(R.string.audio_lab_auto, autoName)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.Black, labelColor = Color.White))
                    kernels.forEach { (id, name) ->
                        FilterChip(selected = kernel == id, onClick = { kernel = id }, label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.Black, labelColor = Color.White))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1024, 2048, 4096, 8192).forEach { n ->
                        FilterChip(selected = fftSize == n, onClick = { fftSize = n }, label = { Text("FFT $n") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.Black, labelColor = Color.White))
                    }
                }
                Text(
                    stringResource(R.string.audio_lab_frame, frameUs, if (AudioTap.sampleRate > 0) AudioTap.sampleRate else 0),
                    color = green, fontSize = 12.sp
                )
            }

            if (AudioTap.sampleRate == 0) {
                Text(stringResource(R.string.audio_lab_play_something), color = Color.Gray, fontSize = 13.sp)
            }

            // Waveform.
            Text(stringResource(R.string.audio_lab_waveform), color = Color.White, fontSize = 14.sp)
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
            Text(stringResource(R.string.audio_lab_peak, if (level > 0f) 20f * kotlin.math.log10(level) else FLOOR_DB), color = Color.Gray, fontSize = 12.sp)

            // Spectrum.
            Text(stringResource(R.string.audio_lab_spectrum), color = Color.White, fontSize = 14.sp)
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
            Text(stringResource(R.string.audio_lab_axis), color = Color.Gray, fontSize = 11.sp)

            // Spectrogram.
            Text(stringResource(R.string.audio_lab_spectrogram), color = Color.White, fontSize = 14.sp)
            Canvas(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                @Suppress("UNUSED_EXPRESSION") tick
                drawImage(
                    spectrogram.asImageBitmap(),
                    srcOffset = IntOffset.Zero, srcSize = IntSize(HISTORY, BANDS),
                    dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Low
                )
            }

            // Benchmark of every path this CPU can run.
            Button(
                onClick = {
                    benching = true
                    scope.launch {
                        benchRows = withContext(Dispatchers.Default) {
                            runCatching {
                                val a = JSONArray(NativeEngine.dspBenchNative(4096, 3000))
                                List(a.length()) { a.getJSONObject(it).let { r -> Triple(r.getString("id"), r.getString("name"), r.getLong("ns")) } }
                            }.getOrDefault(emptyList())
                        }
                        benching = false
                    }
                },
                enabled = !benching,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(if (benching) R.string.audio_lab_measuring else R.string.audio_lab_measure), color = Color.White) }
            if (benchRows.isNotEmpty()) {
                val base = benchRows.first().third.coerceAtLeast(1)
                benchRows.forEach { (_, name, ns) ->
                    Text(
                        stringResource(R.string.audio_lab_bench_row, name, ns / 1000f, base.toFloat() / ns.coerceAtLeast(1)),
                        color = Color.LightGray, fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Dark blue → green → yellow → white, for 0..1. */
private fun heat(f: Float): Int {
    val x = f.coerceIn(0f, 1f)
    val r: Float; val g: Float; val b: Float
    when {
        x < 0.33f -> { val t = x / 0.33f; r = 0.05f; g = 0.1f + 0.5f * t; b = 0.3f + 0.2f * t }
        x < 0.66f -> { val t = (x - 0.33f) / 0.33f; r = 0.05f + 0.9f * t; g = 0.6f + 0.3f * t; b = 0.5f - 0.4f * t }
        else -> { val t = (x - 0.66f) / 0.34f; r = 0.95f + 0.05f * t; g = 0.9f + 0.1f * t; b = 0.1f + 0.9f * t }
    }
    return android.graphics.Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
}
