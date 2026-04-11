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
    buildFeatures {
        compose = true
    }
}

/**
 * Set up adb reverse port forwarding for the localnet (UNDEPLOYED) services.
 *
 * On a physical device, the wallet uses 127.0.0.1 to reach the developer's
 * laptop services. adb reverse tunnels these ports over the USB / wifi-debugging
 * connection so the device can reach localhost on the host machine.
 *
 * Run this once per adb session before testing localnet on a physical device:
 *   ./gradlew adbReverseLocalnet
 *
 * Ports tunneled (must match NetworkConfig.LOCALNET_*_PORT constants):
 *   - 8088: indexer GraphQL
 *   - 9944: node RPC
 *   - 6300: proof server
 *
 * Note: emulators use 10.0.2.2 instead and don't need this — but the task
 * is harmless to run there.
 */
tasks.register("adbReverseLocalnet") {
    group = "kuira"
    description = "Forward localnet ports (8088, 9944, 6300) from device to host via adb reverse."

    doLast {
        val adb = android.sdkDirectory.resolve("platform-tools/adb").absolutePath
        val ports = listOf(8088, 9944, 6300)
        ports.forEach { port ->
            println("adb reverse tcp:$port tcp:$port")
            exec {
                commandLine(adb, "reverse", "tcp:$port", "tcp:$port")
            }
        }
        println("Localnet ports forwarded. Phone's localhost now reaches host's localhost.")
    }
}

dependencies {
    // Feature modules
    implementation(project(":feature:balance"))
    implementation(project(":feature:send"))
    implementation(project(":feature:dust"))
    implementation(project(":feature:onboarding"))
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