plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.g2glasses.gateway"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":glasses-ui"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
