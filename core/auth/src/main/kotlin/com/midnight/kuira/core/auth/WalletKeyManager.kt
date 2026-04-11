// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the AES-256 master key in Android Keystore.
 *
 * The master key encrypts the wallet's seed material at rest. It is:
 * - **Non-extractable**: key material never leaves the secure hardware
 * - **Biometric-gated**: per-use authentication required (duration=0)
 * - **StrongBox-preferred**: falls back to TEE if StrongBox unavailable
 *
 * The key supports both BIOMETRIC_STRONG and DEVICE_CREDENTIAL (PIN/pattern),
 * with CryptoObject binding for TEE-level per-operation enforcement (API 30+).
 */
class WalletKeyManager {

    companion object {
        const val MASTER_KEY_ALIAS = "kuira_master_key"

        private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val KEY_SIZE = 256

        // AES-GCM standard: 12-byte IV, 128-bit (16-byte) auth tag
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 128 // bits

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /**
     * Whether a master key already exists in the Keystore.
     */
    fun hasKey(): Boolean = keyStore.containsAlias(MASTER_KEY_ALIAS)

    /**
     * Generates a new master key in the Keystore.
     *
     * Tries StrongBox first. If StrongBox is unavailable for this algorithm/key size,
     * falls back to TEE. The key is configured with per-use authentication —
     * every encrypt/decrypt requires a fresh biometric or device credential.
     *
     * @return true if StrongBox was used, false if TEE fallback
     * @throws IllegalStateException if a key already exists (call [deleteKey] first)
     */
    fun generateKey(): Boolean {
        check(!hasKey()) { "Master key already exists. Delete it first." }

        val keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM, "AndroidKeyStore")

        return try {
            keyGenerator.init(buildKeySpec(strongBox = true))
            keyGenerator.generateKey()
            true // StrongBox
        } catch (e: StrongBoxUnavailableException) {
            keyGenerator.init(buildKeySpec(strongBox = false))
            keyGenerator.generateKey()
            false // TEE fallback
        }
    }

    /**
     * Creates a Cipher initialized for encryption with the master key.
     *
     * The returned cipher has a Keystore-generated random IV accessible via [Cipher.getIV].
     * Wrap this in a `BiometricPrompt.CryptoObject` before authenticating.
     *
     * After successful biometric auth, call [Cipher.doFinal] with the plaintext.
     * Store the result as: `[IV (12 bytes)] + [ciphertext + GCM tag (16 bytes)]`
     */
    fun cipherForEncrypt(): Cipher {
        val key = getKey()
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    /**
     * Creates a Cipher initialized for decryption with the master key.
     *
     * @param iv The 12-byte IV that was stored alongside the ciphertext during encryption
     * @throws java.security.InvalidKeyException if the key has been permanently invalidated
     */
    fun cipherForDecrypt(iv: ByteArray): Cipher {
        require(iv.size == GCM_IV_LENGTH) { "IV must be $GCM_IV_LENGTH bytes, got ${iv.size}" }
        val key = getKey()
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, gcmSpec)
        }
    }

    /**
     * Deletes the master key from the Keystore.
     *
     * After deletion, all data encrypted with this key becomes permanently inaccessible.
     * Only call this as part of a recovery flow where the seed is available from backup.
     */
    fun deleteKey() {
        if (hasKey()) {
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        }
    }

    private fun getKey(): SecretKey {
        val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("Master key not found. Create a wallet first.")
        return entry.secretKey
    }

    private fun buildKeySpec(strongBox: Boolean): KeyGenParameterSpec {
        return KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(BLOCK_MODE)
            setEncryptionPaddings(PADDING)
            setKeySize(KEY_SIZE)
            // Per-use auth: every operation requires fresh biometric or PIN
            setUserAuthenticationParameters(
                0, // duration=0 → per-use
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
            // Don't invalidate key when user adds new fingerprint
            setInvalidatedByBiometricEnrollment(false)
            // Defense-in-depth: block any use of the key while the device is
            // locked. Per-use BiometricPrompt already gates every call and
            // the app UI can't render while the screen is locked, so the
            // extra flag mostly guards programmatic access paths — but it
            // is the standard Android hardening flag for key material and
            // costs nothing. Available since API 28; minSdk is 30.
            setUnlockedDeviceRequired(true)
            if (strongBox) {
                setIsStrongBoxBacked(true)
            }
        }.build()
    }
}
