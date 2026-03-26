// Investigation test to understand shielded address structure
package com.midnight.kuira.core.crypto.shielded

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvestigateShieldedAddressStructure {

    @Before
    fun setUp() {
        assumeTrue(
            "Native library not loaded",
            ShieldedKeyDeriver.isLibraryLoaded()
        )
    }

    @Test
    fun investigateShieldedKeyLengths() {
        // Use standard test mnemonic
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val bip39Seed = BIP39.mnemonicToSeed(mnemonic)

        MemoryUtils.useAndWipe(bip39Seed) { seed ->
            val hdWallet = HDWallet.fromSeed(seed)
            try {
                val shieldedKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)

                try {
                    val shieldedKeys = ShieldedKeyDeriver.deriveKeys(shieldedKey.privateKeyBytes)
                    require(shieldedKeys != null)

                    val combined = shieldedKeys.coinPublicKey + shieldedKeys.encryptionPublicKey
                    println("Shielded key lengths: coin=${shieldedKeys.coinPublicKey.length / 2}B, enc=${shieldedKeys.encryptionPublicKey.length / 2}B, total=${combined.length / 2}B")

                    if (combined.length / 2 != 64) {
                        println("WARNING: Length mismatch! Expected 64 bytes, got ${combined.length / 2}")
                    }

                } finally {
                    shieldedKey.clear()
                }
            } finally {
                hdWallet.clear()
            }
        }
    }
}
