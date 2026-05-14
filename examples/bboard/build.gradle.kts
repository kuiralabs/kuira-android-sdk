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
    // Auth — SeedVault, BiometricGate, WalletKeyManager (canary upgrade:
    // BBoard now persists its own generated seed instead of using TEST_SEED).
    implementation(project(":core:auth"))
    // Network config (MidnightNetwork enum)
    implementation(project(":core:network"))
    // Crypto — promoted from runtimeOnly to implementation so the canary can
    // call BIP39.entropyToMnemonic / mnemonicToSeed for seed generation.
    implementation(project(":core:crypto"))
    // Ledger — exposes TransactionSubmitter.SubmissionResult (returned by
    // MidnightSdk.registerForDustGeneration) and BalanceFormatter (token
    // decimals are a ledger concept: 6 for NIGHT, 15 for DUST).
    implementation(project(":core:ledger"))

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // FragmentActivity — SeedVault.loadSeed / storeSeed host the biometric prompt
    // on a FragmentActivity (BBoardActivity is already a ComponentActivity which
    // extends FragmentActivity).
    implementation(libs.androidx.fragment.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
