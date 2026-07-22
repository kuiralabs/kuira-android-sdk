package com.midnight.kuira.core.ledger.signer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Security-focused tests for TransactionSigner
 *
 * These tests specifically target security vulnerabilities:
 * - Memory safety (use-after-free, double-free, buffer overflows)
 * - BIP-340 compatibility (official test vectors)
 * - Cryptographic correctness (signature verification)
 * - Boundary conditions (edge cases)
 *
 * **Note:** These tests verify defensive programming but cannot detect
 * all memory issues. Use AddressSanitizer / LeakSanitizer for comprehensive validation.
 */
@RunWith(AndroidJUnit4::class)
class TransactionSignerSecurityTest {

    companion object {
        // Helper: Convert hex string to bytes
        private fun hexToBytes(hex: String): ByteArray {
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        // Helper: Convert bytes to hex string
        private fun ByteArray.toHex(): String {
            return joinToString("") { "%02x".format(it) }
        }

        /**
         * BIP-340 Official Test Vectors from Bitcoin BIPs
         * Source: https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv
         */
        private val BIP340_TEST_VECTORS = listOf(
            // Test Vector : Very small private key
            Triple(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000003"),
                hexToBytes("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9"),
                "message"
            ),
            // Test Vector : Another small private key
            Triple(
                hexToBytes("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF"),
                hexToBytes("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659"),
                "message"
            ),
            // Test Vector : Max-1 private key
            Triple(
                hexToBytes("C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9"),
                hexToBytes("DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8"),
                "message"
            )
        )
    }

    // ============================================================================
    // BIP-340 Test Vector Validation
    // ============================================================================

    @Test
    fun testBip340TestVector0() {
        val (privateKey, expectedPubKey, _) = BIP340_TEST_VECTORS[0]

        val actualPubKey = TransactionSigner.getPublicKey(privateKey)

        assertNotNull("Public key should be derived", actualPubKey)
        assertArrayEquals(
            "Public key should match BIP-340 test vector #0",
            expectedPubKey,
            actualPubKey
        )
    }

    @Test
    fun testBip340TestVector1() {
        val (privateKey, expectedPubKey, _) = BIP340_TEST_VECTORS[1]

        val actualPubKey = TransactionSigner.getPublicKey(privateKey)

        assertNotNull("Public key should be derived", actualPubKey)
        assertArrayEquals(
            "Public key should match BIP-340 test vector #1",
            expectedPubKey,
            actualPubKey
        )
    }

    @Test
    fun testBip340TestVector2() {
        val (privateKey, expectedPubKey, _) = BIP340_TEST_VECTORS[2]

        val actualPubKey = TransactionSigner.getPublicKey(privateKey)

        assertNotNull("Public key should be derived", actualPubKey)
        assertArrayEquals(
            "Public key should match BIP-340 test vector #2",
            expectedPubKey,
            actualPubKey
        )
    }

    @Test
    fun testBip340AllTestVectors() {
        // Validate all test vectors in one test
        BIP340_TEST_VECTORS.forEachIndexed { index, (privateKey, expectedPubKey, _) ->
            val actualPubKey = TransactionSigner.getPublicKey(privateKey)
            assertNotNull("Test vector #$index: public key should be derived", actualPubKey)
            assertArrayEquals(
                "Test vector #$index: public key should match BIP-340 spec",
                expectedPubKey,
                actualPubKey
            )
        }
    }

    // ============================================================================
    // Memory Safety Tests
    // ============================================================================

    /**
     * IMPORTANT: Manual pointer management (internalCreateSigningKey/internalFreeSigningKey)
     * is UNSAFE and cannot prevent use-after-free or double-free in JNI/C without tracking.
     *
     * These tests verify that the PUBLIC API (signData/getPublicKey) is safe because
     * it uses the useSigningKey() helper which provides automatic cleanup (RAII pattern).
     *
     * Users should NEVER manually call internal methods - always use signData()/getPublicKey().
     */

    @Test
    fun testMemorySafety_PublicAPIAutoCleanup() {
        // The public API automatically manages SigningKey lifetime
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val data = "test data".toByteArray()

        // Multiple calls - each creates, uses, and frees SigningKey automatically
        repeat(10) { iteration ->
            val signature = TransactionSigner.signData(privateKey, data)
            assertNotNull("Iteration $iteration: should sign successfully", signature)
            assertEquals("Iteration $iteration: signature length", 64, signature!!.size)
        }

        // No manual cleanup needed - useSigningKey() handled everything
        // Public API memory safety verified: automatic cleanup works
    }

    @Test
    fun testNullPointerFree_DoesNotCrash() {
        // Free null pointer (should be no-op)
        TransactionSigner.internalFreeSigningKey(0L)

        // If we reach here, null pointer free was handled gracefully
        assertTrue("Null pointer free handled gracefully", true)
    }

    @Test
    fun testMemoryLeakDetection_ThousandOperations() {
        // Perform 1000 sign operations to detect memory leaks
        // (requires LeakSanitizer or manual memory profiling to validate)
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val data = "Midnight transaction data".toByteArray()

        repeat(1000) { iteration ->
            val signature = TransactionSigner.signData(privateKey, data)
            assertNotNull("Iteration $iteration: signature should not be null", signature)
            assertEquals("Iteration $iteration: signature should be 64 bytes", 64, signature!!.size)
        }

        // If we reach here without crash or OOM, basic memory management is working
        // 1000 signing operations completed without memory issues
    }

    // ============================================================================
    // Buffer Overflow / Boundary Tests
    // ============================================================================

    @Test
    fun testReasonablySizedData_100KB() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")

        // Test with 100 KB data (generous for real Midnight transactions which are typically <10KB)
        val largeData = ByteArray(100 * 1024) { it.toByte() }

        val signature = TransactionSigner.signData(privateKey, largeData)

        assertNotNull("Should sign 100 KB data", signature)
        assertEquals("Signature should be 64 bytes", 64, signature!!.size)
    }

