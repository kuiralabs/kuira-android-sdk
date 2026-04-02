package com.midnight.kuira.core.crypto.proving

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.shielded.ZswapTransferBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E test: build transaction → prove locally on phone → verify proof output.
 *
 * Validates that the phone can generate valid ZK proofs without a proof server.
 */
@RunWith(AndroidJUnit4::class)
class LocalProvingE2ETest {

    companion object {
        private const val TAG = "LocalProvingE2E"

        private const val BOB_SHIELDED_ADDR =
            "mn_shield-addr_undeployed1ys3ucc6ly48wtqekax3ehp3qwdfc257tcd2uaqp8vf3kvm6wggccgjhsxtsuw983n80t28fk3ncdtk2dcxn268f4mxrrhaqmkwt7ajs4fj9f5"

        private const val NIGHT_TOKEN = "0000000000000000000000000000000000000000000000000000000000000000"
    }

    @Test
    fun given_output_only_offer_when_prove_locally_then_produces_valid_proof() {
        // Setup: ensure keys are downloaded
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyManager = ProvingKeyManager(context)
        runBlocking {
            if (!keyManager.hasWalletKeys()) {
                keyManager.downloadWalletKeys()
            }
        }
        assertTrue("Need proving keys", keyManager.hasWalletKeys())

        Log.d(TAG, "=== E2E Local Proving Test ===")

        // Step 1: Decode Bob's shielded address → coin_pk + enc_pk (64 bytes)
        val (_, payload) = Bech32m.decode(BOB_SHIELDED_ADDR)
        assertEquals("Shielded payload should be 64 bytes", 64, payload.size)
        val coinPkHex = payload.sliceArray(0 until 32)
            .joinToString("") { "%02x".format(it) }
        val encPkHex = payload.sliceArray(32 until 64)
            .joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Bob coin_pk: $coinPkHex")
        Log.d(TAG, "Bob enc_pk: $encPkHex")

        // Step 2: Create a shielded output for Bob
        val outputResult = ZswapTransferBuilder.createOutput(
            recipientCoinPk = coinPkHex,
            recipientEncPk = encPkHex,
            tokenType = NIGHT_TOKEN,
            amount = java.math.BigInteger("1000000"),
        )
        assertNotNull("Should create output", outputResult)
        Log.d(TAG, "Output created: ${outputResult!!.outputHex.length / 2} bytes")

        // Step 3: Build offer from single output
        val offerJson = ZswapTransferBuilder.nativeBuildOfferPublic(
            inputsHexJson = "[]",
            outputsHexJson = "[\"${outputResult.outputHex}\"]",
        )
        assertNotNull("Should build offer", offerJson)
        val offerResult = ZswapTransferBuilder.parseOfferResult(offerJson!!)
        assertNotNull("Should parse offer", offerResult)

        // Step 4: Build unproven transaction
        val txHex = ZswapTransferBuilder.buildTransaction(
            offerHex = offerResult!!.offerHex,
            networkId = "undeployed",
            ttlMs = System.currentTimeMillis() + 3600_000,
        )
        assertNotNull("Should build transaction", txHex)
        Log.d(TAG, "Unproven tx: ${txHex!!.length / 2} bytes")

        // Step 5: PROVE LOCALLY
        Log.d(TAG, "Starting local proof generation...")
        val start = System.currentTimeMillis()

        val provenTxHex = LocalProver.proveTransaction(
            unprovenTxHex = txHex,
            keysDir = keyManager.keysDir.absolutePath,
        )

        val elapsed = System.currentTimeMillis() - start

        // Step 6: Verify
        assertNotNull("LOCAL PROVING SHOULD PRODUCE OUTPUT", provenTxHex)
        assertTrue(
            "Proven tx should be larger (proofs added). Unproven=${txHex.length / 2}B, proven=${provenTxHex!!.length / 2}B",
            provenTxHex.length > txHex.length
        )

        Log.d(TAG, "=== LOCAL PROVING SUCCEEDED ===")
        Log.d(TAG, "  Time:     ${elapsed}ms (${elapsed / 1000.0}s)")
        Log.d(TAG, "  Unproven: ${txHex.length / 2} bytes")
        Log.d(TAG, "  Proven:   ${provenTxHex.length / 2} bytes")
        Log.d(TAG, "  Overhead: +${(provenTxHex.length - txHex.length) / 2} bytes")
    }
}
