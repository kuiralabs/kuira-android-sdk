package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end guard for the alpha05 bigint fix (#21).
 *
 * [ArgConverter] now emits a JS BigInt literal (`42n`) for a Kotlin integer. The
 * compactc-generated circuit wrapper guards every `Uint<N>` argument with
 * `typeof(x) === 'bigint'`, so the PRE-fix output (a plain JS number `42`) was
 * rejected — exactly the failure that forced kuira-tifosi's `patch-voting.js`.
 *
 * This drives the real QuickJS -> wrapper path **offline** (no network, no proving
 * — it stops at unproven-tx assembly), so it deterministically fails before the fix
 * and passes after. The string-only `ArgConverterTest` could not cover this.
 *
 * Target: penalty (Midnight Kicks) `joinMatch(commitDeadlineSecs: Uint<64>)`, whose
 * committed contract JS already ships as an androidTest asset.
 */
@RunWith(AndroidJUnit4::class)
class ArgConverterBigIntE2ETest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)

    /** The six witnesses the penalty circuits declare, with deterministic values. */
    private fun penaltyWitnesses() = mapOf(
        "localSecretKey" to WitnessProvider { WitnessResult(null, ByteArray(32) { (it + 1).toByte() }) },
        "localNonce" to WitnessProvider { WitnessResult(null, ByteArray(32) { (it + 7).toByte() }) },
        "localShoots" to WitnessProvider { WitnessResult(null, ByteArray(5) { 0 }, WitnessKind.VECTOR_OF_UINT8) },
        "localKeeps" to WitnessProvider { WitnessResult(null, ByteArray(5) { 0 }, WitnessKind.VECTOR_OF_UINT8) },
        "localSdShoot" to WitnessProvider { WitnessResult(null, byteArrayOf(0)) },
        "localSdKeep" to WitnessProvider { WitnessResult(null, byteArrayOf(0)) },
    )

    private fun joinMatch(arg: String) = runBlocking {
        executor.executeCircuit(
            contractJs = MidnightContract.normalizeContractJs(
                context.assets.open("contracts/penalty-contract.js").bufferedReader().readText(),
            ),
            contractAddress = "0".repeat(64),
            circuitName = "joinMatch",
            circuitArgs = listOf(arg),
            witnesses = penaltyWitnesses(),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
        )
    }

    @Test
    fun integerArg_viaArgConverter_passesBigIntGuard_whileNumberIsRejected() {
        // ArgConverter turns a Kotlin Long into the JS bigint literal "42n".
        assertEquals("42n", ArgConverter.toJsExpression(42L))

        val bigintErr = runCatching { joinMatch(ArgConverter.toJsExpression(42L)) }
            .exceptionOrNull()?.message.orEmpty()
        val numberErr = runCatching { joinMatch("42") }
            .exceptionOrNull()?.message.orEmpty()

        // pass-after: the bigint PASSES the wrapper's `typeof === 'bigint'` guard and
        // reaches joinMatch's body, where this single-party offline setup trips the
        // contract's own "cannot join your own match" assertion. Reaching the body IS
        // the proof the guard accepted the bigint — pre-fix, a number never got here.
        assertTrue(
            "bigint arg should reach the circuit body (pass the type guard); got: <$bigintErr>",
            bigintErr.contains("Cannot join your own match"),
        )

        // fail-before: the pre-fix output (a plain JS number) is rejected AT the guard,
        // before the body — so it never reaches joinMatch's assertion.
        assertTrue("a plain JS number must be rejected (throw)", numberErr.isNotEmpty())
        assertFalse(
            "number arg should be rejected by the guard, not reach the body; got: <$numberErr>",
            numberErr.contains("Cannot join your own match"),
        )
    }
}
