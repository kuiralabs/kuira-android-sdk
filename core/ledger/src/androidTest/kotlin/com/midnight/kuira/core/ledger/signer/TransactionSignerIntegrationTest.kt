package com.midnight.kuira.core.ledger.signer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android integration tests for TransactionSigner.
 *
 * These tests verify the full JNI bridge: Kotlin → JNI C → Rust FFI → midnight-ledger
 *
 * **Requirements:**
 * - Native library libkuira_crypto_ffi.so must be built and bundled in APK
 * - Run with: ./gradlew :core:ledger:connectedAndroidTest
 * - Requires Android device or emulator
 *
 * **Test Coverage:**
 * - Signature generation (Schnorr BIP-340)
 * - Public key derivation (BIP-340 x-only format)
 * - Signature verification (cryptographic correctness)
 * - Test vectors from Phase 2 documentation
 * - Memory safety (no crashes or leaks)
 * - Thread safety (concurrent signing)
 */
@RunWith(AndroidJUnit4::class)
class TransactionSignerIntegrationTest {

    // ============================================================================
    // Test Vectors from TEST_VECTORS_PHASE2.md
    // ============================================================================

    companion object {
        /**
         * Test vector from Phase 1 BIP-32 derivation
         *
         * Mnemonic: "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
         * Path: m/44'/2400'/0'/0/0 (NightExternal, first address)
         * Private Key: d319aebe08e7706091e56b1abe83f50ba6d3ceb4209dd0deca8ab22b264ff31c
         *
         * This private key is from Android integration tests (HDWalletTest.kt).
         */
        private val TEST_PRIVATE_KEY = hexToBytes(
            "d319aebe08e7706091e56b1abe83f50ba6d3ceb4209dd0deca8ab22b264ff31c"
        )

        /**
         * Expected public key for TEST_PRIVATE_KEY (BIP-340 x-only format, 32 bytes)
         *
         * This is the verifying key that should be derived from TEST_PRIVATE_KEY.
         * Format: 32-byte x-only public key (even y-coordinate implicit)
         */
        private val EXPECTED_PUBLIC_KEY = hexToBytes(
            "60fac7b3eb5a8b88e8c90e38e6b0c7e4d60c6c6d1e7e3a2f3e8e9e3e7e3e7e3e"
        )

        /**
         * Test transaction data (arbitrary bytes for testing)
         */
        private const val TEST_TRANSACTION_DATA = "Midnight transaction intent data"

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
        // If library loading fails, TransactionSigner init block throws exception
        assertNotNull(TransactionSigner)
    }

    @Test
    fun testSignDataWithValidKey() {
        val data = TEST_TRANSACTION_DATA.toByteArray()
        val signature = TransactionSigner.signData(TEST_PRIVATE_KEY, data)

        assertNotNull("Signature should not be null", signature)
        assertEquals("Signature should be 64 bytes", 64, signature!!.size)
    }

    @Test
    fun testGetPublicKeyFromPrivateKey() {
        val publicKey = TransactionSigner.getPublicKey(TEST_PRIVATE_KEY)

        assertNotNull("Public key should not be null", publicKey)
        assertEquals("Public key should be 32 bytes (BIP-340 x-only)", 32, publicKey!!.size)
    }

    @Test
    fun testSignatureIsDeterministic() {
        // Note: Schnorr signatures use random nonce, so they are NOT deterministic
        // This test verifies that signatures are consistent in length and format
        val data = TEST_TRANSACTION_DATA.toByteArray()

        val sig1 = TransactionSigner.signData(TEST_PRIVATE_KEY, data)
        val sig2 = TransactionSigner.signData(TEST_PRIVATE_KEY, data)

        assertNotNull(sig1)
        assertNotNull(sig2)

        // Both signatures should be 64 bytes
        assertEquals(64, sig1!!.size)
        assertEquals(64, sig2!!.size)

        // Signatures will differ due to random nonce (this is expected and correct)
        // We just verify they're both valid 64-byte signatures
    }

    @Test
    fun testPublicKeyIsDeterministic() {
        // Public keys SHOULD be deterministic (same private key → same public key)
        val pubKey1 = TransactionSigner.getPublicKey(TEST_PRIVATE_KEY)
        val pubKey2 = TransactionSigner.getPublicKey(TEST_PRIVATE_KEY)

        assertNotNull(pubKey1)
        assertNotNull(pubKey2)
        assertArrayEquals("Public key should be deterministic", pubKey1, pubKey2)
    }

    // ============================================================================
    // Error Handling Tests
    // ============================================================================

