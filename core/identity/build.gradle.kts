plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.midnight.kuira.core.identity"
    compileSdk = 36

    defaultConfig {
        minSdk = 30

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
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Sibling modules
    implementation(project(":core:crypto"))
    implementation(project(":core:auth"))

    // Core Android
    implementation(libs.androidx.core.ktx)

    // CredentialManager — passkey creation and authentication ceremonies
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)

    // Crypto (bitcoinj for Base58, secp256k1-kmp for curve ops)
    implementation(libs.bitcoinj.core)
    implementation(libs.secp256k1.kmp)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Google Block Store — encrypted cloud backup
    implementation(libs.play.services.blockstore)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))

    // Android Instrumentation Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(project(":core:testing"))
}
