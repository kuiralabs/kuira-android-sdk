import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.hilt)
    alias(libs.plugins.ksp)
}

/**
 * Dev-seed escape hatch — same wiring as the original Step-3 home in
 * `sdk:dapp-ui`. Reads `kuira.dev.seed` from the root `local.properties`
 * at configure time and exposes it as `BuildConfig.DEV_SEED_HEX`. When
 * set, [WalletSeedSource.ensureSeedReady] bypasses the passkey PRF
 * path and returns the supplied 64-byte BIP-39 seed directly.
 *
 * Lives in this module (not dapp-ui) so non-UI hosts — Kicks's
 * `MatchManager`, future game/agent runtimes — get the same dev
 * workflow without depending on Compose.
 *
 * See sdk/wallet-seed/README.md (or sdk/dapp-ui/README.md until that
 * exists) for the local.properties workflow.
 */
val devSeedHex: String = run {
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) {
        val props = Properties()
        localProps.reader().use { reader -> props.load(reader) }
        props.getProperty("kuira.dev.seed", "").trim()
    } else ""
}

android {
    namespace = "com.midnight.kuira.sdk.walletseed"
    compileSdk = 36

    defaultConfig {
        // SeedVault (core:auth) needs API 30; we depend on it transitively.
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "DEV_SEED_HEX", "\"$devSeedHex\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    buildFeatures {
        // BuildConfig.DEBUG gates the dev-seed branch (R8 DCE in release).
        // BuildConfig.DEV_SEED_HEX carries the local.properties override.
        buildConfig = true
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
