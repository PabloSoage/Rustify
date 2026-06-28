plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.varuna.rustify"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.varuna.rustify"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"


    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    lint {
        disable += "MissingTranslation"
        abortOnError = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols.add("**/libpython.zip.so")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // YouTubeDL Android (JunkFood02 Fork - actively maintained, yt-dlp)
    implementation(libs.youtubedl.android)
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}

// --- RUST CORE INTEGRATION ---

// Define the target architectures to compile (64-bit physical devices and modern emulators)
val targets = listOf("arm64-v8a", "x86_64")

// Detect the host operating system to invoke the correct executable file for Cargo
val cargoCommand = if (System.getProperty("os.name").lowercase().contains("windows")) "cargo.exe" else "cargo"

// Get the number of CPU cores for parallel compilation
val cpuCount = Runtime.getRuntime().availableProcessors()

// Dynamically register an individual Gradle 'Exec' task for each specified architecture
targets.forEach { target ->
    tasks.register<Exec>("buildRustCore_${target}") {
        group = "rust"
        description = "Compiles the Rust core_engine for $target architecture via cargo-ndk"
        workingDir = file("../core_engine")

        // Retrieve the NDK path from Android components
        val androidComponents = project.extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
        val ndkDir = androidComponents.sdkComponents.ndkDirectory.get().asFile
        val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile

        // Set the environment variables required by cargo-ndk
        environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
        environment("ANDROID_HOME", sdkDir.absolutePath)

        inputs.dir("../core_engine/src")
        outputs.dir("src/main/jniLibs/$target")

        // Execute the cargo-ndk command with explicit job parallelism and specific architecture target
        commandLine(
            cargoCommand, "ndk",
            "-t", target,
            "-o", "../app/src/main/jniLibs",
            "build", "--release",
            "-j", cpuCount.toString() // Use all available CPU cores for compilation
        )
    }
}

// Orchestrator task that triggers the compilation of all defined Rust architectures in parallel
tasks.register("buildRustCoreAll") {
    group = "rust"
    description = "Compiles the Rust core_engine for all supported Android ABIs (in parallel)"

    // Each architecture task will run in parallel with others
    dependsOn(targets.map { "buildRustCore_${it}" })
}

// Hook into the Android build lifecycle to automate Rust compilation
// This intercepts the package generation phase right before Android merges the native JNI libraries
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn("buildRustCoreAll")
    }
}





