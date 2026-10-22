import java.net.InetSocketAddress
import java.net.Socket

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

// RuntimeShimBundleSyncTest compares the two runtime assets by reading them straight off disk,
// which Gradle cannot see: the task declared no dependency on those files, so after an
// asset-only edit it reported UP-TO-DATE and the check never ran -- exactly the change it exists
// to catch, passing green. A negative control caught it; the guard needed a guard. Declaring the
// directory as an input is what makes an asset edit re-run the test.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/main/assets/runtime"))
        .withPropertyName("runtimeAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// ── BBoard E2E test setup ──
// Automatically prepares localnet infrastructure before connected tests.
// Requires: mn CLI installed, Docker running, Android emulator connected.

// Node, indexer, proof server and the dApp connector. Forwarded to the device so the e2e
// fixture can address the host as 127.0.0.1 from an emulator or real hardware alike.
val LOCALNET_PORTS = listOf(9944, 8088, 6300, 9932)

// Every contract this task deploys, the instrumented class that consumes it, and the test method
// it belongs to — in ONE place, because two separate things read it: the deploy step below, and
// the decision about whether a given instrumented run needs a chain at all.
//
// A chain-dependent test cannot exist without an entry here: the bboard `post` circuit asserts a
// VACANT board, so each board-touching test needs its own freshly deployed contract. That is what
// keeps this list and the set of chain-dependent classes in step as tests are added.
class ChainContract(val deviceFile: String, val testClass: String, val testMethod: String)

val COMPACT_TEST_PKG = "com.midnight.kuira.core.compact"
val CHAIN_CONTRACTS = listOf(
    ChainContract("contract_address.txt", "$COMPACT_TEST_PKG.BboardEndToEndTest", "onlinePipeline"),
    ChainContract("contract_address_post1.txt", "$COMPACT_TEST_PKG.MidnightContractTest", "callPost_oneLiner"),
    ChainContract("contract_address_post2.txt", "$COMPACT_TEST_PKG.MidnightContractTest", "callPost_withProgress"),
    ChainContract("contract_address_prepare.txt", "$COMPACT_TEST_PKG.MidnightContractTest", "prepare_offlineMode"),
)
val CHAIN_TEST_CLASSES = CHAIN_CONTRACTS.map { it.testClass }.toSet()

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
            // An error response used to be SWALLOWED: the guard skipped the throw whenever the
            // body contained "error", so a refused airdrop or dust registration returned quietly
            // and the task logged "Airdropping…" / "Registering dust…" and carried on as if they
            // had worked. The first real symptom then appeared several steps later, somewhere
            // unrelated. A non-zero exit is a failure whatever the body says.
            if (exit != 0) {
                throw GradleException("mn ${args.joinToString(" ")} failed (exit $exit): $output")
            }
            return output
        }

        fun adb(vararg args: String): String {
            val proc = ProcessBuilder("adb", *args)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            // The exit code used to be discarded. Every call here changes device state that the
            // tests then read — mkdir, push of the contract/indexer config, reverse of the
            // localnet ports — so a failure meant the task logged "BBoard e2e setup complete!"
            // while the device kept whatever stale config it already had. That is how a
            // contract address and an indexer URL from a previous, emulator-era run survived
            // on the device and sent the tests at 10.0.2.2 and a contract that no longer existed.
            val exit = proc.waitFor()
            if (exit != 0) {
                throw GradleException("adb ${args.joinToString(" ")} failed (exit $exit): $output")
            }
            return output
        }

        fun jsonField(json: String, field: String): String? {
            val regex = """"$field"\s*:\s*"([^"]+)"""".toRegex()
            return regex.find(json)?.groupValues?.get(1)
        }

        val network = "undeployed"
        // The default was "${rootProject.projectDir}/../../midnight/…", which is one level too
        // shallow: the repo sits at <dev>/android/projects/kuira-android-wallet, so ../.. lands on
        // <dev>/android, not <dev>. The checkout was never found and bboardSetup always failed with
        // "set BBOARD_DIR" even on machines that had it. Search the plausible roots instead of
        // pinning one relative depth, and say which ones were tried when none match.
        val bboardCandidates = listOfNotNull(
            System.getenv("BBOARD_DIR")?.let(::file),
            file("${rootProject.projectDir}/../../../midnight/midnight-libraries/example-bboard"),
            file("${System.getProperty("user.home")}/Development/midnight/midnight-libraries/example-bboard"),
        )
        val bboardDir = bboardCandidates.firstOrNull { it.exists() }
            ?: throw GradleException(
                "example-bboard not found. Set the BBOARD_DIR env var to your example-bboard " +
                    "checkout. Tried:\n" + bboardCandidates.joinToString("\n") { "  - $it" }
            )

        // 1. Check localnet — by PROBING the ports, not by asking `mn localnet status`.
        //
        // `mn localnet status` reports a stale "running" when the containers are gone, and
        // reports nothing useful for a stack it did not start itself (a plain docker compose
        // one, say). Either way it answers a different question than "can I reach the chain",
        // which is the only thing this task needs. scripts/e2e/prepare-localnet-e2e.sh already
        // probes for exactly this reason; this now matches it.
        logger.lifecycle("Probing localnet (node 9944, indexer 8088)...")
        fun portOpen(port: Int): Boolean = try {
            Socket().use { sock -> sock.connect(InetSocketAddress("127.0.0.1", port), 2000) }
            true
        } catch (_: Exception) {
            false
        }
        val closedPorts = listOf(9944, 8088).filterNot(::portOpen)
        if (closedPorts.isNotEmpty()) {
            throw GradleException(
                "Localnet not reachable on ${closedPorts.joinToString(", ")}. " +
                    "Start it (scripts/e2e/prepare-localnet-e2e.sh) before running the e2e suite."
            )
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

        // 5. Start the mn-serve DApp connector, and CONFIRM it is actually listening.
        //
        // This used to start the process, sleep 10s, and continue — never checking the result.
        // `mn serve` prints "Server ready — listening on ws://localhost:9932" BEFORE it binds,
        // so when a stale instance still holds the port it reports success and then dies with
        // "EADDRINUSE: address already in use". The contract-call tests then meet no connector,
        // and the only clue is several steps later. Probing the port is the only honest check:
        // the process's own output is not evidence that it bound.
        logger.lifecycle("Probing the DApp connector (9932)...")
        if (!portOpen(9932)) {
            logger.lifecycle("Starting mn serve...")
            ProcessBuilder("mn", "serve", "--approve-all", "--port", "9932", "--network", network)
                .redirectErrorStream(true)
                .start()
            // Poll rather than sleeping a fixed amount: it has a wallet to sync first, and a
            // fixed wait is either too short on a cold cache or wasted on a warm one.
            val connectorDeadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < connectorDeadline && !portOpen(9932)) {
                Thread.sleep(2_000)
            }
            if (!portOpen(9932)) {
                throw GradleException(
                    "mn serve did not start listening on 9932 within 120s. If a stale instance " +
                        "holds the port, the new one exits with EADDRINUSE after printing " +
                        "\"Server ready\" — check for an existing `mn serve` and stop it."
                )
            }
        }
        logger.lifecycle("DApp connector listening on 9932")

        // 6. Deploy fresh bboard contracts — one per board-touching test.
        //    The bboard `post` circuit requires a VACANT board, so two posts to
        //    the same contract conflict. A dedicated contract per test also keeps
        //    every test to exactly ONE mn-serve connector submit (the connector
        //    can't balance+submit twice in rapid succession). Deploys run through
        //    the `mn` CLI, which finalizes each before returning, so they don't
        //    contend with each other.
        fun deployFreshContract(label: String): String {
            logger.lifecycle("Deploying bboard contract ($label)...")
            val deploy = ProcessBuilder("mn", "contract", "deploy", "--network", network, "--json")
                .directory(bboardDir)
                .redirectErrorStream(true)
                .start()
            val output = deploy.inputStream.bufferedReader().readText().trim()
            val exit = deploy.waitFor()
            val address = jsonField(output, "address")
                ?: throw GradleException("Deploy ($label) failed (exit $exit): $output")
            logger.lifecycle("Deployed bboard ($label) at: $address")
            return address
        }

        val deployedContracts = CHAIN_CONTRACTS.map { it.deviceFile to deployFreshContract(it.testMethod) }

        // 7. Push config to device via temp files (avoids shell quoting issues)
        logger.lifecycle("Pushing config to device...")
        adb("shell", "mkdir", "-p", "/data/local/tmp/bboard_keys")

        fun pushConfig(filename: String, content: String) {
            val tmp = File.createTempFile("bboard_", ".txt")
            tmp.writeText(content)
            adb("push", tmp.absolutePath, "/data/local/tmp/bboard_keys/$filename")
            tmp.delete()
        }
        deployedContracts.forEach { (file, address) -> pushConfig(file, address) }
        // Read the indexer API path from NetworkConfig.kt so this gradle file
        // doesn't have to be edited on a version bump. The Kotlin sibling and
        // this task push the same string into /data/local/tmp/.
        val networkConfigSrc = file("${rootProject.projectDir}/core/network/src/main/kotlin/com/midnight/kuira/core/network/NetworkConfig.kt")
        val apiPath = Regex("""INDEXER_API_PATH\s*=\s*"([^"]+)"""")
            .find(networkConfigSrc.readText())
            ?.groupValues
            ?.get(1)
            ?: throw GradleException("Couldn't extract INDEXER_API_PATH from ${networkConfigSrc.absolutePath}")
        // 10.0.2.2 is the EMULATOR's alias for the host and means nothing on a real phone,
        // which is why these tests could only ever run on an emulator. Forward the localnet
        // ports instead and address them as 127.0.0.1: `adb reverse` is an adb feature, not
        // an emulator one, so the same URL works on both.
        LOCALNET_PORTS.forEach { port -> adb("reverse", "tcp:$port", "tcp:$port") }
        logger.lifecycle("Forwarded localnet ports ${LOCALNET_PORTS.joinToString(", ")} to the device")

        pushConfig("indexer_url.txt", "http://127.0.0.1:8088$apiPath")
        pushConfig("network_id.txt", network)

        logger.lifecycle("BBoard e2e setup complete!")
    }
}

