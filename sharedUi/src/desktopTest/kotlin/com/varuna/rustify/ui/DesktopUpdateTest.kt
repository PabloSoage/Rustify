package com.varuna.rustify.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's update check without the network: the version it compares, the package it names
 * (by the release workflow's file names), and the releases it skips.
 */
class DesktopUpdateTest {
    private fun release(tag: String, vararg assets: String, body: String = "notes for $tag") =
        DesktopUpdate.Release(tag = tag, title = "Rustify $tag", body = body, htmlUrl = "https://example.invalid/$tag", assets = assets.toList())

    private fun desktopAssets(version: String) = arrayOf(
        "Rustify-$version-windows-x86_64.msi",
        "Rustify-$version-windows-x86_64-setup.exe",
        "Rustify-$version-windows-x86_64-portable.zip",
        "rustify-$version-linux-x86_64.deb",
        "rustify-$version-linux-x86_64.rpm",
        "Rustify-$version-linux-x86_64.AppImage",
        "rustify-$version-linux-aarch64.deb",
        "Rustify-$version-macos-arm64.dmg",
        "Rustify-$version-macos-x86_64.dmg",
        "app-arm64-v8a-release.apk",
    )

    @Test
    fun versionsAreNumbersWhateverTheTagAddsToThem() {
        assertEquals(listOf(5, 0, 0), DesktopUpdate.parseVersion("v5.0.0b"))
        assertEquals(listOf(2, 11, 4), DesktopUpdate.parseVersion("v2.11.4-beta"))
        assertEquals(listOf(5, 1, 0), DesktopUpdate.parseVersion("5.1"))
        assertEquals(emptyList<Int>(), DesktopUpdate.parseVersion("nightly"))
        assertEquals(emptyList<Int>(), DesktopUpdate.parseVersion(""))
    }

    @Test
    fun newerIsByNumberNotByText() {
        assertTrue(DesktopUpdate.isNewer("v2.12.10-beta", "2.12.9"))
        assertFalse(DesktopUpdate.isNewer("v5.0.0b", "5.0.0"))
        assertFalse(DesktopUpdate.isNewer("v4.9.9", "5.0.0"))
        assertFalse(DesktopUpdate.isNewer("v5.0.1", ""))
    }

    @Test
    fun thisComputerByTheWorkflowsNames() {
        assertEquals("windows-x86_64", DesktopUpdate.platformName("Windows 11", "amd64"))
        assertEquals("linux-x86_64", DesktopUpdate.platformName("Linux", "amd64"))
        assertEquals("linux-aarch64", DesktopUpdate.platformName("Linux", "aarch64"))
        assertEquals("macos-arm64", DesktopUpdate.platformName("Mac OS X", "aarch64"))
        assertEquals("macos-x86_64", DesktopUpdate.platformName("Mac OS X", "x86_64"))
        // Nothing is built for Windows on ARM: no name, so no package to point at.
        assertNull(DesktopUpdate.platformName("Windows 11", "aarch64"))
    }

    @Test
    fun thePackageIsTheOneThisCopyWasInstalledFrom() {
        val assets = desktopAssets("5.1.0").toList()
        assertEquals("Rustify-5.1.0-windows-x86_64.msi", DesktopUpdate.packageFor(assets, "windows-x86_64", listOf(".msi", "-setup.exe")))
        assertEquals("Rustify-5.1.0-windows-x86_64-portable.zip", DesktopUpdate.packageFor(assets, "windows-x86_64", listOf("-portable.zip")))
        assertEquals("rustify-5.1.0-linux-x86_64.rpm", DesktopUpdate.packageFor(assets, "linux-x86_64", listOf(".rpm", ".AppImage")))
        assertEquals("rustify-5.1.0-linux-aarch64.deb", DesktopUpdate.packageFor(assets, "linux-aarch64", listOf(".deb")))
        // linux-aarch64 has no AppImage in this release, and x86_64's is not its to take.
        assertNull(DesktopUpdate.packageFor(assets, "linux-aarch64", listOf(".AppImage")))
        assertEquals("Rustify-5.1.0-macos-arm64.dmg", DesktopUpdate.packageFor(assets, "macos-arm64", listOf(".dmg")))
    }

    @Test
    fun aReleaseWithOnlyTheApksIsNotAnUpdateHere() {
        val releases = listOf(release("v5.0.1b", "app-arm64-v8a-release.apk"), release("v5.0.0b", *desktopAssets("5.0.0")))
        assertNull(DesktopUpdate.pick(releases, "5.0.0", "windows-x86_64", listOf(".msi")))
    }

    @Test
    fun theNewestWithAPackageAndEveryMissedReleasesNotes() {
        val releases = listOf(
            release("v5.0.0b", *desktopAssets("5.0.0")),
            release("v5.2.0b", "app-arm64-v8a-release.apk"),
            release("v5.1.0b", *desktopAssets("5.1.0")),
            release("v5.0.1b", *desktopAssets("5.0.1")),
            // The same release from both endpoints.
            release("v5.1.0b", *desktopAssets("5.1.0")),
        )
        val update = DesktopUpdate.pick(releases, "5.0.0", "linux-x86_64", listOf(".deb"))!!
        assertEquals("v5.1.0b", update.tag)
        assertEquals("rustify-5.1.0-linux-x86_64.deb", update.asset)
        assertEquals("## Rustify v5.1.0b\n\nnotes for v5.1.0b\n\n\n## Rustify v5.0.1b\n\nnotes for v5.0.1b", update.body)
    }

    @Test
    fun oneMissedReleaseIsItsNotesAsTheyAre() {
        val releases = listOf(release("v5.0.1b", *desktopAssets("5.0.1")), release("v5.0.0b", *desktopAssets("5.0.0")))
        val update = DesktopUpdate.pick(releases, "5.0.0", "macos-arm64", listOf(".dmg"))!!
        assertEquals("notes for v5.0.1b", update.body)
    }

    @Test
    fun upToDateIsNothing() {
        val releases = listOf(release("v5.0.0b", *desktopAssets("5.0.0")))
        assertNull(DesktopUpdate.pick(releases, "5.0.0", "windows-x86_64", listOf(".msi")))
    }

    @Test
    fun theNotesAsPlainText() {
        assertEquals(
            "Player\n\nFaster start and better lyrics.",
            DesktopUpdate.plainNotes("<!-- draft -->## Player\n\nFaster **start** and `better` lyrics."),
        )
    }
}
