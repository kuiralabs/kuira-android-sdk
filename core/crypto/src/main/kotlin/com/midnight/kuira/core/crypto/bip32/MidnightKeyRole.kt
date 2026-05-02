// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.bip32

/**
 * Midnight blockchain key roles as defined in the Midnight Wallet SDK.
 *
 * Midnight uses BIP-32 hierarchical deterministic (HD) wallet structure
 * with custom roles for different key purposes.
 *
 * **Derivation Path:**
 * ```
 * m / 44' / 2400' / account' / role / index
 * ```
 *
 * Where:
 * - `44'` = BIP-44 purpose (hardened)
 * - `2400'` = Midnight coin type (hardened)
 * - `account'` = Account index (hardened, typically 0)
 * - `role` = One of the roles defined below (NOT hardened)
 * - `index` = Address index (NOT hardened, typically 0)
 *
 * **Reference:**
 * - Midnight Wallet SDK: https://github.com/midnightntwrk/midnight-wallet
 * - BIP-44: https://github.com/bitcoin/bips/blob/master/bip-0044.mediawiki
 *
 * @property index The numeric role index used in the derivation path
 */
enum class MidnightKeyRole(val index: Int) {
    /**
     * **NightExternal** - Used for unshielded receiving addresses.
     *
     * This is the primary role for public, transparent transactions.
     * Corresponds to external addresses that can be shared publicly.
     *
     * Path: `m/44'/2400'/account'/0/index`
     */
    NIGHT_EXTERNAL(0),

    /**
     * **NightInternal** - Used for unshielded change addresses.
     *
     * Internal addresses used for receiving change from transactions.
     * Should not be shared publicly.
     *
     * Path: `m/44'/2400'/account'/1/index`
     */
    NIGHT_INTERNAL(1),

    /**
     * **Dust** - Used for Dust protocol addresses.
     *
     * Dust is Midnight's native token. This role is for Dust-specific operations.
     *
     * Path: `m/44'/2400'/account'/2/index`
     */
    DUST(2),

    /**
     * **Zswap** - Used for shielded (private) addresses.
     *
     * Zswap is Midnight's zero-knowledge proof system for private transactions.
     * This role derives keys used for shielded addresses.
     *
     * Path: `m/44'/2400'/account'/3/index`
     *
     * **Note:** According to Midnight documentation, role 3 (Zswap) is the
     * primary role for wallet implementations. Other roles are reserved for
     * future implementations.
     */
    ZSWAP(3),

    /**
     * **Metadata** - Reserved for metadata-related operations.
     *
     * Path: `m/44'/2400'/account'/4/index`
     */
    METADATA(4),

    /**
     * **Identity** - Used for sigil access keys (Kuira-specific).
     *
     * secp256k1 access keys that are authorized by the P-256 root passkey
     * via keyAuthorization. These keys sign Midnight transactions on behalf
     * of the sigil identity.
     *
     * Path: `m/44'/2400'/account'/5/index`
     */
    IDENTITY(5);

    companion object {
        /**
         * Gets a [MidnightKeyRole] from its numeric index.
         *
         * @param index The role index (0-4)
         * @return The corresponding [MidnightKeyRole]
         * @throws IllegalArgumentException if index is not valid
         */
        fun fromIndex(index: Int): MidnightKeyRole {
            return entries.firstOrNull { it.index == index }
                ?: throw IllegalArgumentException("Invalid Midnight key role index: $index")
        }
    }
}
