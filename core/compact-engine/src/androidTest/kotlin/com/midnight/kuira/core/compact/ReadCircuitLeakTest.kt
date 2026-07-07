package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The native contract-state pool is process-global (it survives QuickJS teardown), so any
 * execution path that creates handles without freeing them grows it unboundedly — a dApp
 * polling view circuits leaks a full contract state per read. The write path frees its handles
 * in [CircuitExecutor]'s assembly `finally`; these tests pin the invariant that the READ path
 * (which never reaches assembly) and the write path both return the pool to baseline, on
 * success AND when the circuit throws (a thrown read is routine: proposal-list enumeration
 * terminates on the contract's own not-found assert).
 */
@RunWith(AndroidJUnit4::class)
class ReadCircuitLeakTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    private fun loadContract(path: String): String =
        MidnightContract.normalizeContractJs(loadAsset(path))

    private fun bboardWitnesses() = mapOf(
        "localSecretKey" to WitnessProvider { WitnessResult(null, ByteArray(32) { (it + 1).toByte() }) },
    )

    private suspend fun readOnce(circuitName: String = "post"): String =
        executor.readCircuit(
            contractJs = loadContract("contracts/bboard-contract.js"),
            contractAddress = loadAsset("fixtures/bboard-evolved-address.txt").trim(),
            circuitName = circuitName,
            circuitArgs = if (circuitName == "post") listOf("'leak probe'") else emptyList(),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
            onChainStateHex = loadAsset("fixtures/bboard-evolved-state.hex").trim(),
            witnesses = bboardWitnesses(),
        )

    @Test
    fun readCircuit_returnsStatePoolToBaseline() = runBlocking {
        readOnce() // warm-up: JNI load + any lazy one-time allocations settle first
        val baseline = ContractRuntime.statePoolLen()
        repeat(5) { readOnce() }
        assertEquals(
            "read path must free its native state handles (pool grew = leaked contract states)",
            baseline,
            ContractRuntime.statePoolLen(),
        )
    }

    @Test
    fun readCircuit_returnsStatePoolToBaseline_whenCircuitThrows() = runBlocking {
        readOnce() // warm-up
        val baseline = ContractRuntime.statePoolLen()
        repeat(3) {
            try {
                // An unknown circuit throws in the execution tail — AFTER the on-chain state swap
                // created its handle — modeling the real terminator (an in-circuit assert).
                readOnce(circuitName = "nonexistentCircuit")
                throw AssertionError("expected the unknown-circuit read to throw")
            } catch (e: CircuitExecutionException) {
                // expected — the leak assertion below is what this test is about
            }
        }
        assertEquals(
            "a THROWING read must also free its handles (list enumeration terminates by throw)",
            baseline,
            ContractRuntime.statePoolLen(),
        )
    }

    @Test
    fun executeCircuit_writePath_returnsStatePoolToBaseline() = runBlocking {
        // Guards the write path's existing free (assembly finally) against regression, using the
        // same on-chain-state swap the read path uses.
        val stateHex = loadAsset("fixtures/bboard-evolved-state.hex").trim()
        val ledgerParamsHex = loadAsset("fixtures/bboard-evolved-ledger-params.hex").trim()
        val address = loadAsset("fixtures/bboard-evolved-address.txt").trim()
        suspend fun writeOnce() = executor.executeCircuit(
            contractJs = loadContract("contracts/bboard-contract.js"),
            contractAddress = address,
            circuitName = "post",
            circuitArgs = listOf("'write leak probe'"),
            witnesses = bboardWitnesses(),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
            networkId = "preprod",
            onChainStateHex = stateHex,
            ledgerParametersHex = ledgerParamsHex,
        )
        writeOnce() // warm-up
        val baseline = ContractRuntime.statePoolLen()
        val result = writeOnce()
        assertTrue("sanity: write path still assembles", result.unprovenTxHex.length > 100)
        assertEquals(
            "write path must keep returning the state pool to baseline",
            baseline,
            ContractRuntime.statePoolLen(),
        )
    }
}
