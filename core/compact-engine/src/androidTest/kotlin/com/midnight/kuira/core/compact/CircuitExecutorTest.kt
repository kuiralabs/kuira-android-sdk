package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun executeCircuit_producesValidTxParams() = runBlocking {
        val testSecretKey = ByteArray(32) { (it + 1).toByte() }

        val result = executor.executeCircuit(
            contractJs = loadAsset("runtime/bboard-contract-iife.js"),
            contractAddress = "0".repeat(64),
            circuitName = "post",
            circuitArgs = listOf("'Params test'"),
            witnesses = mapOf("localSecretKey" to WitnessProvider { WitnessResult(null, testSecretKey) }),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )

        // Verify the tx params JSON has all required fields
        val params = org.json.JSONObject(result.txParamsJson)
        assertTrue("Should have initial_state_handle", params.has("initial_state_handle"))
        assertTrue("Should have state_handle", params.has("state_handle"))
        assertTrue("Should have proof_data", params.has("proof_data"))
        assertEquals("post", params.getString("entry_point"))
    }
}
