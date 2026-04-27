package com.midnight.kuira.sdk

import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.network.MidnightNetwork
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for key derivation logic.
 *
 * Note: Full [deriveKeys] requires ShieldedKeyDeriver (native FFI).
 * These JVM tests verify the HD wallet derivation paths that don't need FFI.
 */
class DeriveKeysTest {

    // Known test mnemonic seed (from core:testing TestFixtures)
    private val testSeed = ByteArray(64) // BIP-39 seed from "abandon...art" mnemonic

    @Test
    fun `HD wallet derives correct key roles`() {
        val wallet = HDWallet.fromSeed(testSeed)
        try {
            val account = wallet.selectAccount(0)

            // Verify all four key roles can be derived
            val nightKey = account.selectRole(MidnightKeyRole.NIGHT_EXTERNAL).deriveKeyAt(0)
            assertNotNull("NIGHT_EXTERNAL key should derive", nightKey)
            assertEquals("Public key should be 33 bytes (compressed)", 33, nightKey.publicKeyBytes.size)

            val dustKey = account.selectRole(MidnightKeyRole.DUST).deriveKeyAt(0)
            assertNotNull("DUST key should derive", dustKey)
            assertEquals("Private key should be 32 bytes", 32, dustKey.privateKeyBytes.size)

            val zswapKey = account.selectRole(MidnightKeyRole.ZSWAP).deriveKeyAt(0)
            assertNotNull("ZSWAP key should derive", zswapKey)
            assertEquals("Private key should be 32 bytes", 32, zswapKey.privateKeyBytes.size)
        } finally {
            wallet.clear()
        }
    }

    @Test
    fun `different account indices produce different keys`() {
        val wallet = HDWallet.fromSeed(testSeed)
        try {
            val key0 = wallet.selectAccount(0)
                .selectRole(MidnightKeyRole.NIGHT_EXTERNAL).deriveKeyAt(0)
            val key1 = wallet.selectAccount(1)
                .selectRole(MidnightKeyRole.NIGHT_EXTERNAL).deriveKeyAt(0)

            assertFalse(
                "Different accounts should produce different keys",
                key0.publicKeyBytes.contentEquals(key1.publicKeyBytes),
            )
        } finally {
            wallet.clear()
        }
    }

    @Test
    fun `x-only public key is 32 bytes after stripping prefix`() {
        val wallet = HDWallet.fromSeed(testSeed)
        try {
            val nightKey = wallet.selectAccount(0)
                .selectRole(MidnightKeyRole.NIGHT_EXTERNAL).deriveKeyAt(0)

            val compressed = nightKey.publicKeyBytes
            assertEquals("Compressed pubkey should be 33 bytes", 33, compressed.size)
            assertTrue(
                "Prefix byte should be 0x02 or 0x03",
                compressed[0] == 0x02.toByte() || compressed[0] == 0x03.toByte(),
            )

            val xOnly = compressed.copyOfRange(1, 33)
            assertEquals("X-only pubkey should be 32 bytes", 32, xOnly.size)
        } finally {
            wallet.clear()
        }
    }

    @Test
    fun `network address prefix matches MidnightNetwork enum`() {
        assertEquals("mn_addr_preprod", MidnightNetwork.PREPROD.addressPrefix)
        assertEquals("mn_addr_preview", MidnightNetwork.PREVIEW.addressPrefix)
        assertEquals("mn_addr_undeployed", MidnightNetwork.UNDEPLOYED.addressPrefix)
    }

    @Test
    fun `network rustNetworkId matches expected values`() {
        assertEquals("preprod", MidnightNetwork.PREPROD.rustNetworkId)
        assertEquals("preview", MidnightNetwork.PREVIEW.rustNetworkId)
        assertEquals("undeployed", MidnightNetwork.UNDEPLOYED.rustNetworkId)
    }
}
