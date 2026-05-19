plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.midnight.kuira.dapp"
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
        // Needed for `BuildConfig.DEBUG` — the panel modules use it to gate
        // canary-only verbose logging (raw PRF outputs, etc.) so release
        // builds R8-strip those lines and the strings they interpolate.
        // See `SigilPanelViewModel.testPrf` for the canonical example.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric prerequisite. Without it, the test classpath has
            // no `res/values/...` for Android system resource lookups
            // (which Robolectric's shadow Context/Resources requires) and
            // tests blow up at construction with "Unable to find resource".
            // Cheap to enable; needed by every Robolectric-flagged test.
            isIncludeAndroidResources = true
            // Don't silently return default values for unstubbed Android
            // API calls — Robolectric handles real-shadow behaviour for
            // the cases that matter (SharedPreferences durability, file IO).
            isReturnDefaultValues = false
        }
    }
}

dependencies {
    // Bootstrap the wallet end-to-end inside the panel so host apps don't
    // re-implement seed handling, SDK construction, or dust polling.
    implementation(project(":sdk:midnight-sdk"))
    implementation(project(":core:auth"))      // SeedVault, WalletKeyManager
    implementation(project(":core:crypto"))    // BIP39 seed generation
    implementation(project(":core:identity"))  // PasskeyManager, DidKeyGenerator, SigilBackup — sigil panel
    implementation(project(":core:ledger"))    // BalanceFormatter + SubmissionResult
    implementation(project(":core:network"))   // MidnightNetwork
    implementation(project(":core:compact-engine"))  // ProvingKeyManager.installFromLocalTmp

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)  // FragmentActivity hosts SeedVault biometric prompts
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt DI — panel ViewModels are @HiltViewModel + @Inject. Wallet-
    // and identity-side dependencies (WalletKeyManager, BiometricGate,
    // SeedVault, PasskeyManager, PasskeyConfig) are provided by
    // `core:auth:AuthModule` + `core:identity:IdentityModule`; this
    // module only adds Block Store + SigilBackup on top. Consumers
    // override the passkey rpId by replacing `IdentityModule`.
    // `hilt-navigation-compose` gives Composables `hiltViewModel()`
    // for VM resolution at the call site.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // ZXing core — QR matrix generation for the Receive screen. `:core` is the
    // pure-Java module (~500KB); the Android-specific bitmap helpers we
    // implement ourselves to avoid pulling in `:android-core` (which links
    // against android.hardware.camera and bloats the APK).
    implementation(libs.zxing.core)

    // ── Unit-test stack ──
    //
    // mockk for state-machine ordering / mocked dependencies — supports
    // suspend funcs natively which is essential since both ViewModels
    // expose coroutine entry points.
    //
    // Robolectric for the small set of tests that need real Android
    // system behaviour (SharedPreferences durability — verifying
    // `dismissBackup`'s commit() actually fsyncs — and SeedVault's
    // atomic-write temp-file-rename on real Context.filesDir). Pure
    // state-machine tests don't need Robolectric.
    //
    // kotlinx-coroutines-test for `runTest` + `TestScope` —
    // viewModelScope launches need a controlled dispatcher to be
    // assertable from synchronous test bodies.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    // Shared `MainDispatcherRule` — used by ViewModel tests to swap
    // Dispatchers.Main for a TestDispatcher so `init { viewModelScope.launch }`
    // runs inline.
    testImplementation(project(":core:testing"))
}
