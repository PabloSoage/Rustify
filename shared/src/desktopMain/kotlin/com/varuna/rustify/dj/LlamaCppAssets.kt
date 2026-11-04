// shared/src/desktopMain/kotlin/com/varuna/rustify/dj/LlamaCppAssets.kt
package com.varuna.rustify.dj

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Which of llama.cpp's release builds fits this computer. Pure, so the choice is tested against
 * real asset names; the names have changed over the releases (`cuda-cu12.2.0`, `cuda-12.4`, a
 * `.tar.gz` where there was a `.zip`), so the matching reads tokens rather than one pattern, and the
 * caller logs what it chose.
 *
 * The order of preference: CUDA where there is an NVIDIA card (the newest CUDA build its driver
 * runs, with the CUDA runtime that Windows needs beside it), else Vulkan where the system has a
 * Vulkan loader (AMD, Intel, and NVIDIA too), Metal on Apple Silicon (the plain macOS arm64 build),
 * and the CPU build otherwise.
 */
object LlamaCppAssets {
    const val LATEST_RELEASE = "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest"

    enum class Os { WINDOWS, MACOS, LINUX }
    enum class Arch { X64, ARM64 }
    enum class Backend { CUDA, VULKAN, METAL, CPU }

    /** A CUDA version, major.minor; comparable so the newest one the driver runs can be picked. */
    data class CudaVersion(val major: Int, val minor: Int) : Comparable<CudaVersion> {
        override fun compareTo(other: CudaVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor })
        override fun toString() = "$major.$minor"
    }

    /** What the computer has. [cuda] is the newest CUDA its NVIDIA driver runs (null: unknown). */
    data class Host(
        val os: Os,
        val arch: Arch,
        val nvidia: Boolean = false,
        val cuda: CudaVersion? = null,
        val vulkan: Boolean = false,
    )

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String? = null)

    /** A build to install: its archive and, for CUDA on Windows, the CUDA runtime archive too. */
    data class Choice(val asset: Asset, val backend: Backend, val extra: List<Asset> = emptyList())

    data class Release(val tag: String, val assets: List<Asset>)

    /** GitHub's `releases/latest` answer: the tag and each asset's name, URL, size and digest. */
    fun parseRelease(json: String): Release? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        val tag = (root["tag_name"] as? JsonPrimitive)?.content ?: return null
        val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val url = (o["browser_download_url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val size = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L
            // "sha256:…" where GitHub has computed it; older assets have none.
            val digest = (o["digest"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            Asset(name, url, size, digest)
        }
        return Release(tag, assets)
    }

    /**
     * The build for [host] among [assets], skipping the backends in [skip] (one that failed to start
     * here), or null when there is none for this system at all.
     */
    fun choose(assets: List<Asset>, host: Host, skip: Set<Backend> = emptySet()): Choice? {
        val builds = assets.mapNotNull { parse(it) }.filter { it.os == host.os && it.arch == host.arch }
        for (backend in preference(host) - skip) {
            val candidates = builds.filter { it.backend == backend && !it.runtime }
            val choice = when (backend) {
                Backend.CUDA -> chooseCuda(candidates, builds.filter { it.runtime }, host)
                // The one that says "cpu" (where older releases had one per instruction set), else the
                // plainest name.
                else -> candidates.minWithOrNull(compareBy<Build>({ if ("cpu" in it.tokens) 0 else 1 }, { it.asset.name.length }))
                    ?.let { Choice(it.asset, backend) }
            }
            if (choice != null) return choice
        }
        return null
    }

    private fun preference(host: Host): List<Backend> = when (host.os) {
        Os.MACOS -> if (host.arch == Arch.ARM64) listOf(Backend.METAL, Backend.CPU) else listOf(Backend.CPU)
        else -> buildList {
            if (host.nvidia) add(Backend.CUDA)
            if (host.vulkan) add(Backend.VULKAN)
            add(Backend.CPU)
        }
    }

    // The newest CUDA build the driver runs (all of them, oldest first, when it did not say); on
    // Windows only one whose CUDA runtime is published beside it.
    private fun chooseCuda(candidates: List<Build>, runtimes: List<Build>, host: Host): Choice? {
        val ordered = candidates.filter { it.cuda != null }
            .filter { host.cuda == null || it.cuda!! <= host.cuda }
            .sortedWith(compareBy<Build> { it.cuda }.thenBy { it.asset.name.length })
            .let { if (host.cuda == null) it else it.reversed() }
        for (build in ordered) {
            if (host.os != Os.WINDOWS) return Choice(build.asset, Backend.CUDA)
            val runtime = runtimes.firstOrNull { it.cuda == build.cuda } ?: continue
            return Choice(build.asset, Backend.CUDA, listOf(runtime.asset))
        }
        return null
    }

    private class Build(
        val asset: Asset,
        val os: Os,
        val arch: Arch,
        val backend: Backend,
        val cuda: CudaVersion?,
        /** `cudart-llama-bin-…`: the CUDA runtime DLLs, not a build. */
        val runtime: Boolean,
        val tokens: List<String>,
    )

    private val ARCHIVES = listOf(".zip", ".tar.gz", ".tgz")

    // Builds for an accelerator this does not choose (ROCm, SYCL, OpenCL…) or a variant of one it
    // does (KleidiAI): left alone, so the plain build of a backend is the one taken.
    private val OTHER_ACCELERATORS = setOf(
        "hip", "rocm", "radeon", "sycl", "opencl", "adreno", "kompute", "musa", "cann", "openvino", "npu", "kleidiai", "snapdragon",
    )

    private val CUDA_VERSION = Regex("""(?:cuda-?|cu)(\d+)\.(\d+)""")

    private fun parse(asset: Asset): Build? {
        val lower = asset.name.lowercase()
        val ext = ARCHIVES.firstOrNull { lower.endsWith(it) } ?: return null
        val stem = lower.removeSuffix(ext)
        val tokens = stem.split('-')
        val runtime = stem.startsWith("cudart")
        if (!runtime && !stem.startsWith("llama")) return null
        if ("bin" !in tokens) return null
        val os = when {
            tokens.any { it == "win" || it == "windows" } -> Os.WINDOWS
            tokens.any { it == "macos" || it == "mac" || it == "osx" || it == "darwin" } -> Os.MACOS
            tokens.any { it == "ubuntu" || it == "linux" } -> Os.LINUX
            else -> return null
        }
        val arch = when {
            tokens.any { it == "x64" || it == "x86_64" || it == "amd64" } -> Arch.X64
            tokens.any { it == "arm64" || it == "aarch64" } -> Arch.ARM64
            else -> return null
        }
        val cuda = CUDA_VERSION.find(stem)?.let { CudaVersion(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        val backend = when {
            runtime || tokens.any { it == "cuda" } || cuda != null -> Backend.CUDA
            tokens.any { it == "vulkan" } -> Backend.VULKAN
            tokens.any { it in OTHER_ACCELERATORS } -> return null
            os == Os.MACOS && arch == Arch.ARM64 -> Backend.METAL
            else -> Backend.CPU
        }
        return Build(asset, os, arch, backend, cuda, runtime, tokens)
    }

    /** `CUDA Version: 12.8` in `nvidia-smi`'s header: the newest CUDA the installed driver runs. */
    fun parseNvidiaSmi(output: String): CudaVersion? =
        Regex("""CUDA Version:\s*(\d+)\.(\d+)""").find(output)
            ?.let { CudaVersion(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
}
