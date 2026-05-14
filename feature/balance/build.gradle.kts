plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.midnight.kuira.feature.balance"
    compileSdk = 36

    defaultConfig {
        minSdk = 30 // Required by core:auth

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
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true // Mock android.util.Log and other Android APIs
        }
    }
}

dependencies {
    // Core modules
    implementation(project(":core:crypto"))
    implementation(project(":core:indexer"))
    implementation(project(":core:ledger"))  // BalanceFormatter (token decimals)
    implementation(project(":core:network"))
    implementation(project(":core:auth"))

    // Android Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Hilt for dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // AndroidX Lifecycle (ViewModel)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation(project(":core:designsystem"))

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.mockito:mockito-core:5.7.0")

    // Android Instrumentation Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
