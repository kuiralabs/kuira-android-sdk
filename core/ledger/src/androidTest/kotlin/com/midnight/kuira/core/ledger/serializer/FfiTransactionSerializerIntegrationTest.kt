package com.midnight.kuira.core.ledger.serializer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
import com.midnight.kuira.core.ledger.model.Intent
import com.midnight.kuira.core.ledger.model.UnshieldedOffer
import com.midnight.kuira.core.ledger.model.UtxoOutput
import com.midnight.kuira.core.ledger.model.UtxoSpend
import com.midnight.kuira.core.ledger.signer.TransactionSigner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

/**
 * Android integration tests for FfiTransactionSerializer.
 *
 * These tests verify the full serialization pipeline:
 * Kotlin Intent → JSON → JNI C → Rust FFI → SCALE codec → Hex string
 *
 * **Requirements:**
 * - Native library libkuira_crypto_ffi.so must be built and bundled in APK
 * - Run with: ./gradlew :core:ledger:connectedAndroidTest
 * - Requires Android device or emulator
 *
 * **Test Coverage:**
 * - SCALE serialization with real midnight-ledger types
 * - Bech32m address decoding for outputs
 * - Hex public key parsing for inputs
 * - JSON marshalling across JNI boundary
 * - Memory safety (no crashes or leaks)
 * - Valid SCALE output format
 */
@RunWith(AndroidJUnit4::class)
class FfiTransactionSerializerIntegrationTest {

