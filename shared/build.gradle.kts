// The code Android, the desktop app and iOS share (docs 24 §3, 25).
//
//   commonMain     models, pure logic, platform interfaces (KeyValueStore, Log), engine models
//   jvmCommonMain  what Android and the desktop share because both are a JVM: NativeEngine (JNI)
//   androidMain    Android's Log and SharedPreferencesStore
//   desktopMain    the desktop's Log, PreferencesStore, AppDirs, RustAudioEngine
//   iosMain        CoreCall (the C ABI through cinterop), Log, UserDefaultsStore
//
// The app depends on this module, so it is part of every APK build; the desktop app and the iOS
// framework are not (see settings.gradle.kts and tools/ios/build-core.sh).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    androidLibrary {
        namespace = "com.varuna.rustify.shared"
        compileSdk = 37
        minSdk = 29
    }

    jvm("desktop")

    // Devices and the Apple Silicon simulator. Not iosX64: Intel Macs are not a target worth a build.
    // On a host that is not a Mac these targets are skipped with a warning, which is expected.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "RustifyShared"
            isStatic = true
        }
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // Android and the desktop are both a JVM: JNI, java.io, java.text. One source set for what
        // they share, so NativeEngine is written once.
        //
        // Wired by hand, and therefore iosMain too: an explicit dependsOn switches Kotlin's default
        // hierarchy template off (Gradle says so with a warning), and without the template nothing
        // creates iosMain for the two iOS targets. maybeCreate, so a Kotlin version that applies the
        // template anyway does not fail on a duplicate.
        val jvmCommonMain = maybeCreate("jvmCommonMain").apply { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmCommonMain)
        getByName("desktopMain").dependsOn(jvmCommonMain)

        val iosMain = maybeCreate("iosMain").apply { dependsOn(commonMain.get()) }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
