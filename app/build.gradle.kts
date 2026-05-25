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

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// --- RUST CORE INTEGRATION ---

// Define the target architectures to compile (64-bit physical devices and modern emulators)
val targets = listOf("arm64-v8a", "x86_64")

// Detect the host operating system to invoke the correct executable file for Cargo
val cargoCommand = if (System.getProperty("os.name").lowercase().contains("windows")) "cargo.exe" else "cargo"

// Dynamically register an individual Gradle 'Exec' task for each specified architecture
targets.forEach { target ->
    tasks.register<Exec>("buildRustCore_${target}") {
        group = "rust"
        description = "Compiles the Rust core_engine for $target architecture via cargo-ndk"
        workingDir = file("../core_engine")

        inputs.dir("../core_engine/src")
        outputs.dir("src/main/jniLibs/$target")

        // Execute the cargo-ndk command with the specific architecture target flag and output directory
        commandLine(
            cargoCommand, "ndk",
            "-t", target,
            "-o", "../app/src/main/jniLibs",
            "build", "--release"
        )
    }
}

// Orchestrator task that triggers the compilation of all defined Rust architectures sequentially or in parallel
tasks.register("buildRustCoreAll") {
    group = "rust"
    description = "Compiles the Rust core_engine for all supported Android ABIs"
    dependsOn(targets.map { "buildRustCore_${it}" })
}

// Hook into the Android build lifecycle to automate Rust compilation
// This intercepts the package generation phase right before Android merges the native JNI libraries
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn("buildRustCoreAll")
    }
}





