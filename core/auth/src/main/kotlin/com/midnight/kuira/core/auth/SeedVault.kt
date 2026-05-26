// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     *
     * Idempotent — safe to call multiple times.
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
 * Thrown when the encrypted seed file on disk is corrupted or malformed
 * (wrong size, invalid IV length, decryption fails). The caller should
 * trigger a recovery flow from backup.
 */
class CorruptedSeedException(message: String) : Exception(message)

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
 * explicitly excluded from Auto Backup via `data_extraction_rules.xml`
 * in the host app's manifest.
 *
 * **Atomicity:** Writes go to a temp file and are renamed in place, so a
 * crash mid-write leaves either the old seed or the new seed, never a
 * partial file.
 *
 * **Threading:** All file I/O runs on [Dispatchers.IO] via `withContext`.
 */
class SeedVault(
    private val context: Context,
    private val biometricGate: BiometricGate
) {

    private val seedFile: File
        get() = File(context.filesDir, SEED_FILE_NAME)

    private val tempFile: File
        get() = File(context.filesDir, TEMP_FILE_NAME)

    /**
     * Whether a **valid** encrypted seed has been persisted.
     *
     * Checks size, not just existence: a 0-byte or truncated `kuira_seed.bin`
     * (seen in the field after interrupted writes / recovery churn) must read
     * as "no seed", otherwise it traps the caller forever — `loadSeed` would
     * throw [CorruptedSeedException] and `WalletSeedSource.acceptPreDerivedSeed`
     * would take its "already populated" branch and never re-derive from the
     * PRF entropy it holds. Treating a malformed file as absent lets the
     * bootstrap self-heal (it re-stores, and the atomic write overwrites the
     * junk file).
     *
     * Performs file I/O — must be called from a coroutine.
     */
    suspend fun hasSeed(): Boolean = withContext(Dispatchers.IO) {
        seedFile.exists() && seedFile.length() == EXPECTED_SEED_FILE_SIZE.toLong()
    }

    /**
     * Encrypts and stores the wallet seed material.
     *
     * Shows a biometric prompt to unlock the Keystore master key, encrypts
     * the concatenated entropy+seed with AES-256-GCM, and writes the result
     * atomically to app-private storage.
     *
     * **The [seedProducer] lambda is invoked ONLY AFTER biometric auth succeeds**,
     * which minimizes the time the plaintext seed is resident in memory. If the
     * user cancels the prompt, [seedProducer] is never called.
     *
     * The returned [PlaintextSeed] is wiped automatically after encryption —
     * callers should NOT hold their own reference to it.
     *
     * **Exception propagation:** [android.security.keystore.KeyPermanentlyInvalidatedException]
     * may be thrown synchronously from the Keystore if the key was invalidated.
     *
     * @param activity The FragmentActivity hosting the biometric prompt
     * @param seedProducer Lambda that produces the plaintext seed. Called once,
     *   immediately after successful biometric authentication.
     * @throws IllegalStateException if a seed already exists (call [deleteSeed] first)
     * @throws AuthenticationCancelledException if the user cancelled the biometric prompt
     * @throws AuthenticationFailedException if biometric authentication failed
     */
    suspend fun storeSeed(
        activity: FragmentActivity,
        seedProducer: () -> PlaintextSeed
    ) {
        check(!hasSeed()) { "Seed already exists. Delete it first." }

        // Fast path — if the user authenticated within
        // AuthPolicy.VALIDITY_DURATION_SECONDS (e.g. the PRF passkey
        // biometric that just decrypted a cloud-restored seed),
        // Keystore lets us encrypt without showing a BiometricPrompt.
        // The cipher is obtained BEFORE the seed plaintext is produced,
        // so we preserve the existing "minimize exposure" property:
        // plaintext only lives in memory between seedProducer() and
        // doFinal, regardless of which path got us the cipher.
        val cipher = biometricGate.tryEncryptWithinAuthWindow()?.also {
            Log.i(TAG, "storeSeed: silent (auth-validity window OPEN)")
        } ?: run {
            Log.i(TAG, "storeSeed: prompting (auth-validity window EXPIRED or unavailable)")
            biometricGate.authenticateForEncrypt(
                activity = activity,
                title = "Secure your wallet",
                subtitle = "Authenticate to encrypt your wallet keys"
            ).cipher
        }

        // Produce the plaintext ONLY AFTER the cipher is ready, minimizing exposure window.
        val seed = seedProducer()

        val plaintext = ByteArray(PlaintextSeed.PLAINTEXT_SIZE)
        try {
            System.arraycopy(seed.mnemonicEntropy, 0, plaintext, 0, PlaintextSeed.ENTROPY_SIZE)
            System.arraycopy(seed.bip39Seed, 0, plaintext, PlaintextSeed.ENTROPY_SIZE, PlaintextSeed.SEED_SIZE)

            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            check(iv.size == WalletKeyManager.GCM_IV_LENGTH) {
                "Unexpected IV length: ${iv.size}"
            }

            // Atomic write: temp file + rename. Prevents partial writes on crash.
            withContext(Dispatchers.IO) {
                tempFile.writeBytes(iv + ciphertext)
                if (!tempFile.renameTo(seedFile)) {
                    // Rename failed — clean up temp file and fail loudly
                    tempFile.delete()
                    throw IllegalStateException("Failed to persist seed: rename failed")
                }
            }
        } finally {
            plaintext.fill(0)
            // Wipe the caller's plaintext too — they should not be holding a reference
            seed.wipe()
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
     * @throws CorruptedSeedException if the stored file is malformed
     * @throws AuthenticationCancelledException if the user cancelled
     * @throws AuthenticationFailedException if biometric authentication failed
     */
    suspend fun loadSeed(activity: FragmentActivity): PlaintextSeed {
        // Gate on raw existence, not hasSeed(): hasSeed() now also rejects a
        // wrong-size file, but here we want to keep the absent-vs-corrupt
        // distinction — no file → "create a wallet" (IllegalStateException);
        // file present but malformed → CorruptedSeedException below (the
        // caller's signal to trigger a restore). The two need different
        // recovery UX, so don't collapse them.
        val stored = withContext(Dispatchers.IO) {
            check(seedFile.exists()) { "No seed stored. Create a wallet first." }
            seedFile.readBytes()
        }

        // IV ‖ ciphertext ‖ GCM tag. A wrong size means a truncated/garbage
        // file (e.g. an interrupted write) — surface it as corruption.
        if (stored.size != EXPECTED_SEED_FILE_SIZE) {
            throw CorruptedSeedException(
                "Stored seed has wrong size: expected $EXPECTED_SEED_FILE_SIZE bytes, got ${stored.size}"
            )
        }

        // Extract IV and ciphertext
        val iv = stored.copyOfRange(0, WalletKeyManager.GCM_IV_LENGTH)
        val ciphertext = stored.copyOfRange(WalletKeyManager.GCM_IV_LENGTH, stored.size)

        // Fast path — if the user authenticated within
        // AuthPolicy.VALIDITY_DURATION_SECONDS, Keystore lets us decrypt
        // without showing a BiometricPrompt. UserNotAuthenticatedException
        // is enforced in secure hardware, so this can't bypass auth: if
        // the window's expired the call returns null and we fall through
        // to the full prompt path below.
        val silentPlaintext = biometricGate.tryDecryptWithinAuthWindow(iv, ciphertext)
        val plaintext = if (silentPlaintext != null) {
            Log.i(TAG, "loadSeed: silent (auth-validity window OPEN)")
            silentPlaintext
        } else {
            Log.i(TAG, "loadSeed: prompting (auth-validity window EXPIRED or unavailable)")
            val authenticated = biometricGate.authenticateForDecrypt(
                activity = activity,
                iv = iv,
                title = "Unlock wallet",
                subtitle = "Authenticate to access your wallet keys"
            )
            try {
                authenticated.cipher.doFinal(ciphertext)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw CorruptedSeedException("Seed decryption failed: invalid auth tag")
            }
        }

        try {
            if (plaintext.size != PlaintextSeed.PLAINTEXT_SIZE) {
                throw CorruptedSeedException(
                    "Decrypted seed has unexpected size: ${plaintext.size}"
                )
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
     *
     * Idempotent — safe to call when no seed exists.
     */
    fun deleteSeed() {
        if (seedFile.exists()) {
            seedFile.delete()
        }
        // Also clean up any stale temp file from a crashed write
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    companion object {
        internal const val SEED_FILE_NAME = "kuira_seed.bin"
        internal const val TEMP_FILE_NAME = "kuira_seed.bin.tmp"

        // AES-GCM auth tag size in bytes (derived from WalletKeyManager.GCM_TAG_LENGTH bits)
        private const val GCM_TAG_BYTES = WalletKeyManager.GCM_TAG_LENGTH / 8

        /**
         * Exact on-disk size of a valid encrypted seed: IV ‖ ciphertext ‖ GCM
         * tag. The ciphertext is the plaintext length (GCM is not size-
         * expanding beyond the tag). Used by both [hasSeed] (to reject a
         * truncated/empty file) and [loadSeed]'s integrity check.
         */
        internal const val EXPECTED_SEED_FILE_SIZE =
            WalletKeyManager.GCM_IV_LENGTH + PlaintextSeed.PLAINTEXT_SIZE + GCM_TAG_BYTES

        private const val TAG = "SeedVault"
    }
}
