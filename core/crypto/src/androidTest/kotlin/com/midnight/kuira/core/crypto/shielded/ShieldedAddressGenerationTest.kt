// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.shielded

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android integration test for generating official Midnight shielded addresses.
 *
 * **Purpose:**
 * Demonstrates end-to-end flow:
 * 1. Generate mnemonic (BIP-39)
 * 2. Derive shielded seed (BIP-32 at m/44'/2400'/0'/3/0)
 * 3. Derive shielded keys (JNI → Rust FFI)
 * 4. Format as official Midnight addresses (Bech32m encoding)
 *
 * **Networks:**
 * - **Test:** Official Midnight testnet (`mn_shield-cpk_test1...`)
 * - **Dev:** Development network (`mn_shield-cpk_dev1...`)
 * - **Undeployed:** Undeployed network (`mn_shield-cpk_undeployed1...`)
 * - **Mainnet:** Production (no network suffix) (`mn_shield-cpk1...`)
 *
 * **Reference:**
 * - Midnight SDK: @midnight-ntwrk/wallet-sdk-address-format
 * - Test vectors: midnight-libraries/midnight-wallet/packages/address-format/test/addresses.json
 */
@RunWith(AndroidJUnit4::class)
class ShieldedAddressGenerationTest {

    @Before
    fun setUp() {
        // Skip tests if native library not loaded
        assumeTrue(
            "Native library not loaded - skipping shielded address generation tests",
            ShieldedKeyDeriver.isLibraryLoaded()
        )
    }

    @Test
    fun generateShieldedAddressForTestNetwork() {
        val networkId = "test"

        // Use standard test mnemonic for reproducibility
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val bip39Seed = BIP39.mnemonicToSeed(mnemonic)

        MemoryUtils.useAndWipe(bip39Seed) { seed ->
            val hdWallet = HDWallet.fromSeed(seed)
            try {
                // Derive shielded keys at m/44'/2400'/0'/3/0
                val shieldedKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)

                try {
                    // Derive shielded keys via JNI
                    val shieldedKeys = ShieldedKeyDeriver.deriveKeys(shieldedKey.privateKeyBytes)
                    assertNotNull("Shielded keys should be derived", shieldedKeys)

                    // Convert hex to bytes for Bech32m encoding
                    val coinPkBytes = hexToBytes(shieldedKeys!!.coinPublicKey)
                    val encPkBytes = hexToBytes(shieldedKeys.encryptionPublicKey)

                    // Full shielded address = coin PK + encryption PK (32 + 32 = 64 bytes)
                    val fullAddressBytes = coinPkBytes + encPkBytes

                    // Encode as Bech32m address with network
                    val hrp = "mn_shield-addr_$networkId"
                    val address = Bech32m.encode(hrp, fullAddressBytes)

                    // Expected from Lace-compatible Midnight TypeScript SDK (64-byte seed)
                    val expectedAddress = "mn_shield-addr_test1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2cyckupm"

                    assertEquals("Address should match TypeScript SDK", expectedAddress, address)

                    // Print for manual verification
                    println("=== TEST NETWORK SHIELDED ADDRESS ===")
                    println("Network: $networkId")
                    println("Coin Public Key: ${shieldedKeys.coinPublicKey}")
                    println("Encryption Public Key: ${shieldedKeys.encryptionPublicKey}")
                    println("Address: $address")
                    println("Expected: $expectedAddress")
                    println("Match: ${address == expectedAddress}")
                    println("=====================================")

                    // Verify round-trip decode
                    val (decodedHrp, decodedData) = Bech32m.decode(address)
                    assertEquals("HRP should match", hrp, decodedHrp)
                    assertArrayEquals("Decoded data should match", fullAddressBytes, decodedData)

                } finally {
                    shieldedKey.clear()
                }
            } finally {
                hdWallet.clear()
            }
        }
    }

    @Test
    fun generateShieldedAddressForDevNetwork() {
        val networkId = "dev"

        // Use standard test mnemonic for reproducibility
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
                    assertNotNull(shieldedKeys)

                    val coinPkBytes = hexToBytes(shieldedKeys!!.coinPublicKey)
                    val encPkBytes = hexToBytes(shieldedKeys.encryptionPublicKey)
                    val fullAddressBytes = coinPkBytes + encPkBytes

                    val hrp = "mn_shield-addr_$networkId"
                    val address = Bech32m.encode(hrp, fullAddressBytes)

                    // Expected from Lace-compatible Midnight TypeScript SDK (64-byte seed)
                    val expectedCoinPk = "9408aeffbeedc6b9b45e1bcc621d1a273fb67f77de3f65bfbb1814d84f8b6524"
                    val expectedEncPk = "f3ae706bf28c856a407690b468081a7f5a123e523501b69f4395abcd7e19032b"
                    val expectedAddress = "mn_shield-addr_dev1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2czvgsxd"

                    assertEquals("Coin PK should match TypeScript SDK", expectedCoinPk, shieldedKeys.coinPublicKey)
                    assertEquals("Enc PK should match TypeScript SDK", expectedEncPk, shieldedKeys.encryptionPublicKey)
                    assertEquals("Address should match TypeScript SDK", expectedAddress, address)

                    println("=== DEV NETWORK SHIELDED ADDRESS ===")
                    println("Network: $networkId")
                    println("Coin Public Key: ${shieldedKeys.coinPublicKey}")
                    println("Encryption Public Key: ${shieldedKeys.encryptionPublicKey}")
                    println("Address: $address")
                    println("Expected: $expectedAddress")
                    println("Match: ${address == expectedAddress}")
                    println("====================================")

                } finally {
                    shieldedKey.clear()
                }
            } finally {
                hdWallet.clear()
            }
        }
    }

    @Test
    fun generateShieldedAddressForUndeployedNetwork() {
        val networkId = "undeployed"

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
                    assertNotNull(shieldedKeys)

                    val coinPkBytes = hexToBytes(shieldedKeys!!.coinPublicKey)
                    val encPkBytes = hexToBytes(shieldedKeys.encryptionPublicKey)
                    val fullAddressBytes = coinPkBytes + encPkBytes

                    val hrp = "mn_shield-addr_$networkId"
                    val address = Bech32m.encode(hrp, fullAddressBytes)

                    // Expected from Lace-compatible Midnight TypeScript SDK (64-byte seed)
                    val expectedAddress = "mn_shield-addr_undeployed1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2czvqdfx"

                    assertEquals("Address should match TypeScript SDK", expectedAddress, address)

                    println("=== UNDEPLOYED NETWORK SHIELDED ADDRESS ===")
                    println("Network: $networkId")
                    println("Coin Public Key: ${shieldedKeys.coinPublicKey}")
                    println("Encryption Public Key: ${shieldedKeys.encryptionPublicKey}")
                    println("Address: $address")
                    println("Expected: $expectedAddress")
                    println("Match: ${address == expectedAddress}")
                    println("===========================================")

                } finally {
                    shieldedKey.clear()
                }
            } finally {
                hdWallet.clear()
            }
        }
    }

    @Test
    fun generateShieldedAddressForMainnet() {
        // Mainnet has NO network suffix - just "mn_shield-addr1..."

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
                    assertNotNull(shieldedKeys)

                    val coinPkBytes = hexToBytes(shieldedKeys!!.coinPublicKey)
                    val encPkBytes = hexToBytes(shieldedKeys.encryptionPublicKey)
                    val fullAddressBytes = coinPkBytes + encPkBytes

                    val hrp = "mn_shield-addr"  // NO network suffix for mainnet
                    val address = Bech32m.encode(hrp, fullAddressBytes)

                    // Expected from Lace-compatible Midnight TypeScript SDK (64-byte seed)
                    val expectedAddress = "mn_shield-addr1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2ckxvxuk"

                    assertEquals("Address should match TypeScript SDK", expectedAddress, address)

                    println("=== MAINNET SHIELDED ADDRESS ===")
                    println("Network: mainnet (no suffix)")
                    println("Coin Public Key: ${shieldedKeys.coinPublicKey}")
                    println("Encryption Public Key: ${shieldedKeys.encryptionPublicKey}")
                    println("Address: $address")
                    println("Expected: $expectedAddress")
                    println("Match: ${address == expectedAddress}")
                    println("================================")

                } finally {
                    shieldedKey.clear()
                }
            } finally {
                hdWallet.clear()
            }
        }
    }

    @Test
    fun verifyAllNetworksProduceDifferentAddresses() {
        // Same mnemonic and keys, but different network prefixes → different addresses

        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val bip39Seed = BIP39.mnemonicToSeed(mnemonic)

        val addresses = mutableListOf<String>()
        val networks = listOf("test", "dev", "undeployed", null) // null = mainnet

        MemoryUtils.useAndWipe(bip39Seed) { seed ->
            val hdWallet = HDWallet.fromSeed(seed)
            try {
                val shieldedKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)

                try {
                    val shieldedKeys = ShieldedKeyDeriver.deriveKeys(shieldedKey.privateKeyBytes)
                    assertNotNull(shieldedKeys)

                    val coinPkBytes = hexToBytes(shieldedKeys!!.coinPublicKey)
                    val encPkBytes = hexToBytes(shieldedKeys.encryptionPublicKey)
                    val fullAddressBytes = coinPkBytes + encPkBytes

                    networks.forEach { networkId ->
                        val hrp = if (networkId == null) {
                            "mn_shield-addr"  // Mainnet
                        } else {
                            "mn_shield-addr_$networkId"
                        }

                        val address = Bech32m.encode(hrp, fullAddressBytes)
                        addresses.add(address)

                        println("Network: ${networkId ?: "mainnet"} → $address")
                    }

                } finally {
                    shieldedKey.clear()
                }
            } finally {
                hdWallet.clear()
            }
        }

        // Verify all addresses are different (different checksums due to different HRPs)
        assertEquals("Should generate 4 addresses", 4, addresses.size)
        assertEquals("All addresses should be unique", addresses.size, addresses.toSet().size)
    }

    // Helper function to convert hex string to bytes
    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}
