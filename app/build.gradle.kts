plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.varuna.rustify"
    compileSdk = 37
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
            @Suppress("UnstableApiUsage")
            enableSplit = false
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols.add("**/libffmpeg.zip.so")
            keepDebugSymbols.add("**/libffprobe.zip.so")
            keepDebugSymbols.add("**/libytdlp.zip.so")
            keepDebugSymbols.add("**/libpython.zip.so")
        }
    }

    lint {
        disable += "MissingTranslation"
        disable += "OldTargetApi"
        disable += "IconDuplicates"
        abortOnError = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }


}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
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
    // FFmpeg AAR tells youtubedl-android to look for native ffmpeg/ffprobe.
    // Static replacements in jniLibs/ override the AAR's dynamic libs.
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.profileinstaller)
    // E50 — Google Drive sync (AppData folder + REST v3).
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    // E99 — open-source keyless map (travel playlist).
    implementation(libs.maplibre.android)
}

// --- RUST CORE INTEGRATION ---

// Define the target architectures to compile (64-bit physical devices and modern emulators)
val targets = listOf("arm64-v8a", "x86_64")

// Detect the host operating system to invoke the correct executable file for Cargo
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val cargoExecutableName = if (isWindows) "cargo.exe" else "cargo"
val cargoHome = System.getenv("CARGO_HOME") ?: (System.getProperty("user.home") + "/.cargo")
val cargoPath = file("$cargoHome/bin/$cargoExecutableName")
val cargoCommand = if (cargoPath.exists()) cargoPath.absolutePath else cargoExecutableName

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

        // Bindgen and cargo-ndk on Windows often fail if the NDK path contains spaces (e.g., in the user profile).
        // To fix this universally, we create a Junction (symlink) in the build directory (which usually has no spaces).
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val noSpaceNdkDir = file("${project.layout.buildDirectory.get().asFile.absolutePath}/ndk_link")
        val needsJunction = isWindows && ndkDir.absolutePath.contains(" ")
        val effectiveNdkDir = if (needsJunction) noSpaceNdkDir else ndkDir

        // Set the environment variables required by cargo-ndk
        environment("ANDROID_NDK_HOME", effectiveNdkDir.absolutePath)
        environment("ANDROID_HOME", sdkDir.absolutePath)

        // Find libclang statically to avoid I/O during Gradle's configuration phase (supports Configuration Cache)
        val hostTag = if (isWindows) "windows-x86_64" else if (System.getProperty("os.name").lowercase().contains("mac")) "darwin-x86_64" else "linux-x86_64"
        val clangDir = if (isWindows) {
            file("${effectiveNdkDir.absolutePath}/toolchains/llvm/prebuilt/$hostTag/bin")
        } else {
            file("${effectiveNdkDir.absolutePath}/toolchains/llvm/prebuilt/$hostTag/lib64")
        }

        environment("LIBCLANG_PATH", clangDir.absolutePath)
        if (isWindows) {
            // On Windows, libclang.dll depends on other DLLs in the same folder. We append it to PATH.
            val currentPath = System.getenv("PATH") ?: ""
            environment("PATH", "${clangDir.absolutePath};$currentPath")
        }

        doFirst {
            // Create the symlink right before the task executes (fully supports Configuration Cache)
            if (needsJunction && !noSpaceNdkDir.exists()) {
                noSpaceNdkDir.parentFile.mkdirs()
                Runtime.getRuntime().exec(arrayOf("cmd", "/c", "mklink", "/J", noSpaceNdkDir.absolutePath, ndkDir.absolutePath)).waitFor()
            }
        }

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





