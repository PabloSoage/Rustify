// Rustify for Windows, Linux and macOS (docs 24 §2–§5, 25).
//
// Compose Multiplatform on a JVM, over the same core as the APK through the same JNI bridges, with
// the core's own audio engine (`desktop-audio`) instead of Media3. Opt-in from settings.gradle.kts:
//
//   ./gradlew -Prustify.desktop=true :desktopApp:run
//   ./gradlew -Prustify.desktop=true :desktopApp:packageDistributionForCurrentOS
//
// Packages, one host per format (jpackage cannot cross-build): Windows → .msi and .exe, Linux → .deb,
// .rpm and AppImage, macOS → .dmg. Each carries the core built for the host it was made on.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            // The player's icons (skip, pause, repeat...): the same Material set the phone draws.
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// --- THE CORE, BUILT FOR THIS HOST ---------------------------------------------------------------
//
// cargo with `desktop-audio` into its own target directory (the Android ABIs keep theirs), then the
// library is copied where Compose puts per-OS resources: `<root>/<os>-<arch>/`. At run time the app
// finds it through the `compose.application.resources.dir` property (desktop/CoreLoader.kt).

val hostOs: String = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("win") -> "windows"
        it.contains("mac") -> "macos"
        else -> "linux"
    }
}
val hostArch: String = System.getProperty("os.arch").lowercase().let {
    if (it == "aarch64" || it == "arm64") "arm64" else "x64"
}
val coreLibraryName: String = when (hostOs) {
    "windows" -> "core_engine.dll"
    "macos" -> "libcore_engine.dylib"
    else -> "libcore_engine.so"
}
val coreTargetDir = rootProject.file("core_engine/target/desktop")
val cargoCommand: String = run {
    val exe = if (hostOs == "windows") "cargo.exe" else "cargo"
    val home = System.getenv("CARGO_HOME") ?: (System.getProperty("user.home") + "/.cargo")
    val installed = file("$home/bin/$exe")
    if (installed.exists()) installed.absolutePath else exe
}

val buildRustCoreDesktop by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds core_engine for this host with the desktop audio engine and media controls"
    workingDir = rootProject.file("core_engine")
    environment("CARGO_TARGET_DIR", coreTargetDir.absolutePath)
    commandLine(cargoCommand, "build", "--release", "--lib", "--features", "desktop-audio,media-controls,secure-store")
    inputs.dir(rootProject.file("core_engine/src"))
    inputs.file(rootProject.file("core_engine/Cargo.toml"))
    outputs.file(File(coreTargetDir, "release/$coreLibraryName"))
}

val coreResources = layout.buildDirectory.dir("core-resources")

val copyRustCoreDesktop by tasks.registering(Copy::class) {
    group = "rust"
    dependsOn(buildRustCoreDesktop)
    from(File(coreTargetDir, "release/$coreLibraryName"))
    into(coreResources.map { it.dir("$hostOs-$hostArch") })
}

compose.desktop {
    application {
        mainClass = "com.varuna.rustify.desktop.MainKt"

        nativeDistributions {
            // Per host, because jpackage only builds for the system it runs on, and because asking a
            // Mac for AppImage too makes packageDmg read packageAppImage's output undeclared (CI).
            when (hostOs) {
                "windows" -> targetFormats(TargetFormat.Msi, TargetFormat.Exe)
                "macos" -> targetFormats(TargetFormat.Dmg)
                else -> targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            }
            packageName = "Rustify"
            // jpackage wants MAJOR.MINOR.PATCH with numbers only (an MSI refuses "5.0.0b"). The
            // app's version name is "5.0.0b"; this is the same release without the beta letter.
            packageVersion = "5.0.0"
            description = "Music player over your Spotify library, with the audio from a backend you choose"
            vendor = "Pablo Soage Rodas"
            copyright = "Copyright (C) 2026 Pablo Soage Rodas. GPL-3.0."
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(coreResources)
            // What jlink keeps besides the modules Compose already needs: Preferences backs the
            // settings (PreferencesStore). Check with ./gradlew :desktopApp:suggestRuntimeModules.
            modules("java.prefs")

            windows {
                menuGroup = "Rustify"
                // Fixed for ever: it is how an installed Rustify recognises a newer one as an upgrade.
                upgradeUuid = "2D8650AB-0888-4F10-BFCE-958AA45D56C9"
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }
            linux {
                packageName = "rustify"
                appCategory = "AudioVideo"
                shortcut = true
            }
            macOS {
                bundleID = "com.varuna.rustify.desktop"
                // Unsigned until there is an Apple developer account (docs 24 §5): Gatekeeper will ask.
            }
        }
    }
}

// Every task that reads the app's resources needs the core in them first: `run`, the distributable
// and every package. Matched by name because Compose creates them after this script runs.
tasks.matching { it.name == "prepareAppResources" || it.name == "run" || it.name.startsWith("package") || it.name.startsWith("createDistributable") }
    .configureEach { dependsOn(copyRustCoreDesktop) }
