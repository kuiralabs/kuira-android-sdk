package com.midnight.kuira.sdk.walletseed

import androidx.fragment.app.FragmentActivity

/**
 * The minimal seed-layer capability the recovery feature (#252) needs.
 *
 * Factored out so [RecoveryPhraseManager] depends on a small, explicit contract (dependency
 * inversion) instead of the whole [WalletSeedSource]. That keeps the manager unit-testable with a
 * trivial fake and documents exactly what recovery touches in the seed layer. Internal on purpose
 * — it's an implementation seam, not public SDK surface; dApps consume [WalletRecovery] instead.
 */
internal interface RecoverySeedStore {

    /** True if a wallet seed already exists in the vault. */
    suspend fun hasSeed(): Boolean

    /**
     * Load the wallet's 32-byte BIP-39 entropy (biometric-gated). The recovery phrase is exactly
     * this entropy encoded as BIP-39 words. Returns a fresh copy the caller MUST wipe.
     */
    suspend fun loadEntropy(activity: FragmentActivity): ByteArray

    /**
     * Seed the vault from a pre-derived 32-byte entropy — the restore path. Returns the derived
     * 64-byte BIP-39 seed (caller wipes it).
     */
    suspend fun acceptPreDerivedSeed(activity: FragmentActivity, prfEntropy: ByteArray): ByteArray
}
