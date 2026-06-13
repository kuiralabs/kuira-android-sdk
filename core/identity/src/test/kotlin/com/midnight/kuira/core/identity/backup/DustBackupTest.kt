package com.midnight.kuira.core.identity.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit coverage for the dust cloud-backup crypto + bundle codec (JVM, no device). */
class DustBackupTest {

    // ── SeedDerivedKeyDeriver ────────────────────────────────────────────────

    @Test
    fun `key derivation is deterministic and 32 bytes`() {
        val ikm = ByteArray(32) { it.toByte() }
        val k1 = SeedDerivedKeyDeriver.deriveDustBackupKey(ikm)
        val k2 = SeedDerivedKeyDeriver.deriveDustBackupKey(ikm)
        assertEquals(32, k1.size)
        assertArrayEquals(k1, k2)
    }

    @Test
    fun `different seeds derive different keys`() {
        val a = SeedDerivedKeyDeriver.deriveDustBackupKey(ByteArray(32) { 1 })
        val b = SeedDerivedKeyDeriver.deriveDustBackupKey(ByteArray(32) { 2 })
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `app-state key is deterministic, 32 bytes, and domain-separated from dust`() {
        val ikm = ByteArray(32) { it.toByte() }
        val k1 = SeedDerivedKeyDeriver.deriveAppStateBackupKey(ikm)
        val k2 = SeedDerivedKeyDeriver.deriveAppStateBackupKey(ikm)
        assertEquals(32, k1.size)
        assertArrayEquals("same seed → same app-state key", k1, k2)
        // Same seed must NOT yield the same key as the dust backup — domain
        // separation, so a leak of one backup key can't decrypt the other.
        assertFalse(
            "app-state key must differ from dust key for the same seed",
            k1.contentEquals(SeedDerivedKeyDeriver.deriveDustBackupKey(ikm)),
        )
    }

    // ── DustBackupEncryptor ──────────────────────────────────────────────────

    @Test
    fun `encrypt then decrypt round-trips a large payload`() {
        val key = SeedDerivedKeyDeriver.deriveDustBackupKey(ByteArray(32) { 7 })
        val plaintext = ByteArray(600_000) { (it * 31).toByte() } // ~500KB-scale, past the 2KB cap
        val blob = DustBackupEncryptor.encrypt(key, plaintext)
        val out = DustBackupEncryptor.decrypt(key, blob)
        assertArrayEquals(plaintext, out)
    }

    @Test
    fun `decrypt with wrong key throws`() {
        val key = SeedDerivedKeyDeriver.deriveDustBackupKey(ByteArray(32) { 7 })
        val wrong = SeedDerivedKeyDeriver.deriveDustBackupKey(ByteArray(32) { 8 })
        val blob = DustBackupEncryptor.encrypt(key, "hello".toByteArray())
        assertThrows(BackupDecryptionException::class.java) { DustBackupEncryptor.decrypt(wrong, blob) }
    }

    @Test
    fun `tampered blob fails AEAD`() {
        val key = SeedDerivedKeyDeriver.deriveDustBackupKey(ByteArray(32) { 7 })
        val blob = DustBackupEncryptor.encrypt(key, "hello world".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte() // flip a tag byte
        assertThrows(BackupDecryptionException::class.java) { DustBackupEncryptor.decrypt(key, blob) }
    }

    // ── DustCloudBundleCodec ─────────────────────────────────────────────────

    @Test
    fun `bundle round-trips multiple network entries`() {
        val entries = listOf(
            DustBackupEntry("mn_addr_preprod1abc", 921_634L, ByteArray(500_000) { (it and 0xFF).toByte() }),
            DustBackupEntry("mn_addr_undeployed1xyz", 313L, ByteArray(9_000) { 0x5A }),
        )
        val decoded = DustCloudBundleCodec.decode(DustCloudBundleCodec.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun `entryFor finds the matching address and returns null otherwise`() {
        val bytes = DustCloudBundleCodec.encode(
            listOf(DustBackupEntry("mn_addr_preprod1abc", 42L, byteArrayOf(1, 2, 3))),
        )
        assertEquals(42L, DustCloudBundleCodec.entryFor(bytes, "mn_addr_preprod1abc")?.lastEventId)
        assertNull(DustCloudBundleCodec.entryFor(bytes, "mn_addr_undeployed1xyz"))
    }

    @Test
    fun `full pipeline encode-encrypt-decrypt-decode survives`() {
        val key = SeedDerivedKeyDeriver.deriveDustBackupKey(ByteArray(32) { 3 })
        val entry = DustBackupEntry("mn_addr_preprod1abc", 921_634L, ByteArray(487_000) { (it * 7).toByte() })
        val blob = DustBackupEncryptor.encrypt(key, DustCloudBundleCodec.encode(listOf(entry)))
        val restored = DustCloudBundleCodec.entryFor(DustBackupEncryptor.decrypt(key, blob), entry.address)
        assertEquals(entry, restored)
        assertTrue(restored!!.stateBytes.contentEquals(entry.stateBytes))
    }
}
