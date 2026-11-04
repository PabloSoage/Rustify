// shared/src/desktopMain/kotlin/com/varuna/rustify/dj/LocalModelManager.kt
package com.varuna.rustify.dj

import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.Executables
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/** A small instruct model the desktop can download for its own llama-server. Sizes are approximate. */
data class LocalModel(val id: String, val label: String, val fileName: String, val url: String, val sizeBytes: Long)

/** The models offered: small enough for a laptop's memory, good enough to name artists and moods. */
object LocalModelCatalog {
    val MODELS: List<LocalModel> = listOf(
        LocalModel(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            label = "Qwen2.5 1.5B Instruct (Q4_K_M)",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_120_000_000L,
        ),
        LocalModel(
            id = "llama-3.2-3b-instruct-q4_k_m",
            label = "Llama 3.2 3B Instruct (Q4_K_M)",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 2_020_000_000L,
        ),
        LocalModel(
            id = "qwen2.5-3b-instruct-q4_k_m",
            label = "Qwen2.5 3B Instruct (Q4_K_M)",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2_100_000_000L,
        ),
    )

    fun byId(id: String): LocalModel = MODELS.firstOrNull { it.id == id } ?: MODELS.first()
}

/**
 * Rustify's own model server for the DJ on the desktop, for whoever has no Ollama or LM Studio:
 * llama.cpp's official `llama-server` build for this computer ([LlamaCppAssets]) and one model from
 * [LocalModelCatalog], both downloaded under the app's data folder, and the server run as a child
 * process on `127.0.0.1` and a free port, never another interface. It lives as long as the app (or
 * until [stop]).
 *
 * A build that will not start here (a Vulkan build on a system whose driver cannot run it) is
 * remembered as failed and the next one down is fetched, ending at the CPU build.
 */
class LocalModelManager(private val root: File = File(AppDirs.data, "llm")) {

    sealed class Status {
        data object Idle : Status()
        data class Downloading(val what: String, val done: Long, val total: Long) : Status()
        data class Unpacking(val what: String) : Status()
        data class Starting(val what: String) : Status()
        data class Running(val baseUrl: String, val model: LocalModel, val backend: String) : Status() {
            val address: String get() = baseUrl.removePrefix("http://").substringBefore('/')
        }
        data class Failed(val message: String) : Status()
    }

    /** The llama.cpp build on disk: its release, its backend and where its server is. */
    data class InstalledBuild(val tag: String, val backend: String, val server: File)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null

    private val lock = Any()
    private var process: Process? = null
    private var running: Status.Running? = null
    private var hookInstalled = false

    private val runtimeDir = File(root, "runtime")
    private val modelsDir = File(root, "models")
    private val runtimeFile = File(root, "runtime.properties")
    private val logFile = File(root, "llama-server.log")

    fun modelFile(model: LocalModel): File = File(modelsDir, model.fileName)

    fun isDownloaded(model: LocalModel): Boolean = modelFile(model).isFile

    fun runtime(): InstalledBuild? {
        val p = readProperties()
        val server = p.getProperty("server")?.let { File(it) }?.takeIf { it.isFile } ?: return null
        return InstalledBuild(p.getProperty("tag").orEmpty(), p.getProperty("backend").orEmpty(), server)
    }

    /** Whether [start] would only have to start: the build and [model] are both on disk. */
    fun isReady(model: LocalModel): Boolean = runtime() != null && isDownloaded(model)

