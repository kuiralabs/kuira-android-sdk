plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("com.midnight.kuira.contract") version "0.1.0-alpha01"
}

kuiraContract {
    source.set("../contract/src/managed/bboard")
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
    // Kuira SDK — one line. `dapp-ui` is the panel entry; it `api`-exposes the
    // SDK surface BBoard touches directly (compact-engine, identity, network,
    // wallet-runtime, wallet-seed, auth, crypto, ledger) so the types are on
    // BBoard's compile classpath transitively. No per-module redeclaration.
    implementation("io.github.kuiralabs:dapp-ui:0.1.0-alpha01")

    // AndroidX directly used by BBoard (FragmentActivity host, Compose). Things
    // Kuira pulls in transitively (biometric, credentials, room, etc.) come
    // through the SDK/common POMs and don't need to be redeclared here.
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Hilt DI — required because `dapp-ui` ViewModels are `@HiltViewModel`
    // and resolved via `hiltViewModel()` at the Compose call site. BBoard
    // uses the default passkey rpId (`nel349.github.io`) provided by
    // `core:identity:IdentityModule`; no BBoard-side Hilt module needed.
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
