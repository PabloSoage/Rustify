package com.varuna.rustify.dj

import com.varuna.rustify.dj.LlamaCppAssets.Arch
import com.varuna.rustify.dj.LlamaCppAssets.Backend
import com.varuna.rustify.dj.LlamaCppAssets.CudaVersion
import com.varuna.rustify.dj.LlamaCppAssets.Host
import com.varuna.rustify.dj.LlamaCppAssets.Os
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The build the desktop downloads for its own llama-server, chosen from a release's asset names. The
 * names are those of a llama.cpp release of late 2025, plus the older and the `.tar.gz` forms, since
 * the point of matching by tokens is to survive the next rename.
 */
class LlamaCppAssetsTest {

    private fun assets(vararg names: String) = names.map { LlamaCppAssets.Asset(it, "https://example.invalid/$it", 1L) }

    private val release = assets(
        "cudart-llama-bin-win-cuda-12.4-x64.zip",
        "cudart-llama-bin-win-cuda-13.1-x64.zip",
        "llama-b6890-bin-macos-arm64.zip",
        "llama-b6890-bin-macos-x64.zip",
        "llama-b6890-bin-ubuntu-s390x.zip",
        "llama-b6890-bin-ubuntu-vulkan-x64.zip",
        "llama-b6890-bin-ubuntu-x64.zip",
        "llama-b6890-bin-win-cpu-arm64.zip",
        "llama-b6890-bin-win-cpu-x64.zip",
        "llama-b6890-bin-win-cuda-12.4-x64.zip",
        "llama-b6890-bin-win-cuda-13.1-x64.zip",
        "llama-b6890-bin-win-hip-radeon-x64.zip",
        "llama-b6890-bin-win-opencl-adreno-arm64.zip",
        "llama-b6890-bin-win-sycl-x64.zip",
        "llama-b6890-bin-win-vulkan-x64.zip",
        "llama-b6890-xcframework.zip",
    )

    private fun pick(host: Host, from: List<LlamaCppAssets.Asset> = release, skip: Set<Backend> = emptySet()) =
        LlamaCppAssets.choose(from, host, skip)

    @Test
    fun `an NVIDIA card on Windows gets the newest CUDA its driver runs, with its runtime`() {
        val choice = pick(Host(Os.WINDOWS, Arch.X64, nvidia = true, cuda = CudaVersion(12, 8), vulkan = true))
        assertEquals("llama-b6890-bin-win-cuda-12.4-x64.zip", choice?.asset?.name)
        assertEquals(Backend.CUDA, choice?.backend)
        assertEquals(listOf("cudart-llama-bin-win-cuda-12.4-x64.zip"), choice?.extra?.map { it.name })

        val newer = pick(Host(Os.WINDOWS, Arch.X64, nvidia = true, cuda = CudaVersion(13, 2)))
        assertEquals("llama-b6890-bin-win-cuda-13.1-x64.zip", newer?.asset?.name)
        assertEquals(listOf("cudart-llama-bin-win-cuda-13.1-x64.zip"), newer?.extra?.map { it.name })
    }

    @Test
    fun `a driver that did not say gets the oldest CUDA, which more drivers run`() {
        val choice = pick(Host(Os.WINDOWS, Arch.X64, nvidia = true))
        assertEquals("llama-b6890-bin-win-cuda-12.4-x64.zip", choice?.asset?.name)
    }

    @Test
    fun `CUDA without its runtime on Windows is passed over`() {
        val noRuntime = release.filterNot { it.name.startsWith("cudart") }
        val choice = pick(Host(Os.WINDOWS, Arch.X64, nvidia = true, cuda = CudaVersion(12, 8), vulkan = true), noRuntime)
        assertEquals("llama-b6890-bin-win-vulkan-x64.zip", choice?.asset?.name)
        assertEquals(Backend.VULKAN, choice?.backend)
    }

    @Test
    fun `a driver older than every CUDA build falls to Vulkan`() {
        val choice = pick(Host(Os.WINDOWS, Arch.X64, nvidia = true, cuda = CudaVersion(11, 8), vulkan = true))
        assertEquals(Backend.VULKAN, choice?.backend)
    }