    /**
     * Downloads what is missing and starts the server with [model], in the background; [status]
     * follows it. Another call while one runs is ignored; [cancel] stops a download.
     */
    fun start(model: LocalModel) {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch {
                runCatching { prepareAndRun(model, download = true) }
            }
        }
    }

    /** Stops a download or a start in progress. */
    fun cancel() {
        synchronized(lock) { job?.cancel() }
        _status.value = Status.Idle
    }

    /**
     * The server's OpenAI base URL with [model] loaded, starting it when what it needs is on disk;
     * null when it is not (this never downloads) or it will not start.
     */
    suspend fun ensureRunning(model: LocalModel): String? {
        current(model)?.let { return it.baseUrl }
        if (!isReady(model)) return null
        // A download or start the user asked for is under way: not waited for here.
        if (synchronized(lock) { job?.isActive == true }) return null
        return runCatching { prepareAndRun(model, download = false) }.getOrNull()?.baseUrl
    }

    /** Stops the server; the app does it on exit. */
    fun stop() {
        synchronized(lock) {
            job?.cancel()
            stopProcess()
        }
        _status.value = Status.Idle
    }

    fun deleteModel(model: LocalModel) {
        if (current(model) != null) stop()
        modelFile(model).delete()
        File(modelsDir, model.fileName + ".part").delete()
        _status.value = Status.Idle
    }

    private fun current(model: LocalModel): Status.Running? = synchronized(lock) {
        running?.takeIf { it.model == model && process?.isAlive == true }
    }

    private suspend fun prepareAndRun(model: LocalModel, download: Boolean): Status.Running = mutex.withLock {
        current(model)?.let { return@withLock it }
        try {
            if (!download && !isReady(model)) throw IOException("not downloaded")
            if (!isDownloaded(model)) fetchModel(model)
            var failed = failedBackends()
            // At most one try per backend: CUDA, Vulkan or Metal, then the CPU build.
            repeat(LlamaCppAssets.Backend.entries.size) {
                val rt = runtime()?.takeIf { it.backend !in failed.map { b -> b.name } }
                    ?: if (download) installRuntime(failed) else throw IOException("no llama.cpp build")
                try {
                    val r = run(rt, model)
                    _status.value = r
                    return@withLock r
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A GPU build that does not start here: the next one down, unless this was the CPU's.
                    val backend = runCatching { LlamaCppAssets.Backend.valueOf(rt.backend) }.getOrNull()
                    if (!download || backend == null || backend == LlamaCppAssets.Backend.CPU) throw e
                    Log.w(TAG, "the ${rt.backend} build did not start, trying the next one", e)
                    failed = failed + backend
                    saveFailed(failed)
                }
            }
            throw IOException("no llama.cpp build started on this computer")
        } catch (e: CancellationException) {
            _status.value = Status.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the local model did not start", e)
            _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    // ── The llama.cpp build ─────────────────────────────────────────────────────────────────────

    private suspend fun installRuntime(skip: Set<LlamaCppAssets.Backend>): InstalledBuild {
        _status.value = Status.Downloading("llama.cpp", 0, 0)
        val json = withContext(Dispatchers.IO) { httpText(LlamaCppAssets.LATEST_RELEASE) }
        val release = LlamaCppAssets.parseRelease(json) ?: throw IOException("GitHub's answer about llama.cpp could not be read")
        val host = withContext(Dispatchers.IO) { HostProbe.current() }
        val choice = LlamaCppAssets.choose(release.assets, host, skip)
            ?: throw IOException("llama.cpp ${release.tag} has no build for ${host.os} ${host.arch}")
        Log.i(TAG, "llama.cpp ${release.tag} for $host: ${choice.asset.name} (${choice.backend})" +
            choice.extra.joinToString("") { " + ${it.name}" })
        val what = "llama.cpp ${release.tag} (${choice.backend.label()})"
        val dir = File(runtimeDir, "${release.tag}-${choice.backend.name.lowercase()}")
        withContext(Dispatchers.IO) {
            dir.deleteRecursively()
            dir.mkdirs()
        }
        val main = fetchArchive(choice.asset, what)
        _status.value = Status.Unpacking(what)
        val server = withContext(Dispatchers.IO) {
            Archives.unpack(main, dir)
            main.delete()
            findServer(dir) ?: throw IOException("${choice.asset.name} has no llama-server")
        }
        // The CUDA runtime's DLLs go beside the server, where Windows looks for them.
        for (extra in choice.extra) {
            val archive = fetchArchive(extra, what)
            _status.value = Status.Unpacking(what)
            withContext(Dispatchers.IO) {
                Archives.unpack(archive, server.parentFile)
                archive.delete()
            }
        }
        withContext(Dispatchers.IO) {
            if (!Executables.windows) server.parentFile.listFiles()?.forEach { if (it.isFile) it.setExecutable(true) }
            // Only the build just installed is kept.
            runtimeDir.listFiles()?.filter { it.isDirectory && it != dir }?.forEach { it.deleteRecursively() }
            writeProperties(readProperties().apply {
                setProperty("tag", release.tag)
                setProperty("backend", choice.backend.name)
                setProperty("server", server.absolutePath)
            })
        }
        return InstalledBuild(release.tag, choice.backend.name, server)
    }

    private suspend fun fetchArchive(asset: LlamaCppAssets.Asset, what: String): File {
        val file = File(runtimeDir, asset.name)
        download(asset.url, file, what, asset.size)
        val expected = asset.sha256
        if (expected != null) {
            val actual = withContext(Dispatchers.IO) { sha256(file) }
            if (!actual.equals(expected, ignoreCase = true)) {
                file.delete()
                throw IOException("${asset.name} is not the file GitHub published (SHA-256 differs)")
            }
        }
        return file
    }

    private fun findServer(dir: File): File? =
        dir.walkTopDown().firstOrNull { it.isFile && (it.name == "llama-server" || it.name == "llama-server.exe") }

    private fun failedBackends(): Set<LlamaCppAssets.Backend> =
        readProperties().getProperty("failed").orEmpty().split(',')
            .mapNotNull { name -> runCatching { LlamaCppAssets.Backend.valueOf(name.trim()) }.getOrNull() }.toSet()

    private fun saveFailed(failed: Set<LlamaCppAssets.Backend>) =
        writeProperties(readProperties().apply { setProperty("failed", failed.joinToString(",") { it.name }) })

    private fun readProperties(): Properties = Properties().apply {
        if (runtimeFile.isFile) runCatching { runtimeFile.inputStream().use { load(it) } }
    }

    private fun writeProperties(p: Properties) {
        root.mkdirs()
        runtimeFile.outputStream().use { p.store(it, "llama.cpp build for the DJ") }
    }

    // ── The model ───────────────────────────────────────────────────────────────────────────────

    private suspend fun fetchModel(model: LocalModel) {
        val file = modelFile(model)
        download(model.url, file, model.label, model.sizeBytes)
        // A GGUF file starts with its name; an error page saved under the model's name does not.
        val magic = withContext(Dispatchers.IO) { file.inputStream().use { input -> ByteArray(4).also { input.read(it) } } }
        if (String(magic, Charsets.US_ASCII) != "GGUF") {
            file.delete()
            throw IOException("${model.fileName} is not a GGUF model")
        }
    }

    // ── Running it ──────────────────────────────────────────────────────────────────────────────

    private suspend fun run(rt: InstalledBuild, model: LocalModel): Status.Running {
        val what = model.label
        _status.value = Status.Starting(what)
        synchronized(lock) { stopProcess() }
        val host = LocalLlmDetector.HOST
        val port = withContext(Dispatchers.IO) {
            ServerSocket(0, 1, InetAddress.getByName(host)).use { it.localPort }
        }
        // Only on loopback; -ngl 99 puts every layer on the GPU a GPU build finds (a CPU build
        // ignores it); a 4096-token context is plenty for the DJ's prompt and keeps memory small.
        val command = listOf(
            rt.server.absolutePath,
            "-m", modelFile(model).absolutePath,
            "--host", host,
            "--port", port.toString(),
            "-ngl", "99",
            "-c", "4096",
        )
        val p = withContext(Dispatchers.IO) {
            root.mkdirs()
            val builder = ProcessBuilder(command)
                .directory(rt.server.parentFile)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
            // Its shared libraries are beside it.
            val libs = rt.server.parentFile.absolutePath
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val libVar = when {
                os.contains("mac") -> "DYLD_LIBRARY_PATH"
                os.contains("win") -> null
                else -> "LD_LIBRARY_PATH"
            }
            if (libVar != null) {
                val env = builder.environment()
                env[libVar] = listOfNotNull(libs, env[libVar]?.takeIf { it.isNotBlank() }).joinToString(File.pathSeparator)
            }
            builder.start()
        }
        Log.i(TAG, "llama-server (${rt.backend}) with ${model.fileName} on $host:$port")
        synchronized(lock) {
            process = p
            if (!hookInstalled) {
                hookInstalled = true
                java.lang.Runtime.getRuntime().addShutdownHook(Thread { synchronized(lock) { stopProcess() } })
            }
        }
        // Loading a model takes from seconds to a minute or two on a slow disk; /health answers 200
        // once it is ready (503 while loading).
        val deadline = System.currentTimeMillis() + 180_000
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) throw IOException("llama-server stopped: ${lastLines(logFile)}")
                if (withContext(Dispatchers.IO) { LocalLlmDetector.get("http://$host:$port/health", 500, 2_000) } != null) {
                    val r = Status.Running("http://$host:$port/v1", model, rt.backend)
                    synchronized(lock) { running = r }
                    watch(p)
                    return r
                }
                delay(500)
            }
            throw IOException("llama-server was not ready after three minutes")
        } catch (e: Throwable) {
            synchronized(lock) { stopProcess() }
            throw e
        }
    }

    // A server that ends by itself (out of memory, a crash) is said, not left showing as running.
    private fun watch(p: Process) {
        scope.launch {
            runCatching { p.waitFor() }
            val died = synchronized(lock) {
                if (process === p) {
                    process = null
                    running = null
                    true
                } else false
            }
            if (died) {
                Log.w(TAG, "llama-server ended by itself: ${lastLines(logFile)}")
                _status.value = Status.Failed("llama-server stopped: ${lastLines(logFile)}")
            }
        }
    }

    private fun stopProcess() {
        val p = process ?: return
        process = null
        running = null
        p.destroy()
        if (!runCatching { p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)) p.destroyForcibly()
    }

    private fun lastLines(file: File): String =
        runCatching { file.readLines().takeLast(4).joinToString(" / ").take(400) }.getOrDefault("")

    // ── Downloading ─────────────────────────────────────────────────────────────────────────────

    /**
     * [url] to [dest], through a `.part` file that a later try resumes; [status] shows the bytes.
     * Cancelling the coroutine stops it (the part stays, for next time).
     */
    private suspend fun download(url: String, dest: File, what: String, expected: Long) = withContext(Dispatchers.IO) {
        dest.parentFile.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var have = if (part.isFile) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code == 416 && have > 0) {
                // Already all there.
                if (!part.renameTo(dest)) throw IOException("could not move $what into place")
                return@withContext
            }
            if (code !in 200..299) throw IOException("HTTP $code downloading $what")
            val append = code == 206 && have > 0
            if (!append) have = 0
            val length = conn.contentLengthLong
            val total = if (length > 0) have + length else expected
            _status.value = Status.Downloading(what, have, total)
            FileOutputStream(part, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var shown = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        val now = System.currentTimeMillis()
                        if (now - shown > 200) {
                            _status.value = Status.Downloading(what, have, total)
                            shown = now
                        }
                    }
                }
            }
            if (length > 0 && have < total) throw IOException("the download of $what was cut short")
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("could not move $what into place")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from $url")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun LlamaCppAssets.Backend.label(): String = when (this) {
        LlamaCppAssets.Backend.CUDA -> "CUDA"
        LlamaCppAssets.Backend.VULKAN -> "Vulkan"
        LlamaCppAssets.Backend.METAL -> "Metal"
        LlamaCppAssets.Backend.CPU -> "CPU"
    }

    private companion object {
        const val TAG = "LocalModel"
        const val USER_AGENT = "Rustify-desktop"
    }
}

