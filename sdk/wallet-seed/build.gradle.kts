plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.midnight.kuira.sdk.walletseed"
    compileSdk = 36

    defaultConfig {
        // SeedVault (core:auth) needs API 30; we depend on it transitively.
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

    testOptions {
        unitTests {
            // Robolectric: SharedPreferences shadowing + Context for the
            // wallet_seed prefs file. Same pattern as :sdk:dapp-ui tests.
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
    }
}

dependencies {
    // Wallet seed is bootstrapped from these primitives:
    //  - SeedVault / WalletKeyManager / BiometricGate (core:auth) — local
    //    encrypted cache + Keystore-bound master key.
    //  - PasskeyManager + SeedDeriver (core:identity) — PRF roundtrip
    //    plus the deterministic entropy → BIP-39 seed chain.
    //  - SigilStateStore (core:identity) — "is a passkey forged?" query
    //    that gates the bootstrap.
    implementation(project(":core:auth"))
    implementation(project(":core:identity"))

    implementation(libs.androidx.core.ktx)
    // FragmentActivity required by SeedVault.storeSeed / loadSeed
    // (BiometricPrompt host).
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt — WalletSeedSource is a @Singleton that hosts inject.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // ── Unit-test stack ──
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
