// The interface Android and the desktop share (docs serie-4.x/26): the phone's Compose screens,
// moved here rather than rewritten, over Compose Multiplatform. On Android it resolves to the same
// androidx.compose artifacts the app used before; on the desktop to JetBrains' Skia build of them.
//
//   commonMain   the screens, the theme, the strings (composeResources), the platform seams
//   androidMain  the seams over Android (Context, the Media3 service, Toast, intents)
//   desktopMain  the seams over the desktop (QueuePlayer, a toast in the window, the browser)
//
// Both targets are JVMs, so commonMain may use what :shared's jvmCommonMain gives (NativeEngine,
// the repositories); iOS is not a target of this module.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.varuna.rustify.ui"
        compileSdk = 37
        minSdk = 29
        // The strings and images under composeResources are packaged as Android assets.
        androidResources { enable = true }
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            api(compose.ui)
            api(compose.components.resources)
            api(libs.coil.compose)
            implementation(libs.coil.network.okhttp)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // The queue's drag to reorder (TrackScreen); multiplatform, the same library the APK had.
            implementation(libs.reorderable)
        }
        androidMain.dependencies {
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
    }
}

compose.resources {
    // Res.string.x, the generated accessors, under one package the app and the desktop import.
    packageOfResClass = "com.varuna.rustify.ui.resources"
    publicResClass = true
}