/** What [LlamaCppAssets.choose] needs to know about this computer. */
internal object HostProbe {
    // Where distributions put the Vulkan loader.
    private val LINUX_VULKAN = listOf(
        "/usr/lib/x86_64-linux-gnu/libvulkan.so.1",
        "/usr/lib/aarch64-linux-gnu/libvulkan.so.1",
        "/usr/lib64/libvulkan.so.1",
        "/usr/lib/libvulkan.so.1",
    )

    fun current(): LlamaCppAssets.Host {
        val name = System.getProperty("os.name").orEmpty().lowercase()
        val os = when {
            name.contains("win") -> LlamaCppAssets.Os.WINDOWS
            name.contains("mac") -> LlamaCppAssets.Os.MACOS
            else -> LlamaCppAssets.Os.LINUX
        }
        val jvmArch = System.getProperty("os.arch").orEmpty().lowercase()
        var arch = if (jvmArch == "aarch64" || jvmArch == "arm64") LlamaCppAssets.Arch.ARM64 else LlamaCppAssets.Arch.X64
        // An x64 Java under Rosetta on Apple Silicon: the machine is arm64, and Metal is there.
        if (os == LlamaCppAssets.Os.MACOS && arch == LlamaCppAssets.Arch.X64) {
            val sysctl = File("/usr/sbin/sysctl").takeIf { it.isFile }?.path ?: Executables.onPath("sysctl")
            val translated = sysctl?.let { Executables.run(listOf(it, "-n", "sysctl.proc_translated"), 5) }?.second?.trim()
            if (translated == "1") arch = LlamaCppAssets.Arch.ARM64
        }
        // nvidia-smi comes with NVIDIA's driver, on Windows and Linux alike.
        val smi = if (os == LlamaCppAssets.Os.MACOS) null else Executables.onPath("nvidia-smi")
        val smiOutput = smi?.let { Executables.run(listOf(it), 10) }?.takeIf { it.first == 0 }?.second
        val vulkan = when (os) {
            LlamaCppAssets.Os.WINDOWS -> File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\vulkan-1.dll").isFile
            LlamaCppAssets.Os.LINUX -> LINUX_VULKAN.any { File(it).exists() }
            LlamaCppAssets.Os.MACOS -> false
        }
        return LlamaCppAssets.Host(
            os = os,
            arch = arch,
            nvidia = smiOutput != null,
            cuda = smiOutput?.let { LlamaCppAssets.parseNvidiaSmi(it) },
            vulkan = vulkan,
        )
    }
}

