package com.varuna.rustify.dj

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The DJ on the phone's own AI hardware (4.7): Gemini Nano through Android's AICore, which runs it
 * on the NPU where the phone has one. No network, no key, nothing leaves the device.
 *
 * It is told what [ApiDjProvider] is told and answers in the same `{intro, seeds, queries}` shape
 * ([DjPrompt]); the [DjEngine] turns that into tracks the same way.
 *
 * Not every phone has it — AICore ships on recent flagships — so it degrades to the heuristic, and
 * says so in the intro, when the model is missing or still downloading. The first time it is asked
 * on a phone that can have it, it starts the download (AICore's, done once for every app).
 */
class LocalDjProvider(
    private val fallback: DjProvider = HeuristicDjProvider()
) : DjProvider {

    override suspend fun plan(context: DjContext, request: String): DjPlan {
        val status = OnDeviceModel.refresh()
        if (status != OnDeviceModel.Status.READY) {
            if (status == OnDeviceModel.Status.DOWNLOADABLE) OnDeviceModel.download()
            val plan = fallback.plan(context, request)
            val why = if (context.language.startsWith("es")) when (status) {
                OnDeviceModel.Status.DOWNLOADABLE, OnDeviceModel.Status.DOWNLOADING -> "el modelo del móvil se está descargando"
                else -> "este móvil no tiene IA en el dispositivo"
            } else when (status) {
                OnDeviceModel.Status.DOWNLOADABLE, OnDeviceModel.Status.DOWNLOADING -> "the on-device model is downloading"
                else -> "this phone has no on-device AI"
            }
            return plan.copy(intro = "${plan.intro} ($why)")
        }
        val prompt = DjPrompt.systemPrompt(context) + "\n\n" + DjPrompt.userPrompt(context, request)
        val started = android.os.SystemClock.elapsedRealtime()
        val answer = runCatching {
            // A small model on a phone: 20 s is generous, and past it the heuristic is the better DJ.
            withTimeout(20_000) { OnDeviceModel.model.generateContent(prompt) }
                .candidates.firstOrNull()?.text.orEmpty()
        }.onFailure { Log.w(TAG, "on-device generation failed", it) }.getOrNull()
        val plan = answer?.let { DjPrompt.parsePlan(it) }
        Log.i(TAG, "on-device answer in ${android.os.SystemClock.elapsedRealtime() - started} ms, " +
            "${answer?.length ?: 0} chars, ${if (plan != null) "parsed" else "not parsed: heuristic instead"}")
        return plan ?: fallback.plan(context, request)
    }

    private companion object { const val TAG = "LocalDj" }
}

/**
 * Gemini Nano's availability on this phone, readable from Settings. One client for the process:
 * AICore keeps the model loaded between calls, which is what makes the second request fast.
 */
object OnDeviceModel {
    enum class Status { UNKNOWN, UNSUPPORTED, DOWNLOADABLE, DOWNLOADING, READY }

    private val _status = MutableStateFlow(Status.UNKNOWN)
    val status: StateFlow<Status> = _status

    /** While downloading: bytes so far and in total (total 0 until AICore says it). */
    data class Progress(val downloaded: Long, val total: Long)
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    /** The model AICore serves, e.g. "gemini-nano-…"; null until it is ready. */
    private val _modelName = MutableStateFlow<String?>(null)
    val modelName: StateFlow<String?> = _modelName

    private const val PREFS = "rustify_settings"
    private const val KEY_SIZE = "dj_nano_size_bytes"

    /**
     * The model's size as AICore last reported it when downloading, or 0 if this install never saw
     * a download. The API has no "how big is it" for a model already on the phone.
     */
    fun knownSizeBytes(context: android.content.Context): Long =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getLong(KEY_SIZE, 0L)

    @Volatile private var appContext: android.content.Context? = null
    fun init(context: android.content.Context) { appContext = context.applicationContext }

    /**
     * Loads the model into memory ahead of the first request: measured on a POCO F8 Ultra, the
     * first answer took 12.5 s and later ones 6.9 s. Called when the DJ screen opens in this mode.
     */
    fun warmup() {
        if (_status.value != Status.READY) return
        scope.launch { runCatching { model.warmup() }.onFailure { Log.w("LocalDj", "warmup failed: ${it.message}") } }
    }

    internal val model: GenerativeModel by lazy { Generation.getClient() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Asks AICore where it stands. Never throws: a phone without AICore is [Status.UNSUPPORTED]. */
    // Off the main thread: the first call builds AICore's client, which reads from disk (StrictMode
    // flagged it from the DJ screen's LaunchedEffect).
    suspend fun refresh(): Status = kotlinx.coroutines.withContext(Dispatchers.IO) { refreshOnIo() }

    private suspend fun refreshOnIo(): Status {
        if (following) return Status.DOWNLOADING
        val s = runCatching {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> Status.READY
                FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
                else -> Status.UNSUPPORTED
            }
        }.onFailure {
            // Said, not swallowed: a release build whose ML Kit could not start (an R8 rule missing,
            // 4.8.1) looked exactly like a phone without AICore.
            Log.w("LocalDj", "AICore status check failed", it)
        }.getOrDefault(Status.UNSUPPORTED)
        _status.value = s
        if (s == Status.READY && _modelName.value == null) {
            _modelName.value = runCatching { model.getBaseModelName() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        // AICore keeps downloading after the app that asked is gone (an update, a kill). Without
        // following it again the status said "downloading" forever (4.7.1, seen on a POCO F8 Ultra:
        // 1.9 GB arrived, the screen never changed).
        if (s == Status.DOWNLOADING) download()
        return s
    }

    @Volatile private var following = false

    /** Starts the model download, or follows one already running; [status] tracks it. */
    fun download() {
        if (following) return
        following = true
        _status.value = Status.DOWNLOADING
        scope.launch {
            runCatching {
                model.download().collect { d ->
                    when (d) {
                        is DownloadStatus.DownloadStarted -> {
                            _progress.value = Progress(0L, d.bytesToDownload)
                            if (d.bytesToDownload > 0) appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                                ?.edit()?.putLong(KEY_SIZE, d.bytesToDownload)?.apply()
                        }
                        is DownloadStatus.DownloadProgress ->
                            _progress.value = Progress(d.totalBytesDownloaded, _progress.value?.total ?: 0L)
                        is DownloadStatus.DownloadCompleted -> { _status.value = Status.READY; _progress.value = null }
                        is DownloadStatus.DownloadFailed -> { _status.value = Status.DOWNLOADABLE; _progress.value = null }
                        else -> {}
                    }
                }
            }.onFailure { _status.value = Status.UNSUPPORTED }
            following = false
            // The flow can end without a terminal event; the model's own word settles it.
            // A pause, so a model stuck "downloading" is asked every 30 s rather than in a tight loop.
            if (_status.value == Status.DOWNLOADING) {
                kotlinx.coroutines.delay(30_000)
                refresh()
            }
        }
    }
}
