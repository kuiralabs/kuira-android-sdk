plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.midnight.kuira.sdk.walletruntime"
    compileSdk = 36

    defaultConfig {
        // Transitively pulls SeedVault (core:auth via :sdk:wallet-seed) which
        // needs API 30.
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
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
    }
}

dependencies {
    // The runtime owns the single live MidnightSdk instance, so it sits above
    // both the seed source (where it gets the BIP-39 seed) and the SDK (which
    // it constructs + holds for every consumer).
    implementation(project(":sdk:wallet-seed"))   // WalletSeedSource — seed bootstrap
    implementation(project(":sdk:midnight-sdk"))   // MidnightSdk + Builder

    // WalletConfig (this module's public type) is expressed in these primitives.
    api(project(":core:network"))                  // MidnightNetwork
    api(project(":core:compact-engine"))            // ProvingMode + ProvingKeyManager
    implementation(project(":core:identity"))       // Drive backup storage + dust-backup crypto

    implementation(libs.androidx.core.ktx)
    // FragmentActivity flows through to WalletSeedSource (biometric host).
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt — MidnightSdkProvider is a @Singleton with an @Inject constructor.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // ── Unit-test stack ──
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