    companion object {
        /**
         * Test mnemonic from Phase 1 crypto tests.
         * Path m/44'/2400'/0'/0/0 produces consistent addresses.
         */
        private const val TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"

        /**
         * Test addresses (Bech32m encoded for undeployed/local network).
         * Sender: From "abandon abandon...art" mnemonic at m/44'/2400'/0'/0/0
         * Recipient: Valid Bech32m encoding of 32 zero bytes (for testing)
         */
        private const val SENDER_ADDRESS = "mn_addr_undeployed19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqauuvtx"

        // Generate a valid recipient address (32 zero bytes encoded as Bech32m)
        private val RECIPIENT_ADDRESS by lazy {
            com.midnight.kuira.core.crypto.address.Bech32m.encode("mn_addr_undeployed", ByteArray(32))
        }

        /**
         * Test private key from "abandon abandon...about" mnemonic.
         * Path: m/44'/2400'/0'/0/0
         */
        private val TEST_PRIVATE_KEY = hexToBytes(
            "d319aebe08e7706091e56b1abe83f50ba6d3ceb4209dd0deca8ab22b264ff31c"
        )

        /**
         * Test public key (BIP-340 x-only, 32 bytes hex).
         * Derived from TEST_PRIVATE_KEY using TransactionSigner.
         */
        private val SENDER_PUBLIC_KEY by lazy {
            val pubKey = TransactionSigner.getPublicKey(TEST_PRIVATE_KEY)
                ?: throw IllegalStateException("Failed to derive public key")
            pubKey.toHex()
        }

        /**
         * Native NIGHT token type (32 zero bytes).
         */
        private val NATIVE_TOKEN = "0".repeat(64)

        /**
         * Test intent hash.
         */
        private val TEST_INTENT_HASH = "a".repeat(64)

        /**
         * Generate a REAL signature using TransactionSigner (Rust FFI).
         * This tests the actual signing FFI, not a mock.
         */
        private fun generateRealSignature(): ByteArray {
            val messageToSign = "Test transaction for serialization".toByteArray()
            return TransactionSigner.signData(TEST_PRIVATE_KEY, messageToSign)
                ?: throw IllegalStateException("Failed to generate signature")
        }

        // Helper: Convert hex string to bytes
        private fun hexToBytes(hex: String): ByteArray {
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        // Helper: Convert bytes to hex string
        private fun ByteArray.toHex(): String {
            return joinToString("") { "%02x".format(it) }
        }
    }

    // ============================================================================
    // Basic Functionality Tests
    // ============================================================================

    @Test
    fun testLibraryLoadsSuccessfully() {
        // This test verifies the native library is loaded
        // If library loading fails, FfiTransactionSerializer init block throws exception
        val serializer = FfiTransactionSerializer()
        assertNotNull("FfiTransactionSerializer should instantiate", serializer)
    }

    @Test
    fun testSerializeSimpleTransaction() {
        // Given: A simple Intent with one input and one output
        val input = UtxoSpend(
            intentHash = TEST_INTENT_HASH,
            outputNo = 0,
            value = BigInteger("1000000"),  // 1 NIGHT
            owner = SENDER_ADDRESS,
            ownerPublicKey = SENDER_PUBLIC_KEY,
            tokenType = NATIVE_TOKEN
        )

        val output = UtxoOutput(
            value = BigInteger("1000000"),
            owner = RECIPIENT_ADDRESS,
            tokenType = NATIVE_TOKEN
        )

        val offer = UnshieldedOffer(
            inputs = listOf(input),
            outputs = listOf(output),
            signatures = listOf(generateRealSignature())
        )

        val intent = Intent(
            guaranteedUnshieldedOffer = offer,
            fallibleUnshieldedOffer = null,
            ttl = System.currentTimeMillis() + 30 * 60 * 1000  // 30 minutes
        )

        // When: Serialize to SCALE
        val serializer = FfiTransactionSerializer()
        // MUST call getSigningMessageForInput first to generate binding commitment
        serializer.getSigningMessageForInput(offer.inputs, offer.outputs, 0, intent.ttl)
        val scaleHex = serializer.serialize(intent)

        // Then: Should produce valid hex string
        assertNotNull("SCALE hex should not be null", scaleHex)
        assertTrue("SCALE hex should not be empty", scaleHex.isNotEmpty())
        assertTrue("SCALE hex should be even length", scaleHex.length % 2 == 0)
        assertTrue("SCALE hex should only contain hex chars", scaleHex.all { it in "0123456789abcdef" })

        // SCALE transactions are typically 200-500 bytes
        assertTrue("SCALE hex should be reasonable size (>100 chars)", scaleHex.length > 100)

        println("✅ Serialized transaction: ${scaleHex.length / 2} bytes")
        println("   SCALE hex: ${scaleHex.take(64)}...")
    }

    @Test
    fun testSerializeTransactionWithMultipleInputs() {
        // Given: Intent with multiple inputs (tests JSON array serialization)
        val input1 = UtxoSpend(
            intentHash = TEST_INTENT_HASH,
            outputNo = 0,
            value = BigInteger("500000"),
            owner = SENDER_ADDRESS,
            ownerPublicKey = SENDER_PUBLIC_KEY,
            tokenType = NATIVE_TOKEN
        )

        val input2 = UtxoSpend(
            intentHash = TEST_INTENT_HASH,
            outputNo = 1,
            value = BigInteger("500000"),
            owner = SENDER_ADDRESS,
            ownerPublicKey = SENDER_PUBLIC_KEY,
            tokenType = NATIVE_TOKEN
        )

        val output = UtxoOutput(
            value = BigInteger("1000000"),
            owner = RECIPIENT_ADDRESS,
            tokenType = NATIVE_TOKEN
        )

        val offer = UnshieldedOffer(
            inputs = listOf(input1, input2),
            outputs = listOf(output),
            signatures = listOf(generateRealSignature(), generateRealSignature())
        )

        val intent = Intent(
            guaranteedUnshieldedOffer = offer,
            fallibleUnshieldedOffer = null,
            ttl = System.currentTimeMillis() + 30 * 60 * 1000
        )

        // When: Serialize
        val serializer = FfiTransactionSerializer()
        // MUST call getSigningMessageForInput first to generate binding commitment
        serializer.getSigningMessageForInput(offer.inputs, offer.outputs, 0, intent.ttl)
        val scaleHex = serializer.serialize(intent)

        // Then: Should handle multiple inputs correctly
        assertNotNull(scaleHex)
        assertTrue(scaleHex.isNotEmpty())
        assertTrue(scaleHex.length > 100)

        println("✅ Serialized multi-input transaction: ${scaleHex.length / 2} bytes")
    }

    @Test
    fun testSerializeTransactionWithChange() {
        // Given: Intent with payment + change outputs
        val input = UtxoSpend(
            intentHash = TEST_INTENT_HASH,
            outputNo = 0,
            value = BigInteger("2000000"),  // 2 NIGHT
            owner = SENDER_ADDRESS,
            ownerPublicKey = SENDER_PUBLIC_KEY,
            tokenType = NATIVE_TOKEN
        )

        val paymentOutput = UtxoOutput(
            value = BigInteger("1000000"),  // 1 NIGHT to recipient
            owner = RECIPIENT_ADDRESS,
            tokenType = NATIVE_TOKEN
        )

        val changeOutput = UtxoOutput(
            value = BigInteger("1000000"),  // 1 NIGHT change back to sender
            owner = SENDER_ADDRESS,
            tokenType = NATIVE_TOKEN
        )

        val offer = UnshieldedOffer(
            inputs = listOf(input),
            outputs = listOf(paymentOutput, changeOutput),
            signatures = listOf(generateRealSignature())
        )

        val intent = Intent(
            guaranteedUnshieldedOffer = offer,
            fallibleUnshieldedOffer = null,
            ttl = System.currentTimeMillis() + 30 * 60 * 1000
        )

        // When: Serialize
        val serializer = FfiTransactionSerializer()
        // MUST call getSigningMessageForInput first to generate binding commitment
        serializer.getSigningMessageForInput(offer.inputs, offer.outputs, 0, intent.ttl)
        val scaleHex = serializer.serialize(intent)

        // Then: Should handle multiple outputs
        assertNotNull(scaleHex)
        assertTrue(scaleHex.isNotEmpty())

        println("✅ Serialized transaction with change: ${scaleHex.length / 2} bytes")
    }

    @Test
    fun testBech32mAddressDecodingInSerialization() {
        // Given: Intent with valid Bech32m addresses
        // This specifically tests the Bech32m.decode() path in serializeOutputsToJson()
        val input = UtxoSpend(
            intentHash = TEST_INTENT_HASH,
            outputNo = 0,
            value = BigInteger("1000000"),
            owner = SENDER_ADDRESS,  // Bech32m address
            ownerPublicKey = SENDER_PUBLIC_KEY,
            tokenType = NATIVE_TOKEN
        )

        val output = UtxoOutput(
            value = BigInteger("1000000"),
            owner = RECIPIENT_ADDRESS,  // Bech32m address - will be decoded
            tokenType = NATIVE_TOKEN
        )

        val offer = UnshieldedOffer(
            inputs = listOf(input),
            outputs = listOf(output),
            signatures = listOf(generateRealSignature())
        )

        val intent = Intent(
            guaranteedUnshieldedOffer = offer,
            fallibleUnshieldedOffer = null,
            ttl = System.currentTimeMillis() + 30 * 60 * 1000
        )

        // When: Serialize (triggers Bech32m.decode())
        val serializer = FfiTransactionSerializer()
        // MUST call getSigningMessageForInput first to generate binding commitment
        serializer.getSigningMessageForInput(offer.inputs, offer.outputs, 0, intent.ttl)

        // Then: Should not crash and should produce valid output
        // If Bech32m.decode() fails, it would throw IllegalArgumentException
        val scaleHex = serializer.serialize(intent)
        assertNotNull("Bech32m decoding should succeed", scaleHex)
        assertTrue(scaleHex.isNotEmpty())

        println("✅ Bech32m address decoding successful")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSerializeThrowsWhenNoSignatures() {
        // Given: Intent without signatures
        val input = UtxoSpend(
            intentHash = TEST_INTENT_HASH,
            outputNo = 0,
            value = BigInteger("1000000"),
            owner = SENDER_ADDRESS,
            ownerPublicKey = SENDER_PUBLIC_KEY,
            tokenType = NATIVE_TOKEN
        )

        val output = UtxoOutput(
            value = BigInteger("1000000"),
            owner = RECIPIENT_ADDRESS,
            tokenType = NATIVE_TOKEN
        )

        val offer = UnshieldedOffer(
            inputs = listOf(input),
            outputs = listOf(output),
            signatures = emptyList()  // No signatures!
        )

        val intent = Intent(
            guaranteedUnshieldedOffer = offer,
            fallibleUnshieldedOffer = null,
            ttl = System.currentTimeMillis() + 30 * 60 * 1000
        )

        // When/Then: Should throw IllegalStateException
        val serializer = FfiTransactionSerializer()
        serializer.serialize(intent)  // Should throw
    }

    @Test
    fun testSerializationProducesDifferentBindingCommitments() {
        // Given: Same Intent serialized twice (with different binding commitments)
        val input = UtxoSpend(
            intentHash = TEST_INTENT_HASH,
            outputNo = 0,
            value = BigInteger("1000000"),
            owner = SENDER_ADDRESS,
            ownerPublicKey = SENDER_PUBLIC_KEY,
            tokenType = NATIVE_TOKEN
        )

        val output = UtxoOutput(
            value = BigInteger("1000000"),
            owner = RECIPIENT_ADDRESS,
            tokenType = NATIVE_TOKEN
        )

        val offer = UnshieldedOffer(
            inputs = listOf(input),
            outputs = listOf(output),
            signatures = listOf(generateRealSignature())
        )

        val intent = Intent(
            guaranteedUnshieldedOffer = offer,
            fallibleUnshieldedOffer = null,
            ttl = 1704067200000  // Fixed timestamp
        )

        // When: Serialize twice (each generates new random binding commitment)
        val serializer = FfiTransactionSerializer()

        // First serialization
        serializer.getSigningMessageForInput(offer.inputs, offer.outputs, 0, intent.ttl)
        val result1 = serializer.serialize(intent)

        // Second serialization (new random binding commitment)
        serializer.getSigningMessageForInput(offer.inputs, offer.outputs, 0, intent.ttl)
        val result2 = serializer.serialize(intent)

        // Then: Should produce DIFFERENT output (because binding commitment is random)
        assertNotEquals("Serialization should differ due to random binding commitment", result1, result2)
        // But both should be valid SCALE
        assertTrue("Both results should be valid hex", result1.all { it in "0123456789abcdef" })
        assertTrue("Both results should be valid hex", result2.all { it in "0123456789abcdef" })

        println("✅ Serialization uses random binding commitments")
    }
}
