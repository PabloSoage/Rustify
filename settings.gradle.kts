pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    // The web build fetches its Node, Yarn and Binaryen from repositories its Kotlin plugin adds by itself.
    repositoriesMode.set(
        if (providers.gradleProperty("rustify.web").orNull.toBoolean()) RepositoriesMode.PREFER_PROJECT else RepositoriesMode.FAIL_ON_PROJECT_REPOS
    )
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Rustify"
include(":app")
// Code shared by Android, the desktop app and iOS (docs 24 §3): models, pure logic, the JNI bridge
// for the JVM targets and the C ABI bridge for iOS. The app depends on it, so it is always included.
include(":shared")
// The interface Android and the desktop share (docs serie-4.x/26). The app depends on it.
include(":sharedUi")
// The desktop app (Windows, Linux, macOS) is opt-in while it is new: `-Prustify.desktop=true`, or
// `rustify.desktop=true` in ~/.gradle/gradle.properties. Its Compose Multiplatform plugin is resolved
// and configured by every build that includes it, and building the APK must not depend on that.
if (providers.gradleProperty("rustify.desktop").orNull.toBoolean()) {
    include(":desktopApp")
}