    @Test
    fun `other cards get Vulkan, and no Vulkan loader gets the CPU build`() {
        assertEquals("llama-b6890-bin-win-vulkan-x64.zip", pick(Host(Os.WINDOWS, Arch.X64, vulkan = true))?.asset?.name)
        assertEquals("llama-b6890-bin-win-cpu-x64.zip", pick(Host(Os.WINDOWS, Arch.X64))?.asset?.name)
    }

    @Test
    fun `a release with no CUDA build for Linux gives an NVIDIA card Vulkan`() {
        val choice = pick(Host(Os.LINUX, Arch.X64, nvidia = true, cuda = CudaVersion(12, 8), vulkan = true))
        assertEquals("llama-b6890-bin-ubuntu-vulkan-x64.zip", choice?.asset?.name)
        assertEquals("llama-b6890-bin-ubuntu-x64.zip", pick(Host(Os.LINUX, Arch.X64))?.asset?.name)
    }

    @Test
    fun `Apple Silicon gets the Metal build and an Intel Mac the x64 one`() {
        val arm = pick(Host(Os.MACOS, Arch.ARM64))
        assertEquals("llama-b6890-bin-macos-arm64.zip", arm?.asset?.name)
        assertEquals(Backend.METAL, arm?.backend)
        val intel = pick(Host(Os.MACOS, Arch.X64))
        assertEquals("llama-b6890-bin-macos-x64.zip", intel?.asset?.name)
        assertEquals(Backend.CPU, intel?.backend)
    }

    @Test
    fun `Windows on ARM gets the CPU build, not a phone GPU's`() {
        assertEquals("llama-b6890-bin-win-cpu-arm64.zip", pick(Host(Os.WINDOWS, Arch.ARM64, vulkan = true))?.asset?.name)
    }

    @Test
    fun `a backend that failed to start is skipped`() {
        val choice = pick(Host(Os.LINUX, Arch.X64, vulkan = true), skip = setOf(Backend.VULKAN))
        assertEquals("llama-b6890-bin-ubuntu-x64.zip", choice?.asset?.name)
    }

    @Test
    fun `tar archives and the older CUDA names are read too`() {
        val other = assets(
            "llama-b7100-bin-ubuntu-x64.tar.gz",
            "llama-b7100-bin-ubuntu-vulkan-x64.tar.gz",
            "llama-b7100-bin-macos-arm64.tar.gz",
            "llama-b4000-bin-win-cuda-cu12.4-x64.zip",
            "cudart-llama-bin-win-cu12.4-x64.zip",
        )
        assertEquals("llama-b7100-bin-ubuntu-vulkan-x64.tar.gz", pick(Host(Os.LINUX, Arch.X64, vulkan = true), other)?.asset?.name)
        assertEquals("llama-b7100-bin-macos-arm64.tar.gz", pick(Host(Os.MACOS, Arch.ARM64), other)?.asset?.name)
        val cuda = pick(Host(Os.WINDOWS, Arch.X64, nvidia = true, cuda = CudaVersion(12, 6)), other)
        assertEquals("llama-b4000-bin-win-cuda-cu12.4-x64.zip", cuda?.asset?.name)
        assertEquals(listOf("cudart-llama-bin-win-cu12.4-x64.zip"), cuda?.extra?.map { it.name })
    }

    @Test
    fun `no build for the system is no choice`() {
        assertNull(pick(Host(Os.LINUX, Arch.ARM64)))
        assertNull(pick(Host(Os.WINDOWS, Arch.X64), assets("llama-b6890-xcframework.zip", "README.md")))
    }

    // Release b12110, November 2026.
    private val linuxWithCuda = assets(
        "cudart-llama-b12110-bin-ubuntu-cuda-12.8-x64.tar.gz",
        "cudart-llama-b12110-bin-ubuntu-cuda-13.4-arm64.tar.gz",
        "cudart-llama-b12110-bin-ubuntu-cuda-13.4-x64.tar.gz",
        "llama-b12110-bin-linux-arm64-snapdragon.tar.gz",
        "llama-b12110-bin-ubuntu-26.04-s390x.tar.gz",
        "llama-b12110-bin-ubuntu-arm64.tar.gz",
        "llama-b12110-bin-ubuntu-cuda-12.8-x64.tar.gz",
        "llama-b12110-bin-ubuntu-cuda-13.4-arm64.tar.gz",
        "llama-b12110-bin-ubuntu-cuda-13.4-x64.tar.gz",
        "llama-b12110-bin-ubuntu-openvino-2026.4.1-x64.tar.gz",
        "llama-b12110-bin-ubuntu-rocm-10.0-x64.tar.gz",
        "llama-b12110-bin-ubuntu-sycl-fp16-x64.tar.gz",
        "llama-b12110-bin-ubuntu-vulkan-x64.tar.gz",
        "llama-b12110-bin-ubuntu-x64.tar.gz",
        "llama-b12110-ui.tar.gz",
    )

