plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.hilt)
}

android {
    namespace = "com.midnight.kuira.core.indexer"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

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
            isReturnDefaultValues = true // Mock android.util.Log and other Android APIs
            isIncludeAndroidResources = true // Enable Robolectric
        }
    }
}

dependencies {
    // Core modules
    implementation(project(":core:crypto"))
    implementation(project(":core:network"))

    // Core Android
    implementation(libs.androidx.core.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore for sync state persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Ktor for HTTP client (GraphQL over HTTP)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.websockets)

    // Kotlinx Serialization for JSON
    implementation(libs.kotlinx.serialization.json)

    // Room for local database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Hilt for dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // AndroidX Lifecycle (ViewModel)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("io.mockk:mockk:1.13.8")  // MockK for Kotlin
    testImplementation("io.ktor:ktor-client-mock:2.3.7")
    testImplementation(project(":core:testing"))

    // Robolectric for Room database tests
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")

    // Android Instrumentation Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(libs.kotlinx.coroutines.test)

    // OkHttp for integration tests (Indexer API calls)
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation(libs.kotlinx.serialization.json)
}
