import java.util.Properties

// The interface Android, the desktop and iOS share (docs serie-4.x/26): the phone's Compose screens,
// moved here rather than rewritten, over Compose Multiplatform. On Android it resolves to the same
// androidx.compose artifacts the app used before; on the desktop and iOS to JetBrains' Skia build.
//
//   commonMain   the screens, the theme, the strings (composeResources), the platform seams
//   androidMain  the seams over Android (Context, the Media3 service, Toast, intents)
//   desktopMain  the seams over the desktop (QueuePlayer, a toast in the window, the browser)
//   iosMain      the seams over iOS (QueuePlayer over the C ABI, UIKit, Foundation), and the
//                RustifyKit framework the iOS app links (MainViewController)
//
// commonMain is compiled for iOS too, so it uses nothing only a JVM has: what differs is an
// `expect` here with an actual per platform (Formats, AppFiles, Lock, DataPlatform, …).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.varuna.rustify.ui"
        compileSdk = 37
        minSdk = 29
        // The strings and images under composeResources are packaged as Android assets.
        androidResources { enable = true }
    }

    jvm("desktop")

    // Devices and the Apple Silicon simulator. Not iosX64, which :shared has: Compose Multiplatform
    // stopped publishing it (runtime-iosx64 ends at 1.11.0-alpha01), so this interface cannot run on
    // an Intel Mac's simulator; an Intel Mac still compiles the other two.
    //
    // The framework is the whole Kotlin side of the iOS app: this module, and :shared exported with
    // it so Swift sees the player's state and the Keychain's interface. Static, as :shared's was: the
    // Rust core is a static library inside it (the `rustify` cinterop), and iosApp/project.yml links
    // what that core needs. Built by Xcode through embedAndSignAppleFrameworkForXcode, which also
    // copies the composeResources into the app.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "RustifyKit"
            isStatic = true
            export(project(":shared"))
        }
    }

    // The interface in a browser (docs serie-4.x/28), opt-in as in :shared. The output, `rustify.js`
    // and what it loads, is what rustify-server serves as its web root.
    val web = providers.gradleProperty("rustify.web").orNull.toBoolean()
    if (web) {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
        wasmJs {
            browser {
                commonWebpackConfig { outputFileName = "rustify.js" }
            }
            binaries.executable()
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.material.icons.extended)
            api(libs.compose.ui)
            api(libs.compose.components.resources)
            api(libs.coil.compose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // The queue's drag to reorder (TrackScreen); multiplatform, the same library the APK had.
            implementation(libs.reorderable)
        }
        androidMain.dependencies {
            // Coil's network fetcher over OkHttp, a JVM library; iOS has Ktor's (below).
            implementation(libs.coil.network.okhttp)
            // The app's OkHttp (5.x), not the older one Coil would bring, under Coil and Ktor's engine;
            // DataPlatform names its version in the shared cover's User-Agent.
            implementation(libs.okhttp)
            // The local scan's folders are Storage Access Framework trees (AndroidLocalMusicScanner).
            implementation(libs.androidx.documentfile)
            // FileProvider, for sharing a cover image (AndroidAppPlatform.shareImage).
            implementation(libs.androidx.core.ktx)
            // rememberLauncherForActivityResult, for the runtime permissions a screen asks for.
            implementation(libs.androidx.activity.compose)
        }
        getByName("desktopMain").dependencies {
            // As on Android (above).
            implementation(libs.coil.network.okhttp)
            implementation(libs.okhttp)
            // The desktop's own code (DesktopUpdate) reads with it; Android has it in the platform.
            implementation(libs.org.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(compose.desktop.currentOs)
        }

        // Wired by hand, as in :shared: one iosMain for the iOS targets, and iosTest for their
        // tests (run on the simulator, :sharedUi:iosSimulatorArm64Test). maybeCreate, so a Kotlin
        // version that applies the default hierarchy template anyway does not fail on a duplicate.
        val iosMain = maybeCreate("iosMain").apply { dependsOn(commonMain.get()) }
        iosMain.dependencies {
            // Coil registers this fetcher by itself, as it does OkHttp's on the JVMs.
            implementation(libs.coil.network.ktor3)
            implementation(libs.ktor.client.darwin)
        }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        if (web) {
            getByName("wasmJsMain").dependencies {
                implementation(libs.coil.network.ktor3)
                implementation(libs.ktor.client.js)
            }
        }
        val iosTest = maybeCreate("iosTest").apply { dependsOn(commonTest.get()) }
        getByName("iosArm64Test").dependsOn(iosTest)
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)
    }
}

compose.resources {
    // Res.string.x, the generated accessors, under one package the app and the desktop import.
    packageOfResClass = "com.varuna.rustify.ui.resources"
    publicResClass = true
}

// The Google OAuth client the desktop and iOS sign in to Drive with: the phone's "browser" client
// (drive_appauth_client_id / _secret), from local.properties or the environment as the release
// writes them. Generated into the build, never into the sources; without it Drive is not offered
// there. The secret of an installed app's client is not confidential (PKCE is the protection).
val driveClient = tasks.register("generateDriveClient") {
    val out = layout.buildDirectory.dir("generated/driveClient/commonMain")
    val local = rootProject.file("local.properties")
    fun read(key: String, env: String): String {
        val props = Properties()
        if (local.exists()) local.inputStream().use { props.load(it) }
        return (props.getProperty(key) ?: System.getenv(env) ?: "").trim()
    }
    val id = read("drive_appauth_client_id", "DRIVE_APPAUTH_CLIENT_ID")
    val secret = read("drive_appauth_client_secret", "DRIVE_APPAUTH_CLIENT_SECRET")
    inputs.property("id", id)
    inputs.property("secret", secret)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/varuna/rustify/sync/DriveClient.kt").asFile
        file.parentFile.mkdirs()
        fun lit(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        file.writeText(
            "package com.varuna.rustify.sync\n\n" +
                "/** Generated by :sharedUi:generateDriveClient from local.properties or the environment. */\n" +
                "internal object DriveClient {\n" +
                "    const val ID: String = ${lit(id)}\n" +
                "    const val SECRET: String = ${lit(secret)}\n" +
                "}\n"
        )
    }
}
kotlin.sourceSets.named("commonMain") { kotlin.srcDir(driveClient) }

// The desktop's core for the tests that call it (the audio lab's tap, the places, local search): the
// one :desktopApp builds, on java.library.path for the test JVM only — never a path the app itself
// searches. Without the desktop build (-Prustify.desktop=true) there is none, and those calls fail.
if (rootProject.findProject(":desktopApp") != null) {
    tasks.matching { it.name == "desktopTest" }.configureEach {
        dependsOn(":desktopApp:buildRustCoreDesktop")
        (this as Test).systemProperty("java.library.path", rootProject.file("core_engine/target/desktop/release").absolutePath)
    }
}