// ── Wire setup to run before connected tests, but only when the run actually needs a chain ──
//
// Only the CHAIN_CONTRACTS classes submit through the connector. The other eighteen instrumented
// classes drive QuickJS, circuit execution and local proving with no node, indexer, proof server or
// deployed contract anywhere in sight — yet every one of them inherited `bboardSetup`, so editing a
// runtime-shim test meant standing up a localnet and deploying four contracts first.
// `-x :core:compact-engine:bboardSetup` worked, but a workaround you have to already know about is
// not a fix. Read the instrumentation filter the developer has typed anyway, and decide from it.
//
// The unfiltered run — what the full gate runs — still provisions everything, so nothing is traded
// away here. Where a filter is ambiguous this provisions as well: that way round costs setup time,
// whereas guessing the other way would fail a chain test on a missing precondition.
val needsChainProvisioning: Boolean = run {
    fun runnerArg(key: String): String? =
        (project.findProperty("android.testInstrumentationRunnerArguments.$key") as? String)
            ?.takeIf { it.isNotBlank() }

    // "Class", "Class#method" and comma-separated lists are all valid AGP filter syntax.
    fun filterEntries(arg: String): List<String> =
        arg.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val classFilter = runnerArg("class")
    val notClassFilter = runnerArg("notClass")
    val packageFilter = runnerArg("package")
    when {
        classFilter != null ->
            filterEntries(classFilter).any { it.substringBefore('#') in CHAIN_TEST_CLASSES }
        // A `Class#method` exclusion still runs the rest of that class, so only a bare class name
        // takes the whole class out of the run.
        notClassFilter != null &&
            filterEntries(notClassFilter).filterNot { it.contains('#') }.toSet()
                .containsAll(CHAIN_TEST_CLASSES) -> false
        packageFilter != null -> CHAIN_TEST_CLASSES.any { it.startsWith("$packageFilter.") }
        else -> true
    }
}

tasks.matching { it.name.startsWith("connected") && it.name.contains("AndroidTest") }.configureEach {
    if (needsChainProvisioning) {
        dependsOn(bboardSetup)
    } else {
        doFirst {
            logger.lifecycle(
                "bboardSetup not required: this run's filter excludes every class that needs a " +
                    "chain (${CHAIN_TEST_CLASSES.joinToString(", ") { it.substringAfterLast('.') }}). " +
                    "Run the module unfiltered for the full gate."
            )
        }
    }
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
    // Instrumented tests load the native FFI via System.loadLibrary("kuira_crypto_ffi").
    // The main artifact relies on the consuming app to supply that .so, so the
    // androidTest APK has no source for it — pull core:crypto (which builds the .so
    // from kuira-crypto-ffi's CMake) into the test APK only. Test-scoped, so the
    // core:crypto → core:compact-engine api edge doesn't form a main-variant cycle.
    androidTestImplementation(project(":core:crypto"))
}
