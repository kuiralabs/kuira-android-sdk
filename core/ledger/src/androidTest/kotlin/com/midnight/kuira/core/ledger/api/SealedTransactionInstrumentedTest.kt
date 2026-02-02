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
        println("\n═══════════════════════════════════════════════════════════════")
        println("  [INSTRUMENTED] Testing Sealed Transaction Tag")
        println("═══════════════════════════════════════════════════════════════\n")

        // Build test inputs using model classes
        val inputs = listOf(
            UtxoSpend(
                intentHash = "00e28d3099efda8b36d6277c61f4ce062d52102898b1314c16bd28c9d905b59c",
                outputNo = 0,
                value = BigInteger("5000000"),
                owner = "mn_addr_testnet1ejywu6w8vvfdxj8hf0ruzqszc065nz7n3t6h99da8h3thx2e5kvw5pz",
                ownerPublicKey = "7de754a427c2723bd9e04f7e7876b70bed051aaa439966aaff1596a2c3309fe0",
                tokenType = "0000000000000000000000000000000000000000000000000000000000000000"
            )
        )

        // Test recipient (Bech32m address) and change output
        val outputs = listOf(
            UtxoOutput(
                value = BigInteger("1000000"),
                owner = "mn_addr_testnet1ejywu6w8vvfdxj8hf0ruzqszc065nz7n3t6h99da8h3thx2e5kvw5pz",  // Test address
                tokenType = "0000000000000000000000000000000000000000000000000000000000000000"
            ),
            UtxoOutput(
                value = BigInteger("4000000"),
                owner = "mn_addr_testnet1ejywu6w8vvfdxj8hf0ruzqszc065nz7n3t6h99da8h3thx2e5kvw5pz",  // Change address
                tokenType = "0000000000000000000000000000000000000000000000000000000000000000"
            )
        )

        val ttl = 1769298539531L

        println("📦 Getting signing message from FFI...")

        // Step 1: Get signing message (this also stores binding commitment)
        val signingMessageHex = serializer.getSigningMessageForInput(inputs, outputs, 0, ttl)
        assertNotNull("Should get signing message", signingMessageHex)
        println("✅ Got signing message: ${signingMessageHex!!.take(40)}...")

        // Step 2: Create a test signature (in real usage, this comes from TransactionSigner)
        // For this test we use a dummy signature since we're testing serialization format
        val dummySignature = ByteArray(64) { 0xAB.toByte() }

        // Step 3: Build Intent with signature and serialize
        val signedIntent = Intent(
            guaranteedUnshieldedOffer = UnshieldedOffer(
                inputs = inputs,
                outputs = outputs,
                signatures = listOf(dummySignature)
            ),
            fallibleUnshieldedOffer = null,
            ttl = ttl
        )

        println("📦 Calling FFI serialization...")
        val txHex = serializer.serialize(signedIntent)

        assertNotNull("Transaction serialization should succeed", txHex)
        println("✅ Transaction serialized: ${txHex.length / 2} bytes")

        // Decode and verify tag
        val bytes = txHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val tagEndIndex = bytes.indexOfFirst { it == ':'.code.toByte() }
        require(tagEndIndex > 0) { "No tag delimiter ':' found" }

        val tagBytes = bytes.sliceArray(0..tagEndIndex)
        val tag = String(tagBytes, Charsets.UTF_8)

        println("📋 Transaction Tag: $tag")

        // Verify components
        assertTrue("Tag should contain 'proof-preimage'", tag.contains("proof-preimage"))

        val isSealed = tag.contains("pedersen-schnorr[v1]") || tag.contains("embedded-fr[v1]")
        val isPedersen = tag.contains("pedersen[v1]") && !tag.contains("pedersen-schnorr")

        when {
            isSealed -> println("✅ SUCCESS! Transaction is SEALED (PureGeneratorPedersen)")
            isPedersen -> {
                println("❌ FAIL! Transaction is NOT SEALED (still using Pedersen)")
                fail("Transaction was not sealed - binding type is pedersen[v1] instead of pedersen-schnorr[v1]")
            }
            else -> {
                println("⚠️  WARNING! Unexpected binding type in tag")
            }
        }

        println("\n═══════════════════════════════════════════════════════════════")
        println("  Check logcat for detailed FFI output:")
        println("  adb logcat -d | grep -A 10 'FULL TRANSACTION TAG'")
        println("═══════════════════════════════════════════════════════════════\n")
    }
}
