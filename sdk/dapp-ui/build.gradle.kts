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
        // Dev-seed BuildConfig lives in :sdk:wallet-seed — the panel
        // consumes the seed via WalletSeedSource, so the override
        // doesn't need to be wired here.
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
    api(project(":sdk:midnight-sdk"))
    // Owns the single live MidnightSdk instance + canonical WalletConfig.
    // The panel is the config authority (it has the network/proving toggles);
    // it drives the provider's ensureSdk and reads the shared SDK back. No
    // more per-VM SDK construction — see MidnightSdkProvider.
    api(project(":sdk:wallet-runtime"))
    // Single source of truth for the wallet's BIP-39 seed — owns the
    // PRF derivation, SeedVault cache, sigil gate, and dev override.
    // The panel's ensureSeedReady delegates here, rather than re-
    // implementing the bootstrap as it used to.
    api(project(":sdk:wallet-seed"))
    api(project(":core:auth"))      // SeedVault, WalletKeyManager
    api(project(":core:crypto"))    // BIP39 seed generation
    api(project(":core:identity"))  // PasskeyManager, DidKeyGenerator, SigilBackup — sigil panel
    api(project(":core:ledger"))    // BalanceFormatter + SubmissionResult
    api(project(":core:network"))   // MidnightNetwork
    api(project(":core:compact-engine"))  // ProvingKeyManager.installFromLocalTmp

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
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // `hilt-navigation-compose` is api-exposed because consumers MUST
    // call `hiltViewModel()` at the SigilStatusPanel / WalletStatusPanel
    // call site to obtain the panel's @HiltViewModel — if it were
    // `implementation`-scoped, consumers would have to re-declare it
    // in every dapp module's build.gradle, which they all do today.
    // Promoting to `api` makes the one-line `dapp-ui` dependency in
    // Recipe 1 self-sufficient for the SigilStatusPanel call.
    api("androidx.hilt:hilt-navigation-compose:1.1.0")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // Lottie — the Rarámuri runner backup/progress indicator (ships in the SDK).
    implementation(libs.lottie.compose)
    // Compose preview tooling for the SDK's own pill UI (@Preview).
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

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
