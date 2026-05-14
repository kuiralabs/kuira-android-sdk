plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.midnight.example.common"
    compileSdk = 36

    defaultConfig {
        // SeedVault (core:auth) needs API 30 for setUserAuthenticationParameters
        // + CryptoObject with DEVICE_CREDENTIAL — match its floor.
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
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
    // Bootstrap the wallet end-to-end inside the panel so host apps don't
    // re-implement seed handling, SDK construction, or dust polling.
    implementation(project(":sdk:midnight-sdk"))
    implementation(project(":core:auth"))      // SeedVault, WalletKeyManager
    implementation(project(":core:crypto"))    // BIP39 seed generation
    implementation(project(":core:ledger"))    // BalanceFormatter + SubmissionResult
    implementation(project(":core:network"))   // MidnightNetwork
    implementation(project(":core:compact-engine"))  // ProvingKeyManager.installFromLocalTmp

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)  // FragmentActivity hosts SeedVault biometric prompts
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // ZXing core — QR matrix generation for the Receive screen. `:core` is the
    // pure-Java module (~500KB); the Android-specific bitmap helpers we
    // implement ourselves to avoid pulling in `:android-core` (which links
    // against android.hardware.camera and bloats the APK).
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
