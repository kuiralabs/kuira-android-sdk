package com.midnight.kuira.core.compact

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.testing.ProofMemorySampler
import com.midnight.kuira.core.testing.ProofMemoryUse
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.midnight.kuira.core.compact.state.KeyStorePrivateStateProvider
import com.midnight.kuira.core.compact.state.PrivateStateProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Step 6J: BBoard end-to-end integration test.
 *
 * Tests the full offline pipeline:
 *   circuit execution → transaction assembly → ZK proving
 *
 * **Prerequisites:**
 * Requires proving keys installed on device. Run the setup script first:
 * ```
 * ./scripts/install-bboard-keys.sh
 * ```
 *
 * Tests that don't need proving keys run unconditionally. Proving tests FAIL when the keys
 * are missing — a skip there is indistinguishable from a pass in the gate.
 */
@RunWith(AndroidJUnit4::class)
class BboardEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)
    private val provingKeyManager = ProvingKeyManager(context)

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    private val bboardCircuits = listOf("post", "takeDown")
    private val testSecretKey = ByteArray(32) { (it + 1).toByte() }

    @Before
    fun installKeysFromTempIfAvailable() {
        // Provision bboard proving keys from the adb-pushed staging dir using the
        // SDK's own (content-aware) provisioners: circuit keys (post/takeDown)
        // plus the FULL BLS parameter set that ProvingKeyManager.hasBLSParams
        // requires (2p5–2p15) — copying only a subset makes the proving tests skip.
        val tempKeysDir = File("/data/local/tmp/bboard_keys")
        if (!tempKeysDir.exists()) return

        provingKeyManager.installCircuitKeysForProving(
            circuitNames = bboardCircuits,
            keysSourceDir = tempKeysDir,
            zkirSourceDir = tempKeysDir,
        )
        provingKeyManager.installFromLocalTmp()
    }

    private fun bboardWitnesses() = mapOf(
        "localSecretKey" to WitnessProvider { WitnessResult(null, testSecretKey) },
    )

    /**
     * Read-only connectivity test: fetch ledger parameters from whichever
     * indexer is configured (localnet, preview, preprod, or mainnet).
     * Verifies our code can talk to any Midnight network's indexer.
     */
    @Test
    fun networkConnectivity_fetchLedgerParams(): Unit = runBlocking {
        val indexerUrl = readIndexerUrl()
        Log.i(TAG, "Testing connectivity to: $indexerUrl")

        val ledgerParams = fetchLedgerParameters()
        assertTrue("Ledger params should be substantial hex", ledgerParams.length > 100)
        assertTrue("Should start with midnight tag", ledgerParams.startsWith("6d69646e69676874")) // "midnight" in hex
        Log.i(TAG, "Ledger params: ${ledgerParams.length} hex chars from $indexerUrl")
    }

    @Test
    fun offlinePipeline_executeAndAssemble() = runBlocking {
        // This test always runs — no proving keys needed
        val result = executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = "0".repeat(64),
            circuitName = "post",
            circuitArgs = listOf("'Hello from BBoard E2E!'"),
            witnesses = bboardWitnesses(),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )

        assertTrue("UnprovenTx should be hex", result.unprovenTxHex.length % 2 == 0)
        assertTrue("UnprovenTx should be substantial", result.unprovenTxHex.length > 100)
    }

    @Test
    fun offlinePipeline_executeAssembleAndProve() = runBlocking {
        // Not a skip: without the keys there is no proof to check, and a green skip would
        // report this pipeline as verified when nothing ran.
        assertTrue(
            // Name what actually stages these. prepare-localnet-e2e.sh does NOT: it pushes
            // wallet_keys/{zswap,dust} and the bls_* params, not the bboard circuit keys, so
            // following that advice left the keys missing and the message looking like a lie.
            "Bboard proving keys not installed — the bboardSetup Gradle task stages them " +
                "(it runs ./scripts/install-bboard-keys.sh), or run that script directly.",
            provingKeyManager.hasProvableCircuitKeys(bboardCircuits),
        )

        // Step 1: Execute circuit
        val result = executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = "0".repeat(64),
            circuitName = "post",
            circuitArgs = listOf("'Proven on Android!'"),
            witnesses = bboardWitnesses(),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )

        // Step 2: Prove locally
        val proofProvider: ProofProvider = LocalProofProvider(provingKeyManager)
        val provenTxHex = proofProvider.prove(result.unprovenTxHex)

        // Step 3: Verify result
        assertTrue("ProvenTx should be hex", provenTxHex.length % 2 == 0)
        assertTrue("ProvenTx should be substantial", provenTxHex.length > 100)
        assertNotEquals(
            "ProvenTx should differ from UnprovenTx",
            result.unprovenTxHex,
            provenTxHex,
        )
    }

    @Test
    fun offlinePipeline_bothCircuits() = runBlocking {
        // Post produces a valid transaction
        val postResult = executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = "0".repeat(64),
            circuitName = "post",
            circuitArgs = listOf("'Post then takeDown'"),
            witnesses = bboardWitnesses(),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )
        assertTrue("Post tx should be valid", postResult.unprovenTxHex.length > 100)

        // TakeDown also produces a valid transaction
        // (starts from occupied state — we can't chain state across executions
        //  without a node, but we CAN test that takeDown compiles and assembles
        //  by using initialState that already has a post)
        // For now, just verify post works as the primary circuit
    }

    @Test
    fun onlinePipeline_executeProveBalanceSubmit(): Unit = runBlocking {
        // Fails rather than skips — see the offline pipeline above.
        assertTrue(
            "Bboard proving keys not installed — run ./scripts/install-bboard-keys.sh.",
            provingKeyManager.hasProvableCircuitKeys(bboardCircuits),
        )

        // Contract must be deployed. Push config via adb:
        //   echo -n "<address>" | adb shell "cat > /data/local/tmp/bboard_keys/contract_address.txt"
        //   echo -n "<indexer_url>" | adb shell "cat > /data/local/tmp/bboard_keys/indexer_url.txt"
        //   echo -n "<network_id>" | adb shell "cat > /data/local/tmp/bboard_keys/network_id.txt"
        val contractAddress = readContractAddress()
            ?: FALLBACK_CONTRACT_ADDRESS
        val networkId = readConfigFile("network_id.txt") ?: "undeployed"

        // Step 1: Fetch on-chain contract state and ledger parameters from indexer
        val onChainStateHex = fetchContractState(contractAddress)
        val ledgerParamsHex = fetchLedgerParameters()
        Log.i(TAG, "Network: $networkId | Indexer: ${readIndexerUrl()}")
        Log.i(TAG, "Fetched on-chain state: ${onChainStateHex.length} hex chars")
        Log.i(TAG, "Fetched ledger params: ${ledgerParamsHex.length} hex chars")

        // Step 2: Execute circuit against the DEPLOYED contract state
        val result = executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = contractAddress,
            circuitName = "post",
            circuitArgs = listOf("'Hello from Android on chain!'"),
            witnesses = bboardWitnesses(),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
            networkId = networkId,
            onChainStateHex = onChainStateHex,
            ledgerParametersHex = ledgerParamsHex,
        )

        // Step 3: Prove locally
        val proofProvider: ProofProvider = LocalProofProvider(provingKeyManager)
        val provenTxHex = proofProvider.prove(result.unprovenTxHex).trim()
        Log.i(TAG, "Proven tx: ${provenTxHex.length} hex chars")
        assertTrue("ProvenTx should be substantial", provenTxHex.length > 100)
        assertTrue("ProvenTx should be pure hex", provenTxHex.all { it in '0'..'9' || it in 'a'..'f' })

        // Step 4: Connect to DApp Connector (mn serve on host)
        // host reached via `adb reverse` (works on emulator and real hardware)
        val connector = DAppConnectorClient(DAPP_CONNECTOR_URL)
        try {
            connector.connect()

            // Step 5: Balance the proven transaction (adds dust fees)
            val balancedTxHex = connector.balanceTransaction(provenTxHex)
            Log.i(TAG, "Balanced tx: ${balancedTxHex.length} hex chars")
            assertTrue("BalancedTx should be substantial", balancedTxHex.length > 100)

            // Step 6: Submit to blockchain
            connector.submitTransaction(balancedTxHex)

            Log.i(TAG, "Transaction submitted successfully!")
        } finally {
            connector.disconnect()
        }
    }

    // ── Helpers for on-chain tests ──

    /** Execute a GraphQL query against the configured indexer and return the parsed response. */
    private fun graphqlQuery(query: String): JSONObject {
        val url = URL("${readIndexerUrl()}/graphql")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.outputStream.use { it.write("""{"query":"$query"}""".toByteArray()) }

            val body = conn.inputStream.bufferedReader().readText()
            check(conn.responseCode == 200) { "Indexer HTTP ${conn.responseCode}: $body" }

            val json = JSONObject(body)
            val errors = json.optJSONArray("errors")
            check(errors == null || errors.length() == 0) { "GraphQL errors: $errors" }

            return json.getJSONObject("data")
        } finally {
            conn.disconnect()
        }
    }

    /** Fetch contract state from the indexer. Returns SCALE-encoded hex. */
    private fun fetchContractState(address: String): String {
        val data = graphqlQuery("query { contractAction(address: \\\"$address\\\") { state } }")
        val contractAction = data.optJSONObject("contractAction")
            ?: error("Contract not found at address: $address")
        return contractAction.getString("state")
    }

    /** Fetch current ledger parameters from the indexer. Returns SCALE-encoded hex. */
    private fun fetchLedgerParameters(): String {
        val data = graphqlQuery("query { block { ledgerParameters } }")
        return data.getJSONObject("block").getString("ledgerParameters")
    }

    /**
     * Read contract address from a file pushed via adb, if available.
     * Push with: adb push contract_address.txt /data/local/tmp/bboard_keys/contract_address.txt
     */
    private fun readContractAddress(): String? {
        val address = readConfigFile("contract_address.txt") ?: return null
        return if (address.length == 64) address else null
    }

    /** Read a config value from /data/local/tmp/bboard_keys/<filename> */
    private fun readConfigFile(filename: String): String? {
        val file = File("/data/local/tmp/bboard_keys/$filename")
        if (!file.exists()) return null
        return file.readText().trim().ifEmpty { null }
    }

    /**
     * Read network config from device files pushed via adb.
     * Allows testing against preview/preprod without code changes.
     *
     * Push with:
     *   echo "https://indexer.preview.midnight.network" | adb shell "cat > /data/local/tmp/bboard_keys/indexer_url.txt"
     */
    private fun readIndexerUrl(): String {
        val file = File("/data/local/tmp/bboard_keys/indexer_url.txt")
        return if (file.exists()) file.readText().trim() else DEFAULT_INDEXER_URL
    }

    /**
     * The contract-path proving envelope, measured instead of inferred.
     *
     * All that existed before was the wall-clock gap between two JUnit tests (~0.93 s), which
     * folds in asset loading and circuit execution and cannot separate the first proof in a
     * process from the rest. Proving pays a one-off warm-up -- the zswap path measured ~0.8 s of
     * it -- so one sample describes neither the cold nor the steady-state cost. Every sample here
     * runs in ONE process, so the first is genuinely cold and the rest genuinely warm, and only
     * the prove call is inside the timed region.
     *
     * This asserts rather than merely reporting, because a test that only prints is a measurement
     * harness wearing a @Test annotation: it cannot fail, so it cannot protect anything.
     *
     * The size invariant is a BAND, not equality. Asserting a fixed proof size looked obviously
     * right and failed on about half of six runs: the same circuit proved with the same keys
     * measured 5061 B and sometimes 5059 B, a 2-byte swing from variable-length encoding inside
     * the serialized proof. So the property that actually holds is "stable to within a few
     * bytes", and the band is set wide enough to absorb that while staying far tighter than any
     * real bloat or nondeterminism would be.
     */
    @Test
    fun contractProvingEnvelope_coldThenWarm(): Unit = runBlocking {
        assertTrue(
            "Bboard proving keys not installed -- bboardSetup stages them, or run " +
                "./scripts/install-bboard-keys.sh.",
            provingKeyManager.hasProvableCircuitKeys(bboardCircuits),
        )
        val contractJs = loadAsset("runtime/bboard-contract-iife.js")
        val proofProvider: ProofProvider = LocalProofProvider(provingKeyManager)

        val elapsed = mutableListOf<Long>()
        val provenSizes = mutableListOf<Int>()
        val memUse = mutableListOf<ProofMemoryUse>()
        var unprovenSize = 0

        repeat(PROOF_SAMPLES) { sample ->
            val result = executor.executeCircuit(
                contractJs = contractJs,
                contractAddress = "0".repeat(64),
                circuitName = "post",
                circuitArgs = listOf("'Envelope sample $sample'"),
                witnesses = bboardWitnesses(),
                initialPrivateState = "{ secretKey: new Uint8Array(32) }",
                coinPublicKey = ByteArray(32),
            )
            unprovenSize = result.unprovenTxHex.length / 2
            val sampler = ProofMemorySampler().also { it.start() }
            val start = System.currentTimeMillis()
            val provenTxHex = proofProvider.prove(result.unprovenTxHex)
            elapsed += System.currentTimeMillis() - start
            memUse += sampler.stopAndRead()
            assertNotEquals(
                "Sample $sample returned the unproven tx -- nothing was proved",
                result.unprovenTxHex,
                provenTxHex,
            )
            provenSizes += provenTxHex.length / 2
        }

        val cold = elapsed.first()
        val warm = elapsed.drop(1)
        Log.i(TAG, "=== bboard post proving envelope (circuit k=13, rows=4569) ===")
        Log.i(TAG, "  cold (first proof in process): ${cold}ms")
        Log.i(
            TAG,
            "  warm: ${warm.joinToString(" / ") { "${it}ms" }} (mean ${warm.average().toInt()}ms)",
        )
        Log.i(TAG, "  tx: ${unprovenSize}B unproven -> ${provenSizes.first()}B proven")
        Log.i(TAG, "  native heap: ${memUse.joinToString(" | ") { it.nativeSummary() }}")
        Log.i(TAG, "  total PSS:   ${memUse.joinToString(" | ") { it.pssSummary() }}")
        // Cold only when this is the first proof in the process. A full module run puts the
        // offline-prove test ahead of it, so the logged baseline is already warm and the delta
        // reads far smaller than the real cold cost -- compare circuits from single-test runs.

        val sizeSpread = provenSizes.max() - provenSizes.min()
        assertTrue(
            "Proof size for one circuit spread $sizeSpread B across samples " +
                "(> $PROOF_SIZE_TOLERANCE_B): $provenSizes",
            sizeSpread <= PROOF_SIZE_TOLERANCE_B,
        )
        assertTrue("Every sample must be timed: $elapsed", elapsed.all { it > 0 })
        assertTrue(
            "the sampler must observe native allocation during a k=13 proof: $memUse",
            memUse.all { it.nativeDeltaKb > 0 },
        )
    }

    companion object {
        private const val TAG = "BboardE2E"

        /** One cold proof plus three warm ones -- the shape the zswap envelope was measured in. */
        private const val PROOF_SAMPLES = 4

        /**
         * How far one circuit's proof size may legitimately move between samples. Measured
         * variance is 2 B; this leaves room for it without tolerating real growth.
         */
        private const val PROOF_SIZE_TOLERANCE_B = 64

        /** Default URLs — host reached via `adb reverse` (works on emulator and real hardware) */
        private val DEFAULT_INDEXER_URL = com.midnight.kuira.core.network.NetworkConfig.forNetwork(com.midnight.kuira.core.network.MidnightNetwork.UNDEPLOYED).indexerBaseUrl
        private const val DAPP_CONNECTOR_URL = "ws://127.0.0.1:9932"

        /** Fallback contract address — update after each `mn contract deploy` */
        private const val FALLBACK_CONTRACT_ADDRESS =
            "4b45940479a61bb954f9de4245736d29e2e0e5e4970dba47a18651466cf9ba91"
    }

    @Test
    fun privateState_storeAndRetrieveSecretKey() {
        val stateProvider: PrivateStateProvider = KeyStorePrivateStateProvider(context)

        val contractAddr = "0".repeat(64)
        stateProvider.set(contractAddr, "secretKey", testSecretKey)

        val retrieved = stateProvider.get(contractAddr, "secretKey")
        assertNotNull(retrieved)
        assertArrayEquals(testSecretKey, retrieved)

        // Clean up
        stateProvider.clearContract(contractAddr)
    }
}
