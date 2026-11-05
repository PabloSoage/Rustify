// Rustify for Windows, Linux and macOS (docs 24 §2–§5, 25).
//
// Compose Multiplatform on a JVM, over the same core as the APK through the same JNI bridges, with
// the core's own audio engine (`desktop-audio`) instead of Media3. Opt-in from settings.gradle.kts:
//
//   ./gradlew -Prustify.desktop=true :desktopApp:run
//   ./gradlew -Prustify.desktop=true :desktopApp:packageDistributionForCurrentOS
//
// Packages, one host per format (jpackage cannot cross-build): Windows → .msi and .exe, Linux → .deb,
// .rpm and AppImage, macOS → .dmg. Each carries the core and the sign-in window (desktopApp/login)
// built for the host it was made on.

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
            implementation(project(":sharedUi"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            // The player's icons (skip, pause, repeat...): the same Material set the phone draws.
            implementation(libs.compose.material.icons.extended)
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
// The app's versionName ("5.0.0b") as jpackage accepts it ("5.0.0"). Read from app/build.gradle.kts
// rather than repeated here, where it was once written by hand and stayed behind.
val appPackageVersion: String = run {
    val appBuild = rootProject.file("app/build.gradle.kts").readText()
    val name = Regex("""versionName\s*=\s*"([^"]+)"""").find(appBuild)?.groupValues?.get(1)
    name?.let { Regex("""^\d+\.\d+\.\d+""").find(it)?.value }
        ?: error("app/build.gradle.kts has no versionName of the form MAJOR.MINOR.PATCH…")
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
    commandLine(cargoCommand, "build", "--release", "--lib", "--features", "desktop-audio,media-controls,secure-store,window-style")
    inputs.dir(rootProject.file("core_engine/src"))
    inputs.file(rootProject.file("core_engine/Cargo.toml"))
    outputs.file(File(coreTargetDir, "release/$coreLibraryName"))
}

// --- THE SIGN-IN WINDOW, BUILT FOR THIS HOST -----------------------------------------------------
//
// rustify-login (desktopApp/login): the Spotify login page in the system's own webview, in a process
// of its own, answering the `sp_dc` cookie on standard output (DesktopAppShell.SignIn). It ships next
// to the core, in the same per-OS resources. A failed cargo build fails the desktop build; only
// `-Prustify.loginHelper=false` leaves it out, and then the app offers the pasted cookie alone.
// On Linux it builds against libwebkit2gtk-4.1-dev and libgtk-3-dev.

val loginHelperEnabled: Boolean =
    providers.gradleProperty("rustify.loginHelper").map { it.toBoolean() }.getOrElse(true)
val loginHelperName: String = if (hostOs == "windows") "rustify-login.exe" else "rustify-login"
val loginTargetDir: File = layout.buildDirectory.dir("login-target").get().asFile

val buildLoginHelper by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds rustify-login, the Spotify sign-in window over the system's webview"
    enabled = loginHelperEnabled
    workingDir = project.file("login")
    environment("CARGO_TARGET_DIR", loginTargetDir.absolutePath)
    commandLine(cargoCommand, "build", "--release")
    inputs.dir(project.file("login/src"))
    inputs.file(project.file("login/Cargo.toml"))
    outputs.file(File(loginTargetDir, "release/$loginHelperName"))
}

val coreResources = layout.buildDirectory.dir("core-resources")

val copyRustCoreDesktop by tasks.registering(Copy::class) {
    group = "rust"
    dependsOn(buildRustCoreDesktop)
    from(File(coreTargetDir, "release/$coreLibraryName"))
    if (loginHelperEnabled) {
        dependsOn(buildLoginHelper)
        // Executable whatever the copy started from; the app also checks before running it.
        from(File(loginTargetDir, "release/$loginHelperName")) {
            filePermissions { unix("rwxr-xr-x") }
        }
    }
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
            // jpackage wants MAJOR.MINOR.PATCH with numbers only (an MSI refuses "5.0.0b"): the
            // app's versionName without the beta letter, read from the app's build so the desktop's
            // version (and so its update check) moves with every release instead of being pinned.
            packageVersion = appPackageVersion
            description = "Music player over your Spotify library, with the audio from a backend you choose"
            vendor = "Pablo Soage Rodas"
            copyright = "Copyright (C) 2026 Pablo Soage Rodas. GPL-3.0."
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(coreResources)
            // What jlink keeps besides the modules Compose already needs: Preferences backs the
            // settings (PreferencesStore). Check with ./gradlew :desktopApp:suggestRuntimeModules.
            // java.net.http: the covers (ui/RemoteImage.kt) are fetched with the JDK's client.
            modules("java.prefs", "java.net.http")

            windows {
                menuGroup = "Rustify"
                // Fixed for ever: it is how an installed Rustify recognises a newer one as an upgrade.
                upgradeUuid = "2D8650AB-0888-4F10-BFCE-958AA45D56C9"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // The phone's launcher icon (app/src/main/res/playstore-icon.png), in each system's format.
                iconFile.set(project.file("icons/rustify.ico"))
                // rustify:// is not written by the MSI: this DSL has no URL schemes and jpackage only
                // associates file extensions. The app registers it for the user at first run, under
                // HKCU\Software\Classes\rustify (sharedUi's UrlScheme).
            }
            linux {
                packageName = "rustify"
                appCategory = "AudioVideo"
                shortcut = true
                iconFile.set(project.file("icons/rustify.png"))
                // Nor by the .deb/.rpm: jpackage's .desktop template has no MimeType and no %u, and the
                // resource folder that could replace it is cleared by Compose before each package. The
                // app adds its own hidden handler entry at first run (UrlScheme); the AppImage's entry
                // declares the scheme itself (tools/desktop/make-appimage.sh).
            }
            macOS {
                bundleID = "com.varuna.rustify.desktop"
                iconFile.set(project.file("icons/rustify.icns"))
                // Unsigned until there is an Apple developer account (docs 24 §5): Gatekeeper will ask.
                // rustify:// opens this app; the link arrives through Desktop.setOpenURIHandler
                // (sharedUi's DesktopLinks), not as an argument.
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>com.varuna.rustify.desktop</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>rustify</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }
        }
    }
}

// Every task that reads the app's resources needs the core (and, through the same copy, the sign-in
// window) in them first: `run`, the distributable and every package. Matched by name because Compose
// creates them after this script runs.
tasks.matching { it.name == "prepareAppResources" || it.name == "run" || it.name.startsWith("package") || it.name.startsWith("createDistributable") }
    .configureEach { dependsOn(copyRustCoreDesktop) }
