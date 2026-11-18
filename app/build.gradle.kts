plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.foldki.app"
    compileSdk = 36

    defaultConfig {
        // FA Fold KI: own app id, so it installs next to the original Fold KI (de.foldki.app).
        applicationId = "de.foldki.fa"
        minSdk = 26
        targetSdk = 36
        versionCode = 41
        versionName = "1.0.2"
        // Galaxy Z Fold7 and all current phones are 64-bit ARM; keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
        // llama.cpp (from Jarvis-Android): always optimised, 16 KB pages (Android 15+). Shared C++ runtime, because
        // llama/ggml are several libraries now (compute parts CPU + GPU/OpenCL are loaded at runtime).
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                cppFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Fixed key, committed on purpose: every build must carry the same signature,
    // otherwise Android refuses to install an update over the existing app.
    // This key is only for privately sideloaded APKs, not for a Play Store release.
    signingConfigs {
        create("sideload") {
            storeFile = file("foldki-release.jks")
            storePassword = "foldki-sideload"
            keyAlias = "foldki"
            keyPassword = "foldki-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            isJniDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        jniLibs {
            // Unpacked into nativeLibraryDir, so llama.cpp finds its compute parts (libggml-cpu.so, libggml-opencl.so).
            useLegacyPackaging = true
            // Only needed for linking: at runtime the phone's own OpenCL (/vendor/lib64/libOpenCL.so) must be used.
            excludes += "**/libOpenCL.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
                "META-INF/*.version",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // The local AI is llama.cpp (C++, built from source via CMake, see src/main/cpp) – no Gemini Nano, no Gemma/LiteRT-LM.

    // Reading attachments: PDF text, OCR for photos/scans (offline), photo rotation
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // Supertonic 3 voices (Elena, Tessa, Caleb) on-device
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")

    // GitHub access for the local model's tools
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Fold/hinge position for the split view (on-device only)
    implementation("androidx.window:window:1.5.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only has stubs)
    testImplementation("org.json:json:20240303")
}

// Run the JVM unit tests (e.g. DocumentParser) in every CI build without touching the workflow.
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn("testReleaseUnitTest") }