/** Unpacks the build's archive with the system's own tools where they keep links, else in Java. */
internal object Archives {
    fun unpack(archive: File, into: File) {
        into.mkdirs()
        val name = archive.name.lowercase()
        when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> {
                val tar = Executables.onPath("tar") ?: throw IOException("no tar to unpack ${archive.name}")
                val r = Executables.run(listOf(tar, "-xzf", archive.absolutePath, "-C", into.absolutePath), 300)
                if (r == null || r.first != 0) throw IOException("tar could not unpack ${archive.name}: ${r?.second?.take(200).orEmpty()}")
            }
            name.endsWith(".zip") -> {
                if (!Executables.windows && unzipWithTool(archive, into)) return
                unzipInJava(archive, into)
                if (!Executables.windows) repairLinks(into)
            }
            else -> throw IOException("${archive.name} is not an archive this knows")
        }
    }

    // unzip, and macOS' ditto, restore the symbolic links between a build's shared libraries.
    private fun unzipWithTool(archive: File, into: File): Boolean {
        Executables.onPath("unzip")?.let { unzip ->
            if (Executables.run(listOf(unzip, "-q", "-o", archive.absolutePath, "-d", into.absolutePath), 300)?.first == 0) return true
        }
        Executables.onPath("ditto")?.let { ditto ->
            if (Executables.run(listOf(ditto, "-x", "-k", archive.absolutePath, into.absolutePath), 300)?.first == 0) return true
        }
        return false
    }

    private fun unzipInJava(archive: File, into: File) {
        val base = into.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(base, entry.name).canonicalFile
                // Nothing outside the folder ("zip slip").
                if (out != base && !out.path.startsWith(base.path + File.separator)) {
                    throw IOException("${archive.name} has an entry outside its folder: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    // Read without its Unix attributes, a link in a zip comes out as a small file holding the name of
    // the file it points to (libggml.so → libggml.so.0): made a link again.
    private fun repairLinks(dir: File) {
        dir.walkTopDown().filter { it.isFile && it.length() in 1L..255L }.toList().forEach { f ->
            val target = runCatching { f.readText().trim() }.getOrNull() ?: return@forEach
            if (target.isEmpty() || target.any { it == '\n' || it == '/' || it == '\\' }) return@forEach
            val pointed = File(f.parentFile, target)
            if (!pointed.isFile || pointed == f) return@forEach
            f.delete()
            runCatching { Files.createSymbolicLink(f.toPath(), Paths.get(target)) }
                .onFailure { pointed.copyTo(f, overwrite = true) }
        }
    }
}
