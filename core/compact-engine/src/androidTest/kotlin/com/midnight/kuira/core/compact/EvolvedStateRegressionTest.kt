package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the seam that kept breaking on-device: **QuickJS running
 * a real compiled contract against an *evolved* on-chain state, through the native
 * FFI**, then assembling the transaction.
 *
 * The existing [BboardEndToEndTest] only exercises a *fresh* constructor state
 * (`offlinePipeline_executeAndAssemble`) or needs a live indexer + DApp connector
 * (`onlinePipeline_…`). Neither covers the case that actually failed in production:
 * calling a circuit on a contract whose state has advanced past its constructor
 * value. That gap let the `partition_transcripts` ReadMismatch ship.
 *
 * These tests are deterministic, offline, and need no proving keys or network —
 * they run the full execute → transcript → assemble path against a committed
 * **evolved** bboard state (a VACANT board whose `sequence` Counter = 1, captured
 * from a real device run; see `kuira-crypto-ffi/test-fixtures/readmismatch-post.json`
 * and the native `readmismatch_repro` tests).
 *
 * The bug this guards: `queryLedgerState` stored the live read value in the
 * transcript, the contract's `_descriptor.fromValue` then `shift()`-emptied it, and
 * `transformPublicTranscript` zero-filled the empty value — recording a Counter read
 * of 1 as 0 → ReadMismatch on assembly. The fix is `snapshotReadContent` in
 * `compact-runtime-iife.js`. If this test ever fails with "ReadMismatch", that fix
 * (or the transcript-recording path) has regressed.
 */
@RunWith(AndroidJUnit4::class)
class EvolvedStateRegressionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    /** Load a contract asset through the same ES-module → script normalization the
     *  production [MidnightContract] builder applies, so the test exercises the real
     *  loading path (not a hand-bundled IIFE that drifts out of date). */
    private fun loadContract(path: String): String =
        MidnightContract.normalizeContractJs(loadAsset(path))

    private fun bboardWitnesses() = mapOf(
        "localSecretKey" to WitnessProvider { WitnessResult(null, ByteArray(32) { (it + 1).toByte() }) },
    )

    /**
     * Calling `post` on a VACANT board whose `sequence` Counter has advanced to 1
     * must assemble cleanly. Pre-fix this threw
     * `partition_transcripts ... ReadMismatch { expected: <[-]: b8>, actual: <[01]: b8> }`
     * because the recorded Counter read was corrupted from 1 to 0.
     */
    @Test
    fun post_onEvolvedState_assemblesWithoutReadMismatch() = runBlocking {
        val evolvedStateHex = loadAsset("fixtures/bboard-evolved-state.hex").trim()
        val ledgerParamsHex = loadAsset("fixtures/bboard-evolved-ledger-params.hex").trim()
        val contractAddress = loadAsset("fixtures/bboard-evolved-address.txt").trim()

        val result = try {
            executor.executeCircuit(
                contractJs = loadContract("contracts/bboard-contract.js"),
                contractAddress = contractAddress,
                circuitName = "post",
                circuitArgs = listOf("'evolved-state regression'"),
                witnesses = bboardWitnesses(),
                initialPrivateState = "{ secretKey: new Uint8Array(32) }",
                coinPublicKey = ByteArray(32),
                networkId = "preprod",
                onChainStateHex = evolvedStateHex,
                ledgerParametersHex = ledgerParamsHex,
            )
        } catch (e: Exception) {
            if (e.message?.contains("ReadMismatch") == true) {
                fail(
                    "ReadMismatch regressed: a circuit read on an evolved contract state was " +
                        "corrupted during transcript recording. Check `snapshotReadContent` in " +
                        "compact-runtime-iife.js and queryLedgerState. Original: ${e.message}",
                )
            }
            throw e
        }

        assertTrue(
            "Assembled tx should be substantial hex (assembly succeeded = no ReadMismatch)",
            result.unprovenTxHex.length > 100 && result.unprovenTxHex.length % 2 == 0,
        )
    }

    /**
     * The fresh-state path must keep working too — this is the case that always
     * worked (constructor state == on-chain state), so it pins the baseline and
     * makes a fresh-vs-evolved divergence obvious if one is introduced.
     */
    @Test
    fun post_onFreshState_stillAssembles() = runBlocking {
        val result = executor.executeCircuit(
            contractJs = loadContract("contracts/bboard-contract.js"),
            contractAddress = "0".repeat(64),
            circuitName = "post",
            circuitArgs = listOf("'fresh-state baseline'"),
            witnesses = bboardWitnesses(),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )
        assertTrue(result.unprovenTxHex.length > 100)
    }
}
