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
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    // Res.string.x, the generated accessors, under one package the app and the desktop import.
    packageOfResClass = "com.varuna.rustify.ui.resources"
    publicResClass = true
}
