plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.g2glasses"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.g2glasses"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Phone (S25 Ultra) and x86_64 emulator only; drops ~100 MB of unused native libraries.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // whisper.cpp (Whisper engine), statically linked into libwhisper_jni.so
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    // Whisper engine: NDK r28 (16 KB page aligned by default) and CMake from the SDK.
    // Install both in Android Studio: SDK Manager > SDK Tools > NDK (Side by side) and CMake.
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.0"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
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
    implementation(libs.kotlinx.coroutines.android)

    // Offline speech recognition (Vosk) and translation (ML Kit)
    implementation(libs.vosk.android)
    // JNA as an AAR: the @aar type carries the Android native library (libjnidispatch.so) that
    // Vosk needs. Kept as a coordinate string because the catalog cannot express @aar.
    // vosk-android 0.3.75 itself depends on jna 5.18.1@aar, so Gradle resolves to 5.18.1.
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation(libs.mlkit.translate)

    // What the glasses show (layout, screens) and the BLE link that shows it
    implementation(project(":glasses-ui"))
    implementation(project(":g2-gateway"))
    // sherpa-onnx (Parakeet engine): native libs + Kotlin API. Not on Maven Central; downloaded into
    // app/libs/ by scripts/fetch_models.*, so it is a file dependency outside the version catalog.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (android.jar only has stubs)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}