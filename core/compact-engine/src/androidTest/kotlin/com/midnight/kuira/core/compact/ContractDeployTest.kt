package com.midnight.kuira.core.compact

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests contract deployment via CircuitExecutor.executeConstructor().
 *
 * Runs the BBoard contract's constructor in QuickJS and verifies the
 * deploy transaction assembles correctly with a valid contract address.
 *
 * This test runs on-device (emulator) but does NOT require network
 * or proving keys — it only tests constructor execution + tx assembly.
 */
@RunWith(AndroidJUnit4::class)
class ContractDeployTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)

    private val testSecretKey = ByteArray(32) { (it + 1).toByte() }

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    @Test
    fun executeConstructor_producesDeployTx() = runBlocking {
        val contractJs = loadAsset("runtime/bboard-contract-iife.js")
        val coinPublicKey = ByteArray(32) // dummy for constructor

        val result = executor.executeConstructor(
            contractJs = contractJs,
            witnesses = mapOf(
                "localSecretKey" to WitnessProvider { _ ->
                    WitnessResult(null, testSecretKey.copyOf())
                }
            ),
            initialPrivateState = "{ secretKey: new Uint8Array(${testSecretKey.joinToString(",") { (it.toInt() and 0xFF).toString() }}) }",
            coinPublicKey = coinPublicKey,
            networkId = "undeployed",
        )

        Log.d("ContractDeployTest", "Deploy tx hex: ${result.unprovenTxHex.length} chars")
        Log.d("ContractDeployTest", "Contract address: ${result.contractAddress}")

        assertNotNull("unprovenTxHex should not be null", result.unprovenTxHex)
        assertTrue("tx hex should not be empty", result.unprovenTxHex.isNotEmpty())
        assertTrue("contract address should be 64 hex chars", result.contractAddress.length == 64)
        assertTrue("contract address should be hex", result.contractAddress.matches(Regex("^[0-9a-fA-F]+$")))
    }

    @Test
    fun executeConstructor_differentCalls_produceDifferentAddresses() = runBlocking {
        val contractJs = loadAsset("runtime/bboard-contract-iife.js")
        val coinPublicKey = ByteArray(32)
        val privateState = "{ secretKey: new Uint8Array(${testSecretKey.joinToString(",") { (it.toInt() and 0xFF).toString() }}) }"
        val witnesses = mapOf(
            "localSecretKey" to WitnessProvider { _ ->
                WitnessResult(null, testSecretKey.copyOf())
            }
        )

        val result1 = executor.executeConstructor(
            contractJs = contractJs,
            witnesses = witnesses,
            initialPrivateState = privateState,
            coinPublicKey = coinPublicKey,
        )

        val result2 = executor.executeConstructor(
            contractJs = contractJs,
            witnesses = witnesses,
            initialPrivateState = privateState,
            coinPublicKey = coinPublicKey,
        )

        // Each deploy should get a different address (random nonce in ContractDeploy)
        assertNotEquals(
            "Two deploys should produce different addresses",
            result1.contractAddress,
            result2.contractAddress,
        )
    }

    @Test(expected = CircuitExecutionException::class)
    fun executeConstructor_invalidContractJs_throws() = runBlocking {
        executor.executeConstructor(
            contractJs = "function Contract() {} // no initialState method",
            witnesses = emptyMap(),
            initialPrivateState = "{}",
            coinPublicKey = ByteArray(32),
        )
        Unit
    }
}
