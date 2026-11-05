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
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

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

    // The interface in the browser (docs serie-4.x/28), opt-in like the desktop app: nothing Android
    // or iOS builds configures or downloads anything for it.
    val web = providers.gradleProperty("rustify.web").orNull.toBoolean()
    if (web) {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
        wasmJs { browser() }
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

        // Where the core is one JSON call away ([NativeEngine] over CoreCall): iOS and the web.
        val coreCallMain = maybeCreate("coreCallMain").apply { dependsOn(commonMain.get()) }
        val iosMain = maybeCreate("iosMain").apply { dependsOn(coreCallMain) }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        if (web) {
            getByName("wasmJsMain").apply {
                dependsOn(coreCallMain)
                dependencies { implementation(libs.ktor.client.js) }
            }
        }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosX64Main").dependsOn(iosMain)
        val iosTest = maybeCreate("iosTest").apply { dependsOn(commonTest.get()) }
        getByName("iosArm64Test").dependsOn(iosTest)
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        getByName("iosX64Test").dependsOn(iosTest)
        getByName("desktopTest").dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}
// The desktop's core for the tests that call it, as in :sharedUi: on java.library.path for the test JVM only.
if (rootProject.findProject(":desktopApp") != null) {
    tasks.matching { it.name == "desktopTest" }.configureEach {
        dependsOn(":desktopApp:buildRustCoreDesktop")
        (this as Test).systemProperty("java.library.path", rootProject.file("core_engine/target/desktop/release").absolutePath)
    }
}
