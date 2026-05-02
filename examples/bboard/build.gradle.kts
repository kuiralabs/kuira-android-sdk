plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.midnight.example.bboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.midnight.example.bboard"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Midnight Contract SDK — execute circuits, prove, and submit from Android
    implementation(project(":core:compact-engine"))
    // Midnight SDK — fully standalone (no mn serve needed)
    implementation(project(":sdk:midnight-sdk"))
    // Identity — passkey, DID, keyAuthorization
    implementation(project(":core:identity"))
    // Network config (MidnightNetwork enum)
    implementation(project(":core:network"))
    // Native FFI library (provides libkuira_crypto_ffi.so via CMake)
    runtimeOnly(project(":core:crypto"))

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