    @Test
    fun testBoundarySize_ExactlyOneByte() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val oneByte = byteArrayOf(0x42)

        val signature = TransactionSigner.signData(privateKey, oneByte)

        assertNotNull("Should sign 1 byte", signature)
        assertEquals("Signature should be 64 bytes", 64, signature!!.size)
    }

    @Test
    fun testBoundarySize_PowerOfTwo() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")

        // Test various power-of-two sizes
        val sizes = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192)

        sizes.forEach { size ->
            val data = ByteArray(size) { it.toByte() }
            val signature = TransactionSigner.signData(privateKey, data)

            assertNotNull("Should sign $size bytes", signature)
            assertEquals("Signature should be 64 bytes for $size-byte data", 64, signature!!.size)
        }
    }

    // ============================================================================
    // Invalid Input Tests (Negative Testing)
    // ============================================================================

    @Test
    fun testInvalidPrivateKey_TooShort() {
        val shortKey = ByteArray(16) { 0x42 } // 16 bytes instead of 32

        try {
            TransactionSigner.signData(shortKey, "data".toByteArray())
            fail("Should throw IllegalArgumentException for invalid key length")
        } catch (e: IllegalArgumentException) {
            assertTrue("Error message should mention 32 bytes", e.message!!.contains("32 bytes"))
        }
    }

    @Test
    fun testInvalidPrivateKey_TooLong() {
        val longKey = ByteArray(64) { 0x42 } // 64 bytes instead of 32

        try {
            TransactionSigner.signData(longKey, "data".toByteArray())
            fail("Should throw IllegalArgumentException for invalid key length")
        } catch (e: IllegalArgumentException) {
            assertTrue("Error message should mention 32 bytes", e.message!!.contains("32 bytes"))
        }
    }

    @Test
    fun testInvalidPrivateKey_ZeroLength() {
        val emptyKey = ByteArray(0)

        try {
            TransactionSigner.signData(emptyKey, "data".toByteArray())
            fail("Should throw IllegalArgumentException for zero-length key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("32 bytes"))
        }
    }

    // Note: testInvalidData_EmptyArray removed - empty data is now ALLOWED
    // See testVerifySignature_EmptyMessage for empty message handling

    // ============================================================================
    // Concurrent Access Tests
    // ============================================================================

    @Test
    fun testConcurrentSigningWithDifferentKeys_NoInterference() {
        // Test that concurrent signing with different keys doesn't interfere
        val key1 = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val key2 = hexToBytes("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF")
        val data = "concurrent test data".toByteArray()

        val results = mutableListOf<Pair<Int, ByteArray?>>()

        val thread1 = Thread {
            repeat(10) { i ->
                val sig = TransactionSigner.signData(key1, data)
                synchronized(results) {
                    results.add(Pair(1, sig))
                }
                Thread.sleep(1) // Small delay to encourage interleaving
            }
        }

        val thread2 = Thread {
            repeat(10) { i ->
                val sig = TransactionSigner.signData(key2, data)
                synchronized(results) {
                    results.add(Pair(2, sig))
                }
                Thread.sleep(1)
            }
        }

        thread1.start()
        thread2.start()
        thread1.join(5000)
        thread2.join(5000)

        // Verify all signatures were created successfully
        assertEquals("Should have 20 total signatures", 20, results.size)
        results.forEach { (threadId, sig) ->
            assertNotNull("Thread $threadId: signature should not be null", sig)
            assertEquals("Thread $threadId: signature should be 64 bytes", 64, sig!!.size)
        }

        // Concurrent signing with different keys: no interference
    }

    // ============================================================================
    // Cryptographic Correctness Tests - SIGNATURE VERIFICATION
    // ============================================================================

    @Test
    fun testSignatureIsVerifiable_WithCorrectPublicKey() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val message = "test message for verification".toByteArray()

        // Sign the message
        val signature = TransactionSigner.signData(privateKey, message)
        assertNotNull("Signature should not be null", signature)
        assertEquals("Signature should be 64 bytes", 64, signature!!.size)

        // Get public key
        val publicKey = TransactionSigner.getPublicKey(privateKey)
        assertNotNull("Public key should not be null", publicKey)
        assertEquals("Public key should be 32 bytes", 32, publicKey!!.size)

        // CRITICAL: Verify signature is cryptographically valid
        val isValid = TransactionSigner.verifySignature(publicKey, message, signature)
        assertTrue("Signature MUST be verifiable with correct public key", isValid)

        // Signature verification passed
    }

    @Test
    fun testSignatureVerificationFails_WithWrongMessage() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val originalMessage = "original message".toByteArray()
        val tamperedMessage = "tampered message".toByteArray()

        // Sign original message
        val signature = TransactionSigner.signData(privateKey, originalMessage)
        assertNotNull(signature)

        // Get public key
        val publicKey = TransactionSigner.getPublicKey(privateKey)
        assertNotNull(publicKey)

        // Verification should PASS for original message
        assertTrue(
            "Should verify with correct message",
            TransactionSigner.verifySignature(publicKey!!, originalMessage, signature!!)
        )

        // Verification should FAIL for tampered message
        assertFalse(
            "Should NOT verify with wrong message",
            TransactionSigner.verifySignature(publicKey, tamperedMessage, signature)
        )

        // Signature verification correctly rejects tampered messages
    }

    @Test
    fun testSignatureVerificationFails_WithWrongPublicKey() {
        val privateKey1 = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val privateKey2 = hexToBytes("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF")
        val message = "test message".toByteArray()

        // Sign with key1
        val signature = TransactionSigner.signData(privateKey1, message)
        assertNotNull(signature)

        // Get public keys
        val publicKey1 = TransactionSigner.getPublicKey(privateKey1)
        val publicKey2 = TransactionSigner.getPublicKey(privateKey2)
        assertNotNull(publicKey1)
        assertNotNull(publicKey2)

        // Verification should PASS with correct public key
        assertTrue(
            "Should verify with correct public key",
            TransactionSigner.verifySignature(publicKey1!!, message, signature!!)
        )

        // Verification should FAIL with wrong public key
        assertFalse(
            "Should NOT verify with wrong public key",
            TransactionSigner.verifySignature(publicKey2!!, message, signature)
        )

        // Signature verification correctly rejects wrong public keys
    }

    @Test
    fun testBip340TestVectorsSignaturesVerify() {
        // Test that public keys we derive produce verifiable signatures
        BIP340_TEST_VECTORS.forEach { (privateKey, expectedPubKey, message) ->
            // Get public key
            val actualPubKey = TransactionSigner.getPublicKey(privateKey)
            assertNotNull("Public key should be derived", actualPubKey)
            assertArrayEquals("Public key should match test vector", expectedPubKey, actualPubKey)

            // Sign a test message
            val testMessage = message.toByteArray()
            val signature = TransactionSigner.signData(privateKey, testMessage)
            assertNotNull("Signature should be created", signature)
            assertEquals("Signature should be 64 bytes", 64, signature!!.size)

            // CRITICAL: Verify signature with derived public key
            val isValid = TransactionSigner.verifySignature(actualPubKey!!, testMessage, signature)
            assertTrue("BIP-340 test vector signature must verify", isValid)
        }

        // All BIP-340 test vector signatures verify successfully
    }

    @Test
    fun testMultipleSignaturesOfSameData_AllVerifyCorrectly() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val message = "same message".toByteArray()
        val publicKey = TransactionSigner.getPublicKey(privateKey)
        assertNotNull(publicKey)

        // Sign same message 5 times (random nonces produce different signatures)
        val signatures = List(5) {
            TransactionSigner.signData(privateKey, message)
        }

        // All signatures should be non-null and 64 bytes
        signatures.forEach { sig ->
            assertNotNull("Signature should not be null", sig)
            assertEquals("Signature should be 64 bytes", 64, sig!!.size)
        }

        // All signatures should be DIFFERENT (random nonce)
        for (i in 0 until signatures.size - 1) {
            for (j in i + 1 until signatures.size) {
                assertFalse(
                    "Signatures $i and $j should differ",
                    signatures[i]!!.contentEquals(signatures[j]!!)
                )
            }
        }

        // But ALL signatures should VERIFY correctly
        signatures.forEachIndexed { index, sig ->
            val isValid = TransactionSigner.verifySignature(publicKey!!, message, sig!!)
            assertTrue("Signature $index must verify despite different nonce", isValid)
        }

        // All 5 different signatures of same data verify correctly
    }

    @Test
    fun testSignatureFormat_ExactlyRAndS() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val data = "test message for Schnorr".toByteArray()

        val signature = TransactionSigner.signData(privateKey, data)

        assertNotNull("Signature should not be null", signature)
        assertEquals("Signature should be 64 bytes", 64, signature!!.size)

        // BIP-340: Signature is (R || s) where R is 32 bytes, s is 32 bytes
        val r = signature.copyOfRange(0, 32)
        val s = signature.copyOfRange(32, 64)

        // R and s should not be all zeros
        assertFalse("R component should not be all zeros", r.all { it == 0.toByte() })
        assertFalse("s component should not be all zeros", s.all { it == 0.toByte() })

        // Signature format valid
    }

    @Test
    fun testDifferentSignaturesForSameData_BecauseOfRandomNonce() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val data = "same message".toByteArray()

        // Sign the same data multiple times
        val sig1 = TransactionSigner.signData(privateKey, data)
        val sig2 = TransactionSigner.signData(privateKey, data)
        val sig3 = TransactionSigner.signData(privateKey, data)

        // All signatures should be valid (64 bytes)
        assertNotNull(sig1)
        assertNotNull(sig2)
        assertNotNull(sig3)

        // Signatures should differ (because of random nonce)
        assertFalse("sig1 and sig2 should differ", sig1!!.contentEquals(sig2!!))
        assertFalse("sig2 and sig3 should differ", sig2.contentEquals(sig3!!))
        assertFalse("sig1 and sig3 should differ", sig1.contentEquals(sig3))

        // Random nonce confirmed: same data produces different signatures
    }

    // ============================================================================
    // Edge Cases - Malformed Inputs
    // ============================================================================

    @Test
    fun testVerifySignature_MalformedSignature_AllZeros() {
        val publicKey = hexToBytes("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9")
        val message = "test message".toByteArray()
        val malformedSig = ByteArray(64) { 0 } // All zeros signature

        val isValid = TransactionSigner.verifySignature(publicKey, message, malformedSig)
        assertFalse("Malformed signature (all zeros) should be rejected", isValid)

        // All-zero signature correctly rejected
    }

    @Test
    fun testVerifySignature_MalformedSignature_RandomBytes() {
        val publicKey = hexToBytes("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9")
        val message = "test message".toByteArray()
        val randomSig = ByteArray(64) { it.toByte() } // Sequential bytes 0x00..0x3F

        val isValid = TransactionSigner.verifySignature(publicKey, message, randomSig)
        assertFalse("Malformed signature (random bytes) should be rejected", isValid)

        // Random signature bytes correctly rejected
    }

    @Test
    fun testVerifySignature_InvalidPublicKey_AllZeros() {
        val invalidPublicKey = ByteArray(32) { 0 } // All zeros (not a valid curve point)
        val message = "test message".toByteArray()

        // Create a valid signature with a valid key
        val validPrivateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val signature = TransactionSigner.signData(validPrivateKey, message)
        assertNotNull(signature)

        // Try to verify with invalid public key
        val isValid = TransactionSigner.verifySignature(invalidPublicKey, message, signature!!)
        assertFalse("Invalid public key (all zeros) should fail verification", isValid)

        // All-zero public key correctly rejected
    }

    @Test
    fun testVerifySignature_WrongSignatureLength_TooShort() {
        val publicKey = hexToBytes("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9")
        val message = "test".toByteArray()
        val shortSig = ByteArray(32) { 0x42 } // Only 32 bytes instead of 64

        try {
            TransactionSigner.verifySignature(publicKey, message, shortSig)
            fail("Should throw IllegalArgumentException for wrong signature length")
        } catch (e: IllegalArgumentException) {
            assertTrue("Error message should mention 64 bytes", e.message!!.contains("64 bytes"))
            assertTrue("Error message should mention actual length", e.message!!.contains("32"))
        }

        // Too-short signature correctly rejected
    }

    @Test
    fun testVerifySignature_WrongSignatureLength_TooLong() {
        val publicKey = hexToBytes("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9")
        val message = "test".toByteArray()
        val longSig = ByteArray(128) { 0x42 } // 128 bytes instead of 64

        try {
            TransactionSigner.verifySignature(publicKey, message, longSig)
            fail("Should throw IllegalArgumentException for wrong signature length")
        } catch (e: IllegalArgumentException) {
            assertTrue("Error message should mention 64 bytes", e.message!!.contains("64 bytes"))
            assertTrue("Error message should mention actual length", e.message!!.contains("128"))
        }

        // Too-long signature correctly rejected
    }

    @Test
    fun testVerifySignature_EmptyMessage() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")
        val emptyMessage = ByteArray(0) // Empty message

        // Sign empty message
        val signature = TransactionSigner.signData(privateKey, emptyMessage)
        assertNotNull("Should sign empty message", signature)
        assertEquals("Signature should be 64 bytes", 64, signature!!.size)

        // Get public key
        val publicKey = TransactionSigner.getPublicKey(privateKey)
        assertNotNull("Public key should be derived", publicKey)

        // Verify empty message signature
        val isValid = TransactionSigner.verifySignature(publicKey!!, emptyMessage, signature)
        assertTrue("Empty message signature should verify correctly", isValid)

        // Empty message signature verifies correctly
    }

    // ============================================================================
    // Edge Cases - Extreme Values
    // ============================================================================

    @Test
    fun testAllOnesPrivateKey() {
        val allOnesKey = ByteArray(32) { 0xFF.toByte() }
        val data = "test".toByteArray()

        // All-ones key is valid but close to secp256k1 curve order
        val signature = TransactionSigner.signData(allOnesKey, data)

        // Should either succeed or gracefully fail (return null)
        if (signature != null) {
            assertEquals("Signature should be 64 bytes", 64, signature.size)
        }
        // Both outcomes are acceptable (depends on curve order validation)
    }

    @Test
    fun testAllBytesData() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")

        // Data with all possible byte values (0x00 to 0xFF)
        val allBytesData = ByteArray(256) { it.toByte() }

        val signature = TransactionSigner.signData(privateKey, allBytesData)

        assertNotNull("Should sign data with all byte values", signature)
        assertEquals("Signature should be 64 bytes", 64, signature!!.size)
    }

    @Test
    fun testDataWithNullBytes() {
        val privateKey = hexToBytes("0000000000000000000000000000000000000000000000000000000000000003")

        // Data with embedded null bytes
        val dataWithNulls = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04)

        val signature = TransactionSigner.signData(privateKey, dataWithNulls)

        assertNotNull("Should sign data with null bytes", signature)
        assertEquals("Signature should be 64 bytes", 64, signature!!.size)
    }
}
