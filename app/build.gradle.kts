plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.midnight.kuira"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.midnight.kuira"
        minSdk = 30 // Required by core:auth (setUserAuthenticationParameters, CryptoObject + DEVICE_CREDENTIAL)
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: falls back to the debug keystore when the
    // production keystore file `release.keystore` isn't present, so
    // `./gradlew :app:installRelease` works for local testing without
    // requiring every developer to have the production signing key.
    //
    // When T1-14 (production keystore setup) is done, drop
    // `release.keystore` at the repo root (git-ignored) and set the
    // three env vars — this block will then pick up the production
    // config automatically without further build-script changes.
    //
    // Play Store enforces that debug-signed APKs cannot be uploaded
    // to the production track, so the fallback can't leak into a real
    // shipped build by accident.
    val releaseKeystore = rootProject.file("release.keystore").takeIf { it.exists() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KUIRA_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("KUIRA_RELEASE_KEY_ALIAS") ?: "kuira-release"
                keyPassword = System.getenv("KUIRA_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 / ProGuard enabled for v1.0 per 8B.0 de-risk decision.
            // See app/proguard-rules.pro for the keep-rule strategy.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                // Dev fallback — debug keystore is on every dev's machine.
                // NEVER the production config; Play Store rejects it.
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug builds stay unminified to keep the iteration loop fast.
            // R8 verification happens in release builds (and via
            // `./gradlew :app:assembleRelease` during 8B.0 de-risking).
            isMinifyEnabled = false
        }
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
        buildConfig = true
    }
}

// Note: adbReverseLocalnet lives in the root build.gradle.kts since it's
// project-wide infrastructure consumed by every deployable module
// (:app, :examples:bboard, :examples:midnight-kicks). Library modules
// like :sdk:midnight-sdk don't install to a device so they're not in scope.

dependencies {
    // Feature modules
    implementation(project(":feature:balance"))
    implementation(project(":feature:send"))
    implementation(project(":feature:dust"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:settings"))
    implementation(project(":core:auth")) // Direct dep: SeedVault + WalletAddressCache injected into MainActivity
    implementation(project(":core:identity")) // Direct dep: app declares its own PasskeyConfig (SDK provides no default — wishlist #22)
    implementation(project(":core:connector"))
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":core:designsystem"))
    implementation(project(":core:indexer"))
    implementation(project(":core:network"))

    // Hilt for dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}