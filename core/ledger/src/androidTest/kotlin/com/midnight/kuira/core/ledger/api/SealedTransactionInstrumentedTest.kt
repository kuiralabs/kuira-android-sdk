package com.midnight.kuira.core.ledger.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.ledger.model.Intent
import com.midnight.kuira.core.ledger.model.UnshieldedOffer
import com.midnight.kuira.core.ledger.model.UtxoSpend
import com.midnight.kuira.core.ledger.model.UtxoOutput
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

/**
 * Instrumented test to verify sealed transaction tag on actual Android device.
 * This will generate FFI logs that we can inspect via logcat.
 */
@RunWith(AndroidJUnit4::class)
class SealedTransactionInstrumentedTest {

    private lateinit var serializer: FfiTransactionSerializer

    @Before
    fun setup() {
        serializer = FfiTransactionSerializer()
    }

    @Test
    fun testSealedTransactionTag() {
        val inputs = listOf(
            UtxoSpend(
                intentHash = "00e28d3099efda8b36d6277c61f4ce062d52102898b1314c16bd28c9d905b59c",
                outputNo = 0,
                value = BigInteger("5000000"),
                owner = "mn_addr_undeployed19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqauuvtx",
                ownerPublicKey = "7de754a427c2723bd9e04f7e7876b70bed051aaa439966aaff1596a2c3309fe0",
                tokenType = "0000000000000000000000000000000000000000000000000000000000000000"
            )
        )

        val outputs = listOf(
            UtxoOutput(
                value = BigInteger("1000000"),
                owner = "mn_addr_undeployed19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqauuvtx",
                tokenType = "0000000000000000000000000000000000000000000000000000000000000000"
            ),
            UtxoOutput(
                value = BigInteger("4000000"),
                owner = "mn_addr_undeployed19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqauuvtx",
                tokenType = "0000000000000000000000000000000000000000000000000000000000000000"
            )
        )

        val ttl = 1769298539531L

        val signingMessageHex = serializer.getSigningMessageForInput(inputs, outputs, 0, ttl)
        assertNotNull("Should get signing message", signingMessageHex)

        val dummySignature = ByteArray(64) { 0xAB.toByte() }

        val signedIntent = Intent(
            guaranteedUnshieldedOffer = UnshieldedOffer(
                inputs = inputs,
                outputs = outputs,
                signatures = listOf(dummySignature)
            ),
            fallibleUnshieldedOffer = null,
            ttl = ttl
        )

        val txHex = serializer.serialize(signedIntent)
        assertNotNull("Transaction serialization should succeed", txHex)

        // Decode and verify tag prefix
        val bytes = txHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val tagEndIndex = bytes.indexOfFirst { it == ':'.code.toByte() }
        assertTrue("Should have tag delimiter ':'", tagEndIndex > 0)

        val tagBytes = bytes.sliceArray(0..tagEndIndex)
        val tag = String(tagBytes, Charsets.UTF_8)

        // Verify tag is a valid Midnight serialization tag
        assertTrue(
            "Tag should contain 'midnight', got: $tag",
            tag.contains("midnight")
        )
    }
}
