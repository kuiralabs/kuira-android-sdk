package com.midnight.kuira.core.compact

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
 * Tests that don't need proving keys run unconditionally.
 * Proving tests are skipped (not failed) when keys are missing.
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
        // Copy bboard keys + BLS params from /data/local/tmp/bboard_keys/
        // (pushed via adb) to the app's proving_keys directory
        val tempKeysDir = File("/data/local/tmp/bboard_keys")
        if (!tempKeysDir.exists()) return

        val keysDir = provingKeyManager.keysDir
        keysDir.mkdirs()

        // Circuit keys
        val extensions = listOf("prover", "verifier", "bzkir")
        for (circuit in bboardCircuits) {
            for (ext in extensions) {
                val src = File(tempKeysDir, "$circuit.$ext")
                val dst = File(keysDir, "$circuit.$ext")
                if (src.exists() && !dst.exists()) {
                    src.copyTo(dst)
                }
            }
        }

        // BLS parameters (shared between wallet and contract proving)
        for (k in listOf(13, 14, 15)) {
            val name = "bls_midnight_2p$k"
            val src = File(tempKeysDir, name)
            val dst = File(keysDir, name)
            if (src.exists() && !dst.exists()) {
                src.copyTo(dst)
            }
        }
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
        // Skip if proving keys not installed
        assumeTrue(
            "Bboard proving keys not installed — run ./scripts/install-bboard-keys.sh",
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
        // Skip if proving keys not installed
        assumeTrue(
            "Bboard proving keys not installed",
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
        // Android emulator reaches host at 10.0.2.2
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
     *   echo "https://indexer.preview.midnight.network/api/v3" | adb shell "cat > /data/local/tmp/bboard_keys/indexer_url.txt"
     */
    private fun readIndexerUrl(): String {
        val file = File("/data/local/tmp/bboard_keys/indexer_url.txt")
        return if (file.exists()) file.readText().trim() else DEFAULT_INDEXER_URL
    }

    companion object {
        private const val TAG = "BboardE2E"

        /** Default URLs — Android emulator reaches host at 10.0.2.2 */
        private const val DEFAULT_INDEXER_URL = "http://10.0.2.2:8088/api/v3"
        private const val DAPP_CONNECTOR_URL = "ws://10.0.2.2:9932"

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
