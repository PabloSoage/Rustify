// The code Android, the desktop app and iOS share (docs 24 §3, 25).
//
//   commonMain     models, pure logic, platform interfaces (KeyValueStore, Log), engine models,
//                  and the NativeEngine contract (expect object)
//   jvmCommonMain  what Android and the desktop share because both are a JVM: NativeEngine over JNI
//   androidMain    Android's Log and SharedPreferencesStore
//   desktopMain    the desktop's Log, PreferencesStore, AppDirs, RustAudioEngine
//   iosMain        CoreCall (the C ABI through cinterop), NativeEngine over it, Log, UserDefaultsStore,
//                  the Keychain store and the player's backend
//
// The app depends on this module, so it is part of every APK build; the desktop app and the iOS
// framework (:sharedUi's RustifyKit) are not (see settings.gradle.kts and tools/ios/build-core.sh).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.varuna.rustify.shared"
        compileSdk = 37
        minSdk = 29
    }

    jvm("desktop")

    // Devices, the Apple Silicon simulator, and the simulator on an Intel Mac (iosX64: the owner's
    // macOS VM on an AMD host is x86_64, and so is its simulator). Each is built only when asked for.
    // On a host that is not a Mac these targets are skipped with a warning, which is expected.
    //
    // No framework here: the iOS app links :sharedUi's (RustifyKit), which exports this module. Two
    // Kotlin frameworks in one app would be two runtimes, each with its own copy of this code.
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.compilations.getByName("main") {
            cinterops.create("rustify") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/rustify.def"))
                includeDirs(rootProject.file("core_engine/include"))
                // One directory per Kotlin/Native target, written by tools/ios/build-core.sh.
                extraOpts("-libraryPath", rootProject.file("core_engine/target/ios/${target.name}").absolutePath)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // platform/Http.kt hands out an HttpClient, so its type is part of this module's API.
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // Android and the desktop are both a JVM: JNI, java.io, java.text. One source set for what
        // they share, so NativeEngine over JNI is written once.
        //
        // Wired by hand, and therefore iosMain too: an explicit dependsOn switches Kotlin's default
        // hierarchy template off (Gradle says so with a warning), and without the template nothing
        // creates iosMain for the iOS targets. maybeCreate, so a Kotlin version that applies the
        // template anyway does not fail on a duplicate.
        val jvmCommonMain = maybeCreate("jvmCommonMain").apply { dependsOn(commonMain.get()) }
        jvmCommonMain.dependencies { implementation(libs.okhttp) } // the audio sources' HTTP, the app's OkHttp
        androidMain.get().dependsOn(jvmCommonMain)
        getByName("desktopMain").dependsOn(jvmCommonMain)
        // Ktor's engine on both JVMs: OkHttp, which the app and Coil already carry.
        jvmCommonMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        val iosMain = maybeCreate("iosMain").apply { dependsOn(commonMain.get()) }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosX64Main").dependsOn(iosMain)
        getByName("desktopTest").dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}
