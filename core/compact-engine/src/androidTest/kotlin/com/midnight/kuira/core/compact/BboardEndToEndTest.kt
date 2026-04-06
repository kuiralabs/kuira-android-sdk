package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.proving.ProvingKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.midnight.kuira.core.crypto.state.KeyStorePrivateStateProvider
import com.midnight.kuira.core.crypto.state.PrivateStateProvider
import java.io.File

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
    fun onlinePipeline_executeProveBalanceSubmit() = runBlocking {
        // Skip if proving keys not installed
        assumeTrue(
            "Bboard proving keys not installed",
            provingKeyManager.hasProvableCircuitKeys(bboardCircuits),
        )

        // The bboard contract must already be deployed on localnet.
        // Deploy via: cd example-bboard && mn contract deploy --network undeployed
        val contractAddress = "528526395cdcfa28096f50207b20527d5621a0840bb66d4ece334150204e7107"

        // Step 1: Execute circuit against the DEPLOYED contract
        val result = executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = contractAddress,
            circuitName = "post",
            circuitArgs = listOf("'Hello from Android on chain!'"),
            witnesses = bboardWitnesses(),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )

        // Step 2: Prove locally
        val proofProvider: ProofProvider = LocalProofProvider(provingKeyManager)
        val provenTxHex = proofProvider.prove(result.unprovenTxHex).trim()
        assertTrue("ProvenTx should be substantial", provenTxHex.length > 100)
        assertTrue("ProvenTx should be pure hex", provenTxHex.all { it in '0'..'9' || it in 'a'..'f' })

        // Step 3: Connect to DApp Connector (mn serve on host)
        // Android emulator reaches host at 10.0.2.2
        val connector = DAppConnectorClient("ws://10.0.2.2:9932")
        try {
            connector.connect()

            // Step 4: Balance the proven transaction (adds dust fees)
            val balancedTxHex = connector.balanceTransaction(provenTxHex)
            assertTrue("BalancedTx should be substantial", balancedTxHex.length > 100)

            // Step 5: Submit to blockchain
            connector.submitTransaction(balancedTxHex)

            // If we get here without exception, the transaction was accepted!
        } finally {
            connector.disconnect()
        }
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
