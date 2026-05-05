package com.midnight.kuira.core.identity.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests for BackupEncryptor — fixed-size AES-256-GCM seed encryption.
 */
class BackupEncryptorTest {

    private val testEntropy = ByteArray(32) { (it + 0x10).toByte() }
    private val testSeed = ByteArray(64) { (it + 0x30).toByte() }
    private val testKey = ByteArray(32) { (it + 0x50).toByte() }

    @Test
    fun `encrypt produces fixed-size blob`() {
        val blob = BackupEncryptor.encrypt(testEntropy, testSeed, testKey)
        assertEquals(BackupEncryptor.BLOB_SIZE, blob.size)
    }

    @Test
    fun `encrypt-decrypt roundtrip recovers seed`() {
        val blob = BackupEncryptor.encrypt(testEntropy, testSeed, testKey)
        val decrypted = BackupEncryptor.decrypt(blob, testKey)

        try {
            assertArrayEquals(testEntropy, decrypted.entropy)
            assertArrayEquals(testSeed, decrypted.bip39Seed)
        } finally {
            decrypted.wipe()
        }
    }

    @Test
    fun `blob size is always 525 bytes regardless of content`() {
        val blob1 = BackupEncryptor.encrypt(ByteArray(32), ByteArray(64), testKey)
        val blob2 = BackupEncryptor.encrypt(ByteArray(32) { 0xFF.toByte() }, ByteArray(64) { 0xFF.toByte() }, testKey)

        assertEquals(BackupEncryptor.BLOB_SIZE, blob1.size)
        assertEquals(BackupEncryptor.BLOB_SIZE, blob2.size)
        assertEquals(blob1.size, blob2.size)
    }

    @Test
    fun `each encryption produces different ciphertext (random IV)`() {
        val blob1 = BackupEncryptor.encrypt(testEntropy, testSeed, testKey)
        val blob2 = BackupEncryptor.encrypt(testEntropy, testSeed, testKey)

        // Same plaintext + same key but different IV → different ciphertext
        assertFalse(blob1.contentEquals(blob2))
    }

    @Test
    fun `blob starts with version byte 0x01`() {
        val blob = BackupEncryptor.encrypt(testEntropy, testSeed, testKey)
        assertEquals(0x01.toByte(), blob[0])
    }

    @Test(expected = BackupDecryptionException::class)
    fun `decrypt with wrong key throws`() {
        val blob = BackupEncryptor.encrypt(testEntropy, testSeed, testKey)
        val wrongKey = ByteArray(32) { 0xFF.toByte() }
        BackupEncryptor.decrypt(blob, wrongKey)
    }

    @Test(expected = BackupDecryptionException::class)
    fun `decrypt with truncated blob throws`() {
        BackupEncryptor.decrypt(ByteArray(100), testKey)
    }

    @Test(expected = BackupDecryptionException::class)
    fun `decrypt with wrong version throws`() {
        val blob = BackupEncryptor.encrypt(testEntropy, testSeed, testKey)
        blob[0] = 0x02 // wrong version
        BackupEncryptor.decrypt(blob, testKey)
    }

    @Test(expected = BackupDecryptionException::class)
    fun `decrypt with corrupted ciphertext throws`() {
        val blob = BackupEncryptor.encrypt(testEntropy, testSeed, testKey)
        // Flip a byte in the ciphertext area
        blob[20] = (blob[20].toInt() xor 0xFF).toByte()
        BackupEncryptor.decrypt(blob, testKey)
    }

    @Test
    fun `wipe clears decrypted backup`() {
        val blob = BackupEncryptor.encrypt(testEntropy, testSeed, testKey)
        val decrypted = BackupEncryptor.decrypt(blob, testKey)

        decrypted.wipe()

        // After wipe, arrays should be zeroed
        assert(decrypted.entropy.all { it == 0.toByte() })
        assert(decrypted.bip39Seed.all { it == 0.toByte() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt rejects wrong entropy size`() {
        BackupEncryptor.encrypt(ByteArray(16), testSeed, testKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt rejects wrong seed size`() {
        BackupEncryptor.encrypt(testEntropy, ByteArray(32), testKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt rejects wrong key size`() {
        BackupEncryptor.encrypt(testEntropy, testSeed, ByteArray(16))
    }

    @Test
    fun `full pipeline - PRF derive then encrypt-decrypt`() {
        // Simulates the real flow: PRF output → HKDF → AES key → encrypt → decrypt
        val fakePrfOutput = ByteArray(32) { (it * 3 + 7).toByte() }
        val aesKey = PrfKeyDeriver.deriveKey(fakePrfOutput)

        val blob = BackupEncryptor.encrypt(testEntropy, testSeed, aesKey)
        assertEquals(BackupEncryptor.BLOB_SIZE, blob.size)

        val decrypted = BackupEncryptor.decrypt(blob, aesKey)
        try {
            assertArrayEquals(testEntropy, decrypted.entropy)
            assertArrayEquals(testSeed, decrypted.bip39Seed)
        } finally {
            decrypted.wipe()
            aesKey.fill(0)
        }
    }
}
