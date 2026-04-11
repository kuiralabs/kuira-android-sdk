// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.feature.onboarding

import com.midnight.kuira.core.auth.WalletAddresses
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.shielded.ShieldedKeyDeriver
import com.midnight.kuira.core.network.MidnightNetwork
import java.security.MessageDigest

/**
 * Derives the wallet's public addresses from a freshly-loaded BIP-39 seed.
 *
 * This runs ONLY during onboarding — after biometric auth, while the plaintext
 * seed is briefly in memory. The result is a pair of public addresses
 * ([WalletAddresses]) that can be cached and read by read-only features
 * (balance display) without requiring another biometric prompt.
 *
 * **Thread safety:** This is a pure function — no state. Safe to call from
 * any coroutine context.
 */
internal object WalletAddressDeriver {

    /**
     * Derive unshielded + shielded addresses for account 0, index 0.
     *
     * @param seed 64-byte BIP-39 seed (from [com.midnight.kuira.core.auth.PlaintextSeed.bip39Seed])
     * @param network The current Midnight network (determines address prefix)
     * @return Both addresses. [WalletAddresses.shieldedAddress] is null only if
     *   the native `ShieldedKeyDeriver` failed — logged but not fatal.
     */
    fun derive(seed: ByteArray, network: MidnightNetwork): WalletAddresses {
        val hdWallet = HDWallet.fromSeed(seed)
        try {
            val unshielded = deriveUnshielded(hdWallet, network)
            val shielded = deriveShielded(hdWallet, network)
            return WalletAddresses(unshieldedAddress = unshielded, shieldedAddress = shielded)
        } finally {
            hdWallet.clear()
        }
    }

    private fun deriveUnshielded(hdWallet: HDWallet, network: MidnightNetwork): String {
        val key = hdWallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
            .deriveKeyAt(0)
        try {
            // Midnight address: SHA-256 of x-only public key (BIP-340)
            val compressedPublicKey = key.publicKeyBytes
            require(compressedPublicKey.size == 33) { "Expected 33-byte compressed key" }
            val xOnlyPublicKey = compressedPublicKey.copyOfRange(1, 33)
            val addressData = MessageDigest.getInstance("SHA-256").digest(xOnlyPublicKey)
            return Bech32m.encode(network.addressPrefix, addressData)
        } finally {
            key.clear()
        }
    }

    private fun deriveShielded(hdWallet: HDWallet, network: MidnightNetwork): String? {
        val zswapKey = hdWallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.ZSWAP)
            .deriveKeyAt(0)
        val zswapSeed = zswapKey.privateKeyBytes.copyOf()
        zswapKey.clear()

        try {
            val shieldedKeys = ShieldedKeyDeriver.deriveKeys(zswapSeed) ?: return null
            val coinPkBytes = hexToBytes(shieldedKeys.coinPublicKey)
            val encPkBytes = hexToBytes(shieldedKeys.encryptionPublicKey)
            val prefix = "mn_shield-addr" + when (network) {
                MidnightNetwork.PREPROD -> "_preprod"
                MidnightNetwork.PREVIEW -> "_preview"
                MidnightNetwork.UNDEPLOYED -> "_undeployed"
            }
            return Bech32m.encode(prefix, coinPkBytes + encPkBytes)
        } finally {
            zswapSeed.fill(0)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
