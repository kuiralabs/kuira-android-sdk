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
 * Runs a **real, complex** contract (penalty / Midnight Kicks) through the full
 * QuickJS → native-FFI execution path, covering runtime features that the trivial
 * bboard contract never exercises. Every recent on-device break lived in exactly
 * this seam and escaped because the only contract under test was bboard:
 *
 *  - nested constructor state — `_ensureRustHandle` flattened it, so the
 *    constructor's `ins` into a nested slot failed
 *    ("attempted to ins, only array, map, and bmt are supported");
 *  - Pedersen `persistentCommit` — the runtime wrapper dropped the opening arg,
 *    so the native side saw 0 opening bytes ("opening must be 32 bytes, got 0").
 *
 * These run offline (no proving keys, no network) — they stop before proving.
 * The penalty contract JS is a committed snapshot under
 * `androidTest/assets/contracts/penalty-contract.js` (compactc 0.31.0 → runtime
 * 0.16.0); refresh it when the contract changes.
 */
@RunWith(AndroidJUnit4::class)
class PenaltyContractHarnessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    /** Load a contract through the production ES-module → script normalization. */
    private fun loadContract(path: String): String =
        MidnightContract.normalizeContractJs(loadAsset(path))

    /** All six witnesses the penalty circuits declare, with deterministic test values. */
    private fun penaltyWitnesses() = mapOf(
        "localSecretKey" to WitnessProvider { WitnessResult(null, ByteArray(32) { (it + 1).toByte() }) },
        "localNonce" to WitnessProvider { WitnessResult(null, ByteArray(32) { (it + 7).toByte() }) },
        "localShoots" to WitnessProvider { WitnessResult(null, ByteArray(5) { 0 }, WitnessKind.VECTOR_OF_UINT8) },
        "localKeeps" to WitnessProvider { WitnessResult(null, ByteArray(5) { 0 }, WitnessKind.VECTOR_OF_UINT8) },
        "localSdShoot" to WitnessProvider { WitnessResult(null, byteArrayOf(0)) },
        "localSdKeep" to WitnessProvider { WitnessResult(null, byteArrayOf(0)) },
    )

    /**
     * The penalty constructor builds a NESTED initial ledger state
     * (`Array[Array[8], Array[15]]`). Deploying it must assemble — pre-fix the
     * nested sub-arrays weren't created natively and the constructor's `ins`
     * failed. Guards `stateValueDescriptor` / `_ensureRustHandle`.
     */
    @Test
    fun deploy_buildsNestedInitialState() = runBlocking {
        val result = executor.executeConstructor(
            contractJs = loadContract("contracts/penalty-contract.js"),
            witnesses = penaltyWitnesses(),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )
        assertTrue("Deploy tx should be substantial hex", result.unprovenTxHex.length > 100)
        assertEquals("Derived contract address should be 32 bytes", 64, result.contractAddress.length)
    }
}
