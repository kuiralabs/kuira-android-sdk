// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import android.content.Context
import androidx.fragment.app.FragmentActivity
import java.io.File

/**
 * Wallet seed material decrypted into memory.
 *
 * **Security:** The caller MUST wipe both arrays as soon as possible
 * (see [PlaintextSeed.wipe]). The arrays hold the wallet's signing secret —
 * keeping them in memory longer than necessary is a security risk.
 *
 * @property mnemonicEntropy 32-byte BIP-39 entropy (for a 24-word mnemonic).
 *   Used to reconstruct the human-readable phrase when the user requests it.
 * @property bip39Seed 64-byte BIP-39 seed derived from the mnemonic via PBKDF2.
 *   Used directly for BIP-32 key derivation — skips PBKDF2 on every signing.
 */
class PlaintextSeed(
    val mnemonicEntropy: ByteArray,
    val bip39Seed: ByteArray
) {
    init {
        require(mnemonicEntropy.size == ENTROPY_SIZE) {
            "Mnemonic entropy must be $ENTROPY_SIZE bytes, got ${mnemonicEntropy.size}"
        }
        require(bip39Seed.size == SEED_SIZE) {
            "BIP-39 seed must be $SEED_SIZE bytes, got ${bip39Seed.size}"
        }
    }

    /**
     * Overwrites both byte arrays with zeros. Call this immediately after
     * deriving keys and signing — JVM may not guarantee the wipe is effective
     * (see security notes in plan), but it reduces the window of exposure.
     */
    fun wipe() {
        mnemonicEntropy.fill(0)
        bip39Seed.fill(0)
    }

    companion object {
        const val ENTROPY_SIZE = 32
        const val SEED_SIZE = 64
        const val PLAINTEXT_SIZE = ENTROPY_SIZE + SEED_SIZE
    }
}

/**
 * Encrypted seed storage backed by Android Keystore + biometric authentication.
 *
 * **Layered security model:**
 * - **Local layer (this class):** Seed encrypted with device-bound Keystore
 *   master key. Biometric-gated for every access.
 * - **Backup layer (future — `core:backup`):** Separate encrypted blob using a
 *   transferable key (passkey PRF or backup password). Needed for cross-device
 *   recovery because the Keystore master key cannot transfer to a new device.
 *
 * **Storage format:**
 * The on-disk file is a single blob:
 * ```
 * [12 bytes: IV] + [96 bytes: encrypted(entropy || seed)] + [16 bytes: GCM auth tag]
 * ```
 * Total: 124 bytes.
 *
 * **File location:** `<app filesDir>/kuira_seed.bin` — app-private storage,
 * not world-readable, excluded from ADB backup by default.
 */
class SeedVault(
    private val context: Context,
    private val biometricGate: BiometricGate
) {

    private val seedFile: File
        get() = File(context.filesDir, SEED_FILE_NAME)

    /**
     * Whether an encrypted seed has been persisted.
     */
    fun hasSeed(): Boolean = seedFile.exists()

    /**
     * Encrypts and stores the wallet seed material.
     *
     * Shows a biometric prompt to unlock the Keystore master key, encrypts
     * the concatenated entropy+seed with AES-256-GCM, and writes the result
     * to app-private storage. The plaintext is NOT wiped by this method —
     * the caller is responsible for calling [PlaintextSeed.wipe] after.
     *
     * @param activity The FragmentActivity hosting the biometric prompt
     * @param seed The plaintext seed to encrypt and store
     * @throws IllegalStateException if a seed already exists (call [deleteSeed] first)
     */
    suspend fun storeSeed(activity: FragmentActivity, seed: PlaintextSeed) {
        check(!hasSeed()) { "Seed already exists. Delete it first." }

        val authenticated = biometricGate.authenticateForEncrypt(
            activity = activity,
            title = "Secure your wallet",
            subtitle = "Authenticate to encrypt your wallet keys"
        )

        val plaintext = ByteArray(PlaintextSeed.PLAINTEXT_SIZE)
        try {
            System.arraycopy(seed.mnemonicEntropy, 0, plaintext, 0, PlaintextSeed.ENTROPY_SIZE)
            System.arraycopy(seed.bip39Seed, 0, plaintext, PlaintextSeed.ENTROPY_SIZE, PlaintextSeed.SEED_SIZE)

            val ciphertext = authenticated.cipher.doFinal(plaintext)
            val iv = authenticated.cipher.iv
            check(iv.size == WalletKeyManager.GCM_IV_LENGTH) {
                "Unexpected IV length: ${iv.size}"
            }

            // Write as [IV | ciphertext]
            seedFile.writeBytes(iv + ciphertext)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Decrypts and returns the wallet seed material.
     *
     * Shows a biometric prompt to unlock the Keystore master key, reads the
     * encrypted seed from storage, and decrypts it. The caller MUST call
     * [PlaintextSeed.wipe] as soon as signing is complete.
     *
     * @param activity The FragmentActivity hosting the biometric prompt
     * @throws IllegalStateException if no seed exists
     * @throws IllegalStateException if the stored seed is corrupted
     */
    suspend fun loadSeed(activity: FragmentActivity): PlaintextSeed {
        check(hasSeed()) { "No seed stored. Create a wallet first." }

        val stored = seedFile.readBytes()
        check(stored.size >= WalletKeyManager.GCM_IV_LENGTH) {
            "Stored seed is corrupted (size=${stored.size})"
        }

        // Extract IV and ciphertext
        val iv = stored.copyOfRange(0, WalletKeyManager.GCM_IV_LENGTH)
        val ciphertext = stored.copyOfRange(WalletKeyManager.GCM_IV_LENGTH, stored.size)

        val authenticated = biometricGate.authenticateForDecrypt(
            activity = activity,
            iv = iv,
            title = "Unlock wallet",
            subtitle = "Authenticate to access your wallet keys"
        )

        val plaintext = authenticated.cipher.doFinal(ciphertext)
        try {
            check(plaintext.size == PlaintextSeed.PLAINTEXT_SIZE) {
                "Decrypted seed has unexpected size: ${plaintext.size}"
            }

            val entropy = plaintext.copyOfRange(0, PlaintextSeed.ENTROPY_SIZE)
            val seed = plaintext.copyOfRange(PlaintextSeed.ENTROPY_SIZE, PlaintextSeed.PLAINTEXT_SIZE)
            return PlaintextSeed(entropy, seed)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Deletes the encrypted seed from storage.
     *
     * **WARNING:** After deletion, the seed is permanently gone unless recovered
     * from backup. Only call this from a recovery flow or user-initiated wallet reset.
     */
    fun deleteSeed() {
        if (seedFile.exists()) {
            seedFile.delete()
        }
    }

    companion object {
        private const val SEED_FILE_NAME = "kuira_seed.bin"
    }
}
