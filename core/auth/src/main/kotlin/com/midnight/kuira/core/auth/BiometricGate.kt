// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Result of a successful biometric authentication with CryptoObject.
 *
 * @property cipher The authenticated cipher — ready for [Cipher.doFinal].
 *   This cipher is bound to the specific operation that was authenticated
 *   via the HardwareAuthToken challenge mechanism.
 */
data class AuthenticatedCipher(val cipher: Cipher)

/**
 * Wraps [BiometricPrompt] + [BiometricPrompt.CryptoObject] as a suspend function.
 *
 * Provides per-use authentication gating for cryptographic operations.
 * Each call shows the system biometric prompt (fingerprint, face, or PIN fallback)
 * and returns an [AuthenticatedCipher] that can perform exactly one crypto operation.
 *
 * **Security model (verified against AOSP):**
 * - CryptoObject binds authentication to a specific `beginOperation()` in KeyMint
 * - The HardwareAuthToken's `challenge` field matches the operation handle
 * - This provides TEE-level per-operation enforcement — not just time-based
 * - Works with `AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL` on API 30+
 *   (AndroidX biometric 1.1.0 stable)
 */
class BiometricGate(private val keyManager: WalletKeyManager) {

    /**
     * Authenticates the user and returns a cipher ready for encryption.
     *
     * The cipher has a Keystore-generated random IV accessible via [Cipher.getIV].
     * After calling [Cipher.doFinal], store the result as:
     * `[IV (12 bytes)] + [ciphertext + GCM auth tag (16 bytes)]`
     *
     * @param activity The FragmentActivity hosting the biometric prompt
     * @param title Prompt title shown to the user
     * @param subtitle Optional subtitle (e.g., "Encrypt wallet seed")
     * @throws AuthenticationCancelledException if the user cancelled
     * @throws AuthenticationFailedException if authentication failed
     * @throws KeyPermanentlyInvalidatedException if the key was invalidated
     */
    suspend fun authenticateForEncrypt(
        activity: FragmentActivity,
        title: String = "Authenticate",
        subtitle: String? = null
    ): AuthenticatedCipher {
        val cipher = keyManager.cipherForEncrypt()
        return authenticate(activity, cipher, title, subtitle)
    }

    /**
     * Authenticates the user and returns a cipher ready for decryption.
     *
     * @param activity The FragmentActivity hosting the biometric prompt
     * @param iv The 12-byte IV stored alongside the ciphertext during encryption
     * @param title Prompt title shown to the user
     * @param subtitle Optional subtitle (e.g., "Unlock wallet")
     * @throws AuthenticationCancelledException if the user cancelled
     * @throws AuthenticationFailedException if authentication failed
     * @throws KeyPermanentlyInvalidatedException if the key was invalidated
     */
    suspend fun authenticateForDecrypt(
        activity: FragmentActivity,
        iv: ByteArray,
        title: String = "Authenticate",
        subtitle: String? = null
    ): AuthenticatedCipher {
        val cipher = keyManager.cipherForDecrypt(iv)
        return authenticate(activity, cipher, title, subtitle)
    }

    private suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String?
    ): AuthenticatedCipher = suspendCancellableCoroutine { cont ->

        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authenticatedCipher = result.cryptoObject?.cipher
                if (authenticatedCipher != null && cont.isActive) {
                    cont.resume(AuthenticatedCipher(authenticatedCipher))
                } else if (cont.isActive) {
                    cont.resumeWithException(
                        AuthenticationFailedException("CryptoObject cipher was null after authentication")
                    )
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!cont.isActive) return
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED ->
                        cont.resumeWithException(AuthenticationCancelledException(errString.toString()))
                    else ->
                        cont.resumeWithException(AuthenticationFailedException("[$errorCode] $errString"))
                }
            }

            override fun onAuthenticationFailed() {
                // Called on each failed attempt (wrong fingerprint, etc.)
                // Don't cancel — the system will show retry or fall back to PIN.
                // After too many failures, onAuthenticationError will be called.
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            // Allow biometric + device credential (PIN/pattern/password)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        val cryptoObject = BiometricPrompt.CryptoObject(cipher)

        biometricPrompt.authenticate(promptInfo, cryptoObject)

        cont.invokeOnCancellation {
            biometricPrompt.cancelAuthentication()
        }
    }
}

/**
 * Thrown when the user explicitly cancels biometric authentication.
 */
class AuthenticationCancelledException(message: String) : Exception(message)

/**
 * Thrown when biometric authentication fails (hardware error, lockout, etc.).
 */
class AuthenticationFailedException(message: String) : Exception(message)
