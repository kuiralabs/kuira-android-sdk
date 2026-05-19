plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.midnight.kuira.core.compact"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

// ── BBoard E2E test setup ──
// Automatically prepares localnet infrastructure before connected tests.
// Requires: mn CLI installed, Docker running, Android emulator connected.

val bboardSetup by tasks.registering {
    group = "verification"
    description = "Set up localnet + deploy bboard contract for e2e tests"

    doLast {
        fun mn(vararg args: String): String {
            val proc = ProcessBuilder("mn", *args, "--json")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exit = proc.waitFor()
            if (exit != 0 && !output.contains("\"error\"")) {
                throw GradleException("mn ${args.joinToString(" ")} failed (exit $exit): $output")
            }
            return output
        }

        fun adb(vararg args: String): String {
            val proc = ProcessBuilder("adb", *args)
                .redirectErrorStream(true)
                .start()
            return proc.inputStream.bufferedReader().readText().trim()
                .also { proc.waitFor() }
        }

        fun jsonField(json: String, field: String): String? {
            val regex = """"$field"\s*:\s*"([^"]+)"""".toRegex()
            return regex.find(json)?.groupValues?.get(1)
        }

        val network = "undeployed"
        val home = System.getProperty("user.home")
        val bboardDir = listOf(
            file("$home/Development/midnight/midnight-libraries/example-bboard"),
            file("${rootProject.projectDir}/../../midnight/midnight-libraries/example-bboard"),
        ).firstOrNull { it.exists() }
            ?: throw GradleException(
                "example-bboard not found. Set BBOARD_DIR env or clone to ~/Development/midnight/midnight-libraries/example-bboard"
            )

        // 1. Check localnet
        logger.lifecycle("Checking localnet status...")
        val status = mn("localnet", "status")
        if (!status.contains("running")) {
            throw GradleException("Localnet not running. Start with: mn localnet up")
        }

        // 2. Ensure wallet exists
        logger.lifecycle("Checking wallet...")
        try {
            mn("wallet", "use", "dev")
        } catch (_: Exception) {
            logger.lifecycle("Creating dev wallet...")
            mn("wallet", "generate", "dev", "--network", network)
        }

        // 3. Check balance, airdrop if needed
        logger.lifecycle("Checking balance...")
        val balance = mn("balance", "--network", network)
        val nightBalance = jsonField(balance, "NIGHT")?.toDoubleOrNull() ?: 0.0
        if (nightBalance < 1.0) {
            logger.lifecycle("Airdropping 10000 NIGHT...")
            mn("airdrop", "10000", "--network", network)
        }

        // 4. Check dust, register if needed
        logger.lifecycle("Checking dust...")
        val dust = mn("dust", "status", "--network", network)
        val dustAvailable = dust.contains("\"dustAvailable\":true")
        if (!dustAvailable) {
            logger.lifecycle("Registering dust (this takes a few minutes)...")
            mn("dust", "register", "--network", network)
        }

        // 5. Start mn serve if not running
        logger.lifecycle("Checking mn serve...")
        try {
            val lsof = ProcessBuilder("lsof", "-ti", ":9932")
                .redirectErrorStream(true).start()
            val pid = lsof.inputStream.bufferedReader().readText().trim()
            lsof.waitFor()
            if (pid.isEmpty()) throw Exception("not running")
        } catch (_: Exception) {
            logger.lifecycle("Starting mn serve...")
            ProcessBuilder("mn", "serve", "--approve-all", "--port", "9932", "--network", network)
                .redirectErrorStream(true)
                .start()
            Thread.sleep(10_000) // Wait for sync
        }

        // 6. Deploy fresh bboard contract
        logger.lifecycle("Deploying bboard contract...")
        val deploy = ProcessBuilder("mn", "contract", "deploy", "--network", network, "--json")
            .directory(bboardDir)
            .redirectErrorStream(true)
            .start()
        val deployOutput = deploy.inputStream.bufferedReader().readText().trim()
        val deployExit = deploy.waitFor()
        val contractAddress = jsonField(deployOutput, "address")
            ?: throw GradleException("Deploy failed (exit $deployExit): $deployOutput")
        logger.lifecycle("Deployed bboard at: $contractAddress")

        // 7. Push config to device via temp files (avoids shell quoting issues)
        logger.lifecycle("Pushing config to device...")
        adb("shell", "mkdir", "-p", "/data/local/tmp/bboard_keys")

        fun pushConfig(filename: String, content: String) {
            val tmp = File.createTempFile("bboard_", ".txt")
            tmp.writeText(content)
            adb("push", tmp.absolutePath, "/data/local/tmp/bboard_keys/$filename")
            tmp.delete()
        }
        pushConfig("contract_address.txt", contractAddress)
        // Read the indexer API path from NetworkConfig.kt so this gradle file
        // doesn't have to be edited on a version bump. The Kotlin sibling and
        // this task push the same string into /data/local/tmp/.
        val networkConfigSrc = file("${rootProject.projectDir}/core/network/src/main/kotlin/com/midnight/kuira/core/network/NetworkConfig.kt")
        val apiPath = Regex("""INDEXER_API_PATH\s*=\s*"([^"]+)"""")
            .find(networkConfigSrc.readText())
            ?.groupValues
            ?.get(1)
            ?: throw GradleException("Couldn't extract INDEXER_API_PATH from ${networkConfigSrc.absolutePath}")
        pushConfig("indexer_url.txt", "http://10.0.2.2:8088$apiPath")
        pushConfig("network_id.txt", network)

        logger.lifecycle("BBoard e2e setup complete!")
    }
}

// Wire setup to run before connected tests
tasks.matching { it.name.startsWith("connected") && it.name.contains("AndroidTest") }.configureEach {
    dependsOn(bboardSetup)
}

dependencies {
    // QuickJS engine
    implementation(libs.quickjs.kt)

    // Network — exposes the canonical indexer URL (NetworkConfig.forNetwork)
    // so MidnightConfig.localDev doesn't bake the version into a string.
    implementation(project(":core:network"))

    // Proving + state classes are in this module directly.
    // Native .so comes from core:crypto's CMake (in-project) or jniLibs (standalone AAR).

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // WebSocket client for DApp Connector (balance + submit)
    implementation(libs.java.websocket)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // mockk for ProvingKeyManager helper tests (ensureWalletKeysAvailable
    // pins the local-tmp → S3 fallback contract with mocked PKM internals).
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
