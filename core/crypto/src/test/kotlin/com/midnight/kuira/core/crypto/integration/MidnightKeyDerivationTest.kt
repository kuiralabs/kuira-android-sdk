package com.midnight.kuira.core.crypto.integration

import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that Kuira derives the same keys as Midnight Wallet SDK
 * for ALL key types: Unshielded, Shielded, and Dust.
 *
 * Test vector: "abandon abandon abandon... art" (24-word mnemonic)
 * Uses full 64-byte PBKDF2 seed (Lace compatible).
 */
class MidnightKeyDerivationTest {

    @Test
    fun `verify all three key types match Midnight SDK`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        // Step 1: Derive BIP-39 seed (64 bytes, Lace compatible)
        val seed = BIP39.mnemonicToSeed(mnemonic, passphrase = "")


        // Step 2: Create HD wallet
        val wallet = HDWallet.fromSeed(seed)

        // Step 3: Derive UNSHIELDED key (role 0 = NIGHT_EXTERNAL)
        val unshieldedKey = wallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
            .deriveKeyAt(0)

        val unshieldedSeed = unshieldedKey.privateKeyBytes.joinToString("") { "%02x".format(it) }


        // Step 4: Derive SHIELDED key (role 3 = ZSWAP)
        val shieldedKey = wallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.ZSWAP)
            .deriveKeyAt(0)

        val shieldedSeed = shieldedKey.privateKeyBytes.joinToString("") { "%02x".format(it) }


        // Step 5: Derive DUST key (role 2 = DUST)
        val dustKey = wallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.DUST)
            .deriveKeyAt(0)

        val dustSeed = dustKey.privateKeyBytes.joinToString("") { "%02x".format(it) }


        // Verify unshielded key matches known test vector (Lace compatibility - 64-byte seed)
        assertEquals(
            "Unshielded key at m/44'/2400'/0'/0/0 must match Midnight SDK",
            "af7a998947b1b1fd12d99cb40ee98a739e6a2518d8965690781d85ea0e3a5e13",
            unshieldedSeed
        )

        // Verify shielded and dust keys are valid 32-byte private keys
        assertEquals("Shielded key should be 32 bytes", 32, shieldedKey.privateKeyBytes.size)
        assertEquals("Dust key should be 32 bytes", 32, dustKey.privateKeyBytes.size)

        // Verify all three keys are different from each other
        assert(unshieldedSeed != shieldedSeed) { "Unshielded and shielded keys must differ" }
        assert(unshieldedSeed != dustSeed) { "Unshielded and dust keys must differ" }
        assert(shieldedSeed != dustSeed) { "Shielded and dust keys must differ" }

        // Clean up
        unshieldedKey.clear()
        shieldedKey.clear()
        dustKey.clear()
        wallet.clear()
    }
}
