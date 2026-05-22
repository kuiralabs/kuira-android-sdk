package com.midnight.kuira.core.identity.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the deterministic half of [SeedDeriver] — the entropy → seed
 * mapping. The PRF half (`derivePrfEntropy`) needs a real
 * Credential Manager so it's exercised by the in-app probe + an
 * integration test, not here.
 *
 * What matters at unit-test scope: given the same entropy, every
 * Kuira ecosystem app produces the same 32-byte seed. If this
 * mapping ever drifts, every passkey-derived wallet on every device
 * silently changes addresses.
 */
class SeedDeriverTest {

    @Test
    fun `entropyToBip39Seed produces 64 bytes`() {
        val seed = SeedDeriver.entropyToBip39Seed(ByteArray(32) { 0x42 })
        assertEquals("standard BIP-39 PBKDF2 seed is 64 bytes", 64, seed.size)
    }

    @Test
    fun `entropyToBip39Seed is deterministic`() {
        val entropy = ByteArray(32) { (it * 13).toByte() }
        val seed1 = SeedDeriver.entropyToBip39Seed(entropy)
        val seed2 = SeedDeriver.entropyToBip39Seed(entropy)
        assertArrayEquals(
            "same entropy must always yield the same seed — wallet identity depends on it",
            seed1, seed2,
        )
    }

    @Test
    fun `different entropy yields different seeds`() {
        val seedA = SeedDeriver.entropyToBip39Seed(ByteArray(32) { 0x01 })
        val seedB = SeedDeriver.entropyToBip39Seed(ByteArray(32) { 0x02 })
        assertFalse(
            "different entropy must yield different seeds",
            seedA.contentEquals(seedB),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `entropyToBip39Seed rejects wrong size`() {
        SeedDeriver.entropyToBip39Seed(ByteArray(16))
    }

    @Test
    fun `SEED_SALT is domain-separated from BACKUP_SALT`() {
        assertFalse(
            "SEED_SALT must differ from BACKUP_SALT — two purposes, two independent secrets from one passkey",
            SeedDeriver.SEED_SALT.contentEquals(SigilBackup.BACKUP_SALT),
        )
    }
}
