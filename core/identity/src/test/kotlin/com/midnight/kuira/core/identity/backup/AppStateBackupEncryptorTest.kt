package com.midnight.kuira.core.identity.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [AppStateBackupEncryptor] — AES-256-GCM encryption of the
 * post-PRF app-state blob.
 *
 * Verifies the v2 schema:
 *  - `[version=0x02][IV][ENC([metadataLen][metadata])]`
 *  - variable size (not padded — see KDoc rationale)
 *  - rejects v1 blobs + wrong-key + corrupted
 */
class AppStateBackupEncryptorTest {

    private val testKey = ByteArray(32) { (it + 0x50).toByte() }

    @Test
    fun `encrypt of empty metadata produces minimal blob`() {
        val blob = AppStateBackupEncryptor.encrypt(testKey, ByteArray(0))
        // 1 byte version + 12 byte IV + ENC([metaLen=0]) = 1 + 12 + 2 + 16 (GCM tag) = 31
        assertEquals("empty-metadata blob should be 31 bytes", 31, blob.size)
    }

    @Test
    fun `encrypt-decrypt roundtrip recovers empty metadata`() {
        val blob = AppStateBackupEncryptor.encrypt(testKey, ByteArray(0))
        val decrypted = AppStateBackupEncryptor.decrypt(blob, testKey)
        assertEquals(0, decrypted.size)
    }

    @Test
    fun `encrypt-decrypt roundtrip recovers app metadata bytes`() {
        val metadata = "kicks-match-witness-snapshot".toByteArray()
        val blob = AppStateBackupEncryptor.encrypt(testKey, metadata)
        val decrypted = AppStateBackupEncryptor.decrypt(blob, testKey)
        assertArrayEquals(metadata, decrypted)
    }

    @Test
    fun `blob version byte is 0x02`() {
        val blob = AppStateBackupEncryptor.encrypt(testKey, byteArrayOf(0x42))
        assertEquals(0x02.toByte(), blob[0])
    }

    @Test
    fun `IV is random across encryptions`() {
        // Same key + same metadata, two encryptions: the IV (bytes 1..13)
        // must differ. Otherwise GCM reuse would catastrophically leak
        // plaintext-XOR.
        val metadata = byteArrayOf(0x11, 0x22, 0x33)
        val a = AppStateBackupEncryptor.encrypt(testKey, metadata)
        val b = AppStateBackupEncryptor.encrypt(testKey, metadata)
        val ivA = a.copyOfRange(1, 13)
        val ivB = b.copyOfRange(1, 13)
        assertEquals(
            "IV reuse across encryptions would be catastrophic; got the same IV twice",
            false,
            ivA.contentEquals(ivB),
        )
    }

    @Test
    fun `decrypt rejects v1 legacy blob`() {
        // Construct a minimal "looks v1" blob — version byte 0x01,
        // garbage after. The encryptor must refuse, regardless of key.
        val legacyV1 = byteArrayOf(0x01) + ByteArray(60)
        try {
            AppStateBackupEncryptor.decrypt(legacyV1, testKey)
            fail("expected BackupDecryptionException for v1 blob")
        } catch (e: BackupDecryptionException) {
            // Message references the unsupported version.
        }
    }

    @Test
    fun `decrypt rejects wrong key`() {
        val blob = AppStateBackupEncryptor.encrypt(testKey, "secret".toByteArray())
        val wrongKey = ByteArray(32) { (it + 0x99).toByte() }
        try {
            AppStateBackupEncryptor.decrypt(blob, wrongKey)
            fail("expected BackupDecryptionException for wrong key")
        } catch (e: BackupDecryptionException) {
            // AEAD tag verification fails.
        }
    }

    @Test
    fun `decrypt rejects truncated blob`() {
        val blob = AppStateBackupEncryptor.encrypt(testKey, "abc".toByteArray())
        val truncated = blob.copyOfRange(0, blob.size - 10)
        try {
            AppStateBackupEncryptor.decrypt(truncated, testKey)
            fail("expected BackupDecryptionException for truncated blob")
        } catch (e: BackupDecryptionException) {
            // Either size check or AEAD verification fails.
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt rejects oversize metadata`() {
        AppStateBackupEncryptor.encrypt(
            testKey,
            ByteArray(AppStateBackupEncryptor.MAX_METADATA_SIZE + 1),
        )
    }

    @Test
    fun `encrypt at MAX_METADATA_SIZE boundary works`() {
        val large = ByteArray(AppStateBackupEncryptor.MAX_METADATA_SIZE) { it.toByte() }
        val blob = AppStateBackupEncryptor.encrypt(testKey, large)
        val decrypted = AppStateBackupEncryptor.decrypt(blob, testKey)
        assertArrayEquals(large, decrypted)
    }
}