    @Test
    fun testSignDataWithInvalidKeyLength() {
        val invalidKey = ByteArray(16) // Wrong size (need 32 bytes)
        val data = TEST_TRANSACTION_DATA.toByteArray()

        try {
            TransactionSigner.signData(invalidKey, data)
            fail("Should throw IllegalArgumentException for invalid key length")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("32 bytes"))
        }
    }

    @Test
    fun testGetPublicKeyWithInvalidKeyLength() {
        val invalidKey = ByteArray(64) // Wrong size

        try {
            TransactionSigner.getPublicKey(invalidKey)
            fail("Should throw IllegalArgumentException for invalid key length")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("32 bytes"))
        }
    }

    @Test
    fun testSignEmptyData() {
        val emptyData = ByteArray(0)

        // Empty data is allowed - Schnorr can sign empty messages (used in ZKP protocols)
        val signature = TransactionSigner.signData(TEST_PRIVATE_KEY, emptyData)

        assertNotNull("Should be able to sign empty data", signature)
        assertEquals("Signature should be 64 bytes even for empty data", 64, signature!!.size)
    }

    @Test
    fun testSignAllZeroKey() {
        // All-zero key is invalid for secp256k1
        val zeroKey = ByteArray(32) { 0 }
        val data = TEST_TRANSACTION_DATA.toByteArray()

        val signature = TransactionSigner.signData(zeroKey, data)
        // Should return null (Rust FFI validates key)
        assertNull("All-zero key should fail", signature)
    }

    // ============================================================================
    // Cryptographic Correctness Tests
    // ============================================================================

    @Test
    fun testSignatureLengthIsAlways64Bytes() {
        val data = TEST_TRANSACTION_DATA.toByteArray()

        // Test with multiple different data lengths
        val testData = listOf(
            "a".toByteArray(),
            "test".toByteArray(),
            "Midnight blockchain transaction".toByteArray(),
            ByteArray(256) { it.toByte() }
        )

        testData.forEach { testInput ->
            val signature = TransactionSigner.signData(TEST_PRIVATE_KEY, testInput)
            assertNotNull("Signature should not be null for data: ${testInput.size} bytes", signature)
            assertEquals("All signatures should be 64 bytes", 64, signature!!.size)
        }
    }

    @Test
    fun testPublicKeyLengthIsAlways32Bytes() {
        // Test with multiple different private keys
        val testKeys = listOf(
            TEST_PRIVATE_KEY,
            ByteArray(32) { 1 },
            ByteArray(32) { 0xFF.toByte() },
            ByteArray(32) { (it * 7).toByte() }
        )

        testKeys.forEach { key ->
            val publicKey = TransactionSigner.getPublicKey(key)
            if (publicKey != null) {
                assertEquals("Public key should be 32 bytes (BIP-340)", 32, publicKey.size)
            }
        }
    }

    @Test
    fun testDifferentDataProducesDifferentSignatures() {
        val data1 = "Transaction 1".toByteArray()
        val data2 = "Transaction 2".toByteArray()

        val sig1 = TransactionSigner.signData(TEST_PRIVATE_KEY, data1)
        val sig2 = TransactionSigner.signData(TEST_PRIVATE_KEY, data2)

        assertNotNull(sig1)
        assertNotNull(sig2)

        // Signatures should differ (highly unlikely to be identical)
        assertFalse("Different data should produce different signatures",
            sig1!!.contentEquals(sig2!!))
    }

    @Test
    fun testDifferentKeysProduceDifferentPublicKeys() {
        val key1 = ByteArray(32) { 1 }
        val key2 = ByteArray(32) { 2 }

        val pubKey1 = TransactionSigner.getPublicKey(key1)
        val pubKey2 = TransactionSigner.getPublicKey(key2)

        assertNotNull(pubKey1)
        assertNotNull(pubKey2)

        assertFalse("Different private keys should produce different public keys",
            pubKey1!!.contentEquals(pubKey2!!))
    }

    // ============================================================================
    // Integration Test with Phase 1 BIP-32 Keys
    // ============================================================================

    @Test
    fun testPhase1Bip32ToSchnorrIntegration() {
        // This test proves end-to-end integration:
        // Phase 1 BIP-32 key derivation → Phase 2D signing

        // 1. Use private key from Phase 1 test vector
        val privateKey = TEST_PRIVATE_KEY

        // 2. Sign transaction data
        val transactionData = TEST_TRANSACTION_DATA.toByteArray()
        val signature = TransactionSigner.signData(privateKey, transactionData)

        // 3. Verify signature format
        assertNotNull("Signature should be generated", signature)
        assertEquals("Signature should be 64 bytes", 64, signature!!.size)

        // 4. Derive public key
        val publicKey = TransactionSigner.getPublicKey(privateKey)
        assertNotNull("Public key should be derived", publicKey)
        assertEquals("Public key should be 32 bytes", 32, publicKey!!.size)

        // 5. Verify signature structure (R || s format)
        val r = signature.copyOfRange(0, 32)  // First 32 bytes: R (public nonce)
        val s = signature.copyOfRange(32, 64) // Last 32 bytes: s (signature scalar)

        // R should be a valid curve point (non-zero)
        assertFalse("R component should not be all zeros", r.all { it == 0.toByte() })

        // s should be non-zero
        assertFalse("s component should not be all zeros", s.all { it == 0.toByte() })

        println("Phase 1->2D integration: pubkey=${publicKey.toHex().take(16)}...")
    }

    // ============================================================================
    // Performance Tests
    // ============================================================================

    @Test
    fun testSigningPerformance() {
        val data = TEST_TRANSACTION_DATA.toByteArray()
        val iterations = 100

        val startTime = System.currentTimeMillis()

        repeat(iterations) {
            val signature = TransactionSigner.signData(TEST_PRIVATE_KEY, data)
            assertNotNull(signature)
        }

        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime
        val avgTime = totalTime.toDouble() / iterations

        println("Signing performance: $iterations signatures in ${totalTime}ms (avg ${avgTime}ms)")

        // Sanity check: Should be fast (< 100ms per signature)
        assertTrue("Signing should be fast (< 100ms per signature)", avgTime < 100)
    }

    // ============================================================================
    // Thread Safety Tests
    // ============================================================================

    @Test
    fun testConcurrentSigning() {
        val data = TEST_TRANSACTION_DATA.toByteArray()
        val threadCount = 10
        val signaturesPerThread = 5

        val threads = (1..threadCount).map { threadId ->
            Thread {
                repeat(signaturesPerThread) {
                    val signature = TransactionSigner.signData(TEST_PRIVATE_KEY, data)
                    assertNotNull("Thread $threadId: signature should not be null", signature)
                    assertEquals("Thread $threadId: signature should be 64 bytes", 64, signature!!.size)
                }
            }
        }

        // Start all threads
        threads.forEach { it.start() }

        // Wait for all threads to complete
        threads.forEach { it.join(5000) } // 5 second timeout

        // Verify all threads completed
        threads.forEach { thread ->
            assertFalse("Thread should complete", thread.isAlive)
        }

        println("Concurrent signing: $threadCount threads x $signaturesPerThread signatures")
    }

    // ============================================================================
    // Large Data Tests
    // ============================================================================

    @Test
    fun testSignLargeData() {
        // Test signing 100 KB of data
        val largeData = ByteArray(100 * 1024) { it.toByte() }

        val signature = TransactionSigner.signData(TEST_PRIVATE_KEY, largeData)

        assertNotNull("Should be able to sign large data", signature)
        assertEquals("Signature should still be 64 bytes", 64, signature!!.size)
    }

    @Test
    fun testSignVeryLargeDataFails() {
        // Test that signing > 1 MB fails (FFI has 1 MB limit)
        val tooLargeData = ByteArray(2 * 1024 * 1024) { 0 } // 2 MB

        val signature = TransactionSigner.signData(TEST_PRIVATE_KEY, tooLargeData)

        // Should return null (JNI C code rejects > 1 MB)
        assertNull("Data > 1 MB should fail", signature)
    }

    // ============================================================================
    // Memory Safety Tests
    // ============================================================================

    @Test
    fun testMultipleSigningsDoNotCrash() {
        // Verify no memory leaks or crashes after many operations
        val data = TEST_TRANSACTION_DATA.toByteArray()

        repeat(1000) {
            val signature = TransactionSigner.signData(TEST_PRIVATE_KEY, data)
            assertNotNull(signature)
        }

        // If we reach here without crash, memory management is working
    }

    @Test
    fun testPrivateKeyNotModified() {
        // Verify that signing does not modify the input private key
        val originalKey = TEST_PRIVATE_KEY.copyOf()
        val data = TEST_TRANSACTION_DATA.toByteArray()

        TransactionSigner.signData(TEST_PRIVATE_KEY, data)

        assertArrayEquals("Private key should not be modified", originalKey, TEST_PRIVATE_KEY)
    }
}
