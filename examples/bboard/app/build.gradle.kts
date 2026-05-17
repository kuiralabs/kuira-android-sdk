plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    // Kuira SDK — consumed as Maven artifacts published to mavenLocal by the
    // parent project (`./gradlew publishToMavenLocal`). POMs include transitive
    // deps (zxing, bitcoinj, ktor, room, credentials, etc.) so BBoard doesn't
    // redeclare them by hand. Add a new Kuira module = one `implementation` line.
    implementation("com.midnight.kuira:common:0.1.0-SNAPSHOT")
    implementation("com.midnight.kuira:midnight-sdk:0.1.0-SNAPSHOT")
    // SDK uses `implementation(project(":core:*"))` so those types aren't exposed
    // to consumers transitively. BBoard references compact + auth + network types
    // directly, so declare them here.
    implementation("com.midnight.kuira:compact-engine:0.1.0-SNAPSHOT")
    implementation("com.midnight.kuira:identity:0.1.0-SNAPSHOT")
    implementation("com.midnight.kuira:auth:0.1.0-SNAPSHOT")
    implementation("com.midnight.kuira:network:0.1.0-SNAPSHOT")
    implementation("com.midnight.kuira:crypto:0.1.0-SNAPSHOT")
    implementation("com.midnight.kuira:ledger:0.1.0-SNAPSHOT")

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

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
