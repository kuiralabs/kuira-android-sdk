plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.midnight.kuira.core.auth"
    compileSdk = 36

    defaultConfig {
        minSdk = 30 // Required for setUserAuthenticationParameters + CryptoObject with DEVICE_CREDENTIAL

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
            // Real Android system resources for Robolectric tests
            // (SeedVault touches Context.filesDir; Robolectric stubs it
            // with a real per-test temp directory).
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Core modules
    implementation(project(":core:network")) // MidnightNetwork enum for per-network address cache

    // Core Android
    implementation(libs.androidx.core.ktx)

    // Biometric authentication (BiometricPrompt + CryptoObject)
    implementation(libs.androidx.biometric)

    // Fragment (required by BiometricPrompt)
    implementation(libs.androidx.fragment.ktx)

    // Hilt for dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    //
    // mockk stubs BiometricGate's cipher chain so SeedVaultTest can
    // verify the silent / prompt branching without a real Keystore.
    // Robolectric provides a real Context.filesDir + SharedPreferences
    // — required for testing atomic file IO + storeSeed/loadSeed
    // round-trips on the JVM. kotlinx-coroutines-test gives suspend
    // tests + a controllable Main dispatcher.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(project(":core:testing"))

    // Android Instrumentation Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(project(":core:testing"))
}
