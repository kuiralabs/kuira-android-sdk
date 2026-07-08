package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Batch view reads ([CircuitExecutor.readManyCircuits]): one engine session, one state snapshot,
 * each circuit isolated on a native clone, failures captured per key, and the state pool back to
 * baseline afterwards (the #55 leak invariant extended to the batch path).
 */
@RunWith(AndroidJUnit4::class)
class ReadManyCircuitsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    private fun loadContract(path: String): String =
        MidnightContract.normalizeContractJs(loadAsset(path))

    private fun bboardWitnesses() = mapOf(
        "localSecretKey" to WitnessProvider { WitnessResult(null, ByteArray(32) { (it + 1).toByte() }) },
    )

    private suspend fun batch(
        requests: List<CircuitExecutor.CircuitReadRequest>,
    ): Map<String, CircuitExecutor.CircuitReadResult> = executor.readManyCircuits(
        contractJs = loadContract("contracts/bboard-contract.js"),
        contractAddress = loadAsset("fixtures/bboard-evolved-address.txt").trim(),
        requests = requests,
        initialPrivateState = "{ secretKey: new Uint8Array(32) }",
        coinPublicKey = ByteArray(32),
        onChainStateHex = loadAsset("fixtures/bboard-evolved-state.hex").trim(),
        witnesses = bboardWitnesses(),
    )

    @Test
    fun readMany_isolatesEachCircuitOnItsOwnStateClone() = runBlocking {
        // The evolved fixture's board is VACANT and `post` asserts vacancy. If each read runs on
        // its own clone of the snapshot, BOTH posts succeed; if the first post's state mutation
        // leaked into the second, the second would hit the occupied-board assert.
        val results = batch(
            listOf(
                CircuitExecutor.CircuitReadRequest("first", "post", listOf("'isolation probe A'")),
                CircuitExecutor.CircuitReadRequest("second", "post", listOf("'isolation probe B'")),
            ),
        )
        assertNull("first post must succeed: ${results["first"]?.error}", results["first"]?.error)
        assertNull(
            "second post must succeed — an occupied-board assert here means the first read's " +
                "state mutation leaked into the second (clone isolation broken): ${results["second"]?.error}",
            results["second"]?.error,
        )
    }

    @Test
    fun readMany_capturesFailuresPerKey() = runBlocking {
        val results = batch(
            listOf(
                CircuitExecutor.CircuitReadRequest("good", "post", listOf("'still works'")),
                CircuitExecutor.CircuitReadRequest("bad", "nonexistentCircuit"),
            ),
        )
        assertNotNull("good result present", results["good"]?.json)
        assertNull("good has no error", results["good"]?.error)
        assertNotNull("bad captured as a per-key error, not a batch failure", results["bad"]?.error)
        assertNull("bad has no result", results["bad"]?.json)
    }

    @Test
    fun readMany_returnsStatePoolToBaseline() = runBlocking {
        batch(listOf(CircuitExecutor.CircuitReadRequest("warmup", "post", listOf("'warm up'"))))
        val baseline = ContractRuntime.statePoolLen()
        repeat(3) {
            batch(
                listOf(
                    CircuitExecutor.CircuitReadRequest("a", "post", listOf("'a'")),
                    CircuitExecutor.CircuitReadRequest("b", "post", listOf("'b'")),
                    CircuitExecutor.CircuitReadRequest("boom", "nonexistentCircuit"),
                ),
            )
        }
        assertEquals(
            "batch reads must free every clone/post/base handle (incl. on the error path)",
            baseline,
            ContractRuntime.statePoolLen(),
        )
    }

    @Test
    fun readMany_rejectsDuplicateOrUnsafeKeys() = runBlocking {
        val dup = runCatching {
            batch(
                listOf(
                    CircuitExecutor.CircuitReadRequest("k", "post", listOf("'x'")),
                    CircuitExecutor.CircuitReadRequest("k", "post", listOf("'y'")),
                ),
            )
        }
        assertTrue("duplicate keys rejected", dup.isFailure)
        val unsafe = runCatching {
            batch(listOf(CircuitExecutor.CircuitReadRequest("bad'key", "post", listOf("'x'"))))
        }
        assertTrue("quote-bearing key rejected (JS injection guard)", unsafe.isFailure)
    }
}
