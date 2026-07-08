package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [CircuitExecutor.readCircuitLocal]: run a PURE circuit against `initialState()` with NO deployed
 * contract, NO address, and NO chain fetch. This is the primitive the Private Vault app uses to
 * derive `persistentHash` commitments at deploy time — before any contract exists — from the
 * contract's OWN pure circuits rather than a reimplemented hash.
 *
 * The bboard fixture exports `publicKey(sk, sequence): Bytes<32>` — a pure `persistentHash` over a
 * domain tag + its two args — which stands in for a commitment circuit here. No localnet, no
 * deployed address, no state fixture needed: the whole point is that none of those exist.
 */
@RunWith(AndroidJUnit4::class)
class ReadCircuitLocalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)

    private fun bboardJs(): String =
        MidnightContract.normalizeContractJs(
            context.assets.open("contracts/bboard-contract.js").bufferedReader().readText(),
        )

    // The bboard fixture's Contract constructor requires this witness to exist even to build the
    // handle; readCircuitLocal forwards it (a pure circuit never invokes it, but the ctor checks).
    private fun bboardWitnesses() = mapOf(
        "localSecretKey" to WitnessProvider { WitnessResult(null, ByteArray(32) { (it + 1).toByte() }) },
    )

    private suspend fun publicKey(sk: ByteArray, sequence: ByteArray): String =
        executor.readCircuitLocal(
            contractJs = bboardJs(),
            circuitName = "publicKey",
            circuitArgs = ArgConverter.toJsExpressions(sk, sequence),
            initialPrivateState = "{ secretKey: new Uint8Array(32) }",
            coinPublicKey = ByteArray(32),
            witnesses = bboardWitnesses(),
        )

    private val skA = ByteArray(32) { (it + 1).toByte() }
    private val skB = ByteArray(32) { (it + 2).toByte() }
    private val seq0 = ByteArray(32)

    @Test
    fun runsAgainstInitialState_noAddressNoChain_returnsAHash() = runBlocking {
        val result = publicKey(skA, seq0)
        // A Bytes<32> return serializes to a 64-char hex string (quoted in the JSON).
        val hex = result.trim('"')
        assertEquals("expected a 32-byte hex hash, got: $result", 64, hex.length)
        assertTrue("hash must not be all zeros", hex.any { it != '0' })
    }

    @Test
    fun isDeterministic_sameInputsSameHash() = runBlocking {
        assertEquals(publicKey(skA, seq0), publicKey(skA, seq0))
    }

    @Test
    fun differentInputsProduceDifferentHashes() = runBlocking {
        assertNotEquals(publicKey(skA, seq0), publicKey(skB, seq0))
    }
}
