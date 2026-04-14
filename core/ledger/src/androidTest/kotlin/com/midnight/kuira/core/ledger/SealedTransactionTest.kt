package com.midnight.kuira.core.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
import com.midnight.kuira.core.ledger.model.Intent
import com.midnight.kuira.core.ledger.model.UnshieldedOffer
import com.midnight.kuira.core.ledger.model.UtxoOutput
import com.midnight.kuira.core.ledger.model.UtxoSpend
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test to verify sealed transaction tag.
 * Runs on actual Android device/emulator to generate FFI logs.
 */
@RunWith(AndroidJUnit4::class)
class SealedTransactionTest {

    @Test
    fun testSealedTransactionTagFormat() {
        val serializer = FfiTransactionSerializer(networkId = "undeployed")

        val testOwnerAddress = Bech32m.encode("mn_addr_undeployed", ByteArray(32))

        val inputs = listOf(
            UtxoSpend(
                value = java.math.BigInteger("5000000"),
                ownerPublicKey = "7de754a427c2723bd9e04f7e7876b70bed051aaa439966aaff1596a2c3309fe0",
                tokenType = "0000000000000000000000000000000000000000000000000000000000000000",
                intentHash = "00e28d3099efda8b36d6277c61f4ce062d52102898b1314c16bd28c9d905b59c",
                outputNo = 0,
                owner = testOwnerAddress
            )
        )

        val recipientAddress1 = Bech32m.encode("mn_addr_undeployed", ByteArray(32) { 1.toByte() })
        val recipientAddress2 = Bech32m.encode("mn_addr_undeployed", ByteArray(32) { 2.toByte() })

        val outputs = listOf(
            UtxoOutput(
                value = java.math.BigInteger("1000000"),
                owner = recipientAddress1,
                tokenType = "0000000000000000000000000000000000000000000000000000000000000000"
            ),
            UtxoOutput(
                value = java.math.BigInteger("4000000"),
                owner = recipientAddress2,
                tokenType = "0000000000000000000000000000000000000000000000000000000000000000"
            )
        )

        val signatures = listOf(
            hexToBytes("fd5dbd86cdbf787453b6d88c350dd4fdf20d1096dc8cb7a64f5056dc2498ffeb90c12dcf98b08fc5a8567f6e1a4cd5c4297de435e87819301ebde75f95e170bc")
        )

        val offer = UnshieldedOffer(inputs, outputs, signatures)
        val ttl = 1769298539531L

        val intent = Intent(
            guaranteedUnshieldedOffer = offer,
            fallibleUnshieldedOffer = null,
            ttl = ttl
        )

        val signingMessage = serializer.getSigningMessageForInput(inputs, outputs, 0, ttl)
        assertNotNull("Signing message should be generated", signingMessage)

        val txHex = serializer.serialize(intent)
        assertNotNull("Transaction serialization should succeed", txHex)

        // Decode and verify tag
        val bytes = txHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        var tagEndIndex = -1
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == ')'.code.toByte() && bytes[i+1] == ':'.code.toByte()) {
                tagEndIndex = i + 1
                break
            }
        }
        require(tagEndIndex > 0) { "No tag delimiter ')' found" }

        val tagBytes = bytes.sliceArray(0..tagEndIndex)
        val tag = String(tagBytes, Charsets.UTF_8)

        assertTrue("Tag should contain 'proof-preimage'", tag.contains("proof-preimage"))

        val isSealed = tag.contains("pedersen-schnorr[v1]") || tag.contains("embedded-fr[v1]")
        val isPedersen = tag.contains("pedersen[v1]") && !tag.contains("pedersen-schnorr")

        when {
            isSealed -> { /* Transaction is sealed as expected */ }
            isPedersen -> {
                fail("Transaction was not sealed - binding type is incorrect")
            }
            else -> {
                println("Unexpected binding type in tag: $tag")
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