    @Test
    fun `CUDA on Linux comes with its runtime too`() {
        val choice = pick(Host(Os.LINUX, Arch.X64, nvidia = true, cuda = CudaVersion(13, 5), vulkan = true), linuxWithCuda)
        assertEquals("llama-b12110-bin-ubuntu-cuda-13.4-x64.tar.gz", choice?.asset?.name)
        assertEquals(listOf("cudart-llama-b12110-bin-ubuntu-cuda-13.4-x64.tar.gz"), choice?.extra?.map { it.name })

        val older = pick(Host(Os.LINUX, Arch.X64, nvidia = true, cuda = CudaVersion(13, 0)), linuxWithCuda)
        assertEquals("llama-b12110-bin-ubuntu-cuda-12.8-x64.tar.gz", older?.asset?.name)

        val noRuntime = linuxWithCuda.filterNot { it.name.startsWith("cudart") }
        val vulkan = pick(Host(Os.LINUX, Arch.X64, nvidia = true, cuda = CudaVersion(13, 5), vulkan = true), noRuntime)
        assertEquals(Backend.VULKAN, vulkan?.backend)
    }

    @Test
    fun `nvidia-smi's header says the driver's CUDA`() {
        val header = "| NVIDIA-SMI 570.86.15    Driver Version: 570.86.15    CUDA Version: 12.8     |"
        assertEquals(CudaVersion(12, 8), LlamaCppAssets.parseNvidiaSmi(header))
        val newer = "| NVIDIA-SMI 621.01                 KMD Version: 621.01        CUDA UMD Version: 13.5     |"
        assertEquals(CudaVersion(13, 5), LlamaCppAssets.parseNvidiaSmi(newer))
        assertNull(LlamaCppAssets.parseNvidiaSmi("command not found"))
    }

    @Test
    fun `the release list gives the tags, the assets and their digests`() {
        val json = """[{"tag_name":"b6890","assets":[
            {"name":"llama-b6890-bin-win-cpu-x64.zip","size":20971520,
             "browser_download_url":"https://github.com/ggml-org/llama.cpp/releases/download/b6890/llama-b6890-bin-win-cpu-x64.zip",
             "digest":"sha256:0123abcd"},
            {"name":"llama-b6890-bin-ubuntu-x64.zip","size":1,"browser_download_url":"https://x.invalid/u.zip","digest":null}]}]"""
        val parsed = LlamaCppAssets.parseReleases(json).single()
        assertEquals("b6890", parsed.tag)
        assertEquals(listOf("0123abcd", null), parsed.assets.map { it.sha256 })
        assertEquals(20971520L, parsed.assets.first().size)
        assertEquals(emptyList(), LlamaCppAssets.parseReleases("""{"message":"API rate limit exceeded"}"""))
    }

    @Test
    fun `a numbered release with no builds gives way to the prerelease that has them`() {
        val numbered = LlamaCppAssets.Release("v0.9.0", assets("nightly-tag.txt"))
        val nightly = LlamaCppAssets.Release("b12110", linuxWithCuda)
        val (release, choice) = LlamaCppAssets.newest(listOf(numbered, nightly), Host(Os.LINUX, Arch.X64))!!
        assertEquals("b12110", release.tag)
        assertEquals("llama-b12110-bin-ubuntu-x64.tar.gz", choice.asset.name)
        assertNull(LlamaCppAssets.newest(listOf(numbered), Host(Os.LINUX, Arch.X64)))
    }

    @Test
    fun `a runtime archive's files land beside the server, whatever folder they came in`() {
        val dir = createTempDirectory("archives").toFile()
        try {
            val archive = File(dir, "runtime.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("cudart-llama/libcudart.so.13"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
            val server = File(dir, "server").apply { mkdirs() }
            Archives.unpackFlat(archive, server)
            assertEquals(listOf("libcudart.so.13"), server.list()?.toList())
        } finally {
            dir.deleteRecursively()
        }
    }
}
