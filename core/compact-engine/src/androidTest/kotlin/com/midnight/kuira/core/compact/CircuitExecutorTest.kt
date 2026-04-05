package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.proving.ProvingKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 6H: Tests for CircuitExecutor — the clean API for dApp developers.
 *
 * These tests verify that CircuitExecutor wraps all the QuickJS + FFI
 * boilerplate into a single call that produces an UnprovenTransaction.
 */
@RunWith(AndroidJUnit4::class)
class CircuitExecutorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    @Test
    fun executeCircuit_bboardPost_producesUnprovenTx() = runBlocking {
        val testSecretKey = ByteArray(32) { (it + 1).toByte() }

        val result = executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = "0".repeat(64),
            circuitName = "post",
            circuitArgs = listOf("'Hello from CircuitExecutor!'"),
            witnesses = mapOf(
                "localSecretKey" to WitnessProvider { WitnessResult(null, testSecretKey) },
            ),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )

        // Should produce valid hex-encoded SCALE bytes
        assertTrue("Should be hex (even length)", result.unprovenTxHex.length % 2 == 0)
        assertTrue("Should be substantial", result.unprovenTxHex.length > 100)
        assertFalse("Should not be error", result.unprovenTxHex.startsWith("{\"error"))
    }

    @Test
    fun executeCircuit_bboardPost_differentMessages() = runBlocking {
        val testSecretKey = ByteArray(32) { (it + 1).toByte() }

        val result1 = executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = "0".repeat(64),
            circuitName = "post",
            circuitArgs = listOf("'Message A'"),
            witnesses = mapOf("localSecretKey" to WitnessProvider { WitnessResult(null, testSecretKey) }),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )

        val result2 = executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = "0".repeat(64),
            circuitName = "post",
            circuitArgs = listOf("'Message B'"),
            witnesses = mapOf("localSecretKey" to WitnessProvider { WitnessResult(null, testSecretKey) }),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )

        // Different messages should produce different transactions
        assertNotEquals(
            "Different messages should produce different txs",
            result1.unprovenTxHex,
            result2.unprovenTxHex,
        )
    }

    @Test(expected = CircuitExecutionException::class)
    fun executeCircuit_invalidCircuitName_throws() = runBlocking {
        val testSecretKey = ByteArray(32) { (it + 1).toByte() }

        executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = "0".repeat(64),
            circuitName = "nonexistent",
            witnesses = mapOf("localSecretKey" to WitnessProvider { WitnessResult(null, testSecretKey) }),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )
        Unit
    }

    @Test
    fun executeCircuit_witnessIsCalledDuringExecution() = runBlocking {
        var witnessCalled = false
        val testSecretKey = ByteArray(32) { (it + 1).toByte() }

        executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = "0".repeat(64),
            circuitName = "post",
            circuitArgs = listOf("'Witness test'"),
            witnesses = mapOf(
                "localSecretKey" to WitnessProvider {
                    witnessCalled = true
                    WitnessResult(null, testSecretKey)
                },
            ),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )

        assertTrue("Witness should have been called", witnessCalled)
    }

    @Test
    fun fullPipeline_executeAndProve() = runBlocking {
        val testSecretKey = ByteArray(32) { (it + 1).toByte() }

        // Step 1: Execute circuit → UnprovenTransaction
        val executionResult = executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = "0".repeat(64),
            circuitName = "post",
            circuitArgs = listOf("'Full pipeline test'"),
            witnesses = mapOf("localSecretKey" to WitnessProvider { WitnessResult(null, testSecretKey) }),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )

        assertTrue("Should have unproven tx", executionResult.unprovenTxHex.length > 100)

        // Step 2: Try to prove
        val provingKeyManager = ProvingKeyManager(context)
        val proofProvider: ProofProvider = LocalProofProvider(provingKeyManager)

        if (provingKeyManager.hasWalletKeys()) {
            // Keys available — do the full prove
            val provenTxHex = proofProvider.prove(executionResult.unprovenTxHex)
            assertTrue("Proven tx should be hex", provenTxHex.length % 2 == 0)
            assertTrue("Proven tx should be substantial", provenTxHex.length > 100)
        } else {
            // Keys not downloaded — verify the error is about missing keys, not structural
            try {
                proofProvider.prove(executionResult.unprovenTxHex)
                fail("Should throw ProvingException when keys missing")
            } catch (e: ProvingException) {
                assertTrue(
                    "Error should mention proving keys: ${e.message}",
                    e.message!!.contains("Proving keys"),
                )
            }
        }
    }
}
