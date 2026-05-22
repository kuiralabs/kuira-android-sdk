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

    // Value-pin against the BIP-39 spec lives downstream in
    // HDWalletShieldedIntegrationTest, which pins
    // (mnemonic → bip39Seed → HD-derived key → shielded coinPublicKey)
    // against Midnight SDK reference values. A silent PBKDF2 drift
    // would break that test. Adding a value-pin here would duplicate
    // that coverage on the same invariant.

    @Test
    fun `PrfSeedMaterial wipe zeroes both arrays`() {
        // wipe() lives on the hot path: WalletPanelViewModel calls it
        // in a `finally` after handing entropy + seed to SeedVault. A
        // regression that quietly stopped wiping would leave seed
        // material resident in the ViewModel's heap until GC — pin
        // the invariant.
        val material = PrfSeedMaterial(
            entropy = ByteArray(32) { 0x42 },
            bip39Seed = ByteArray(64) { 0xAA.toByte() },
        )
        material.wipe()
        assertArrayEquals(
            "entropy must be fully zeroed",
            ByteArray(32),
            material.entropy,
        )
        assertArrayEquals(
            "bip39Seed must be fully zeroed",
            ByteArray(64),
            material.bip39Seed,
        )
    }

    @Test
    fun `PrfSeedMaterial wipe is idempotent`() {
        // Defensive: callers may invoke wipe more than once (e.g. a
        // `finally` after an early-return path). Must not throw.
        val material = PrfSeedMaterial(ByteArray(32), ByteArray(64))
        material.wipe()
        material.wipe()
    }

    @Test
    fun `SEED_SALT is domain-separated from BACKUP_SALT`() {
        assertFalse(
            "SEED_SALT must differ from BACKUP_SALT — two purposes, two independent secrets from one passkey",
            SeedDeriver.SEED_SALT.contentEquals(SigilBackup.BACKUP_SALT),
        )
    }
}
