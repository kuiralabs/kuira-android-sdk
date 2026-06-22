plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.midnight.kuira.core.crypto"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // NDK configuration for JNI bridge
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-24"
                )
                cppFlags("")
            }
        }
    }

    // External native build: CMake links JNI C bridge + Rust static lib into .so
    externalNativeBuild {
        cmake {
            path = file("../../kuira-crypto-ffi/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
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
}

dependencies {
    // Contract SDK — proving + state classes moved there, typealiases here for compat
    api(project(":core:compact-engine"))

    // Core Android
    implementation(libs.androidx.core.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Crypto libraries
    // implementation(libs.kotlin.bip39)       // NOT BIP-39 compliant for seed derivation
    implementation(libs.bitcoinj.core)      // BitcoinJ - Java Bitcoin library (BIP-39 compliant)
    implementation(libs.secp256k1.kmp)      // secp256k1 curve operations

    // Annotations for thread safety documentation
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))

    // Android Instrumentation Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(project(":core:testing"))
}
