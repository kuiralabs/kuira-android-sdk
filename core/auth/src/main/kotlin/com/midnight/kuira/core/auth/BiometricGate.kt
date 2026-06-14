// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import android.util.Log
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
     * **Exception propagation:**
     * [android.security.keystore.KeyPermanentlyInvalidatedException] is thrown
     * synchronously from [WalletKeyManager.cipherForEncrypt] BEFORE the biometric
     * prompt is shown. The caller should catch it at the call site and trigger
     * the key-recreation recovery flow.
     *
     * @param activity The FragmentActivity hosting the biometric prompt
     * @param title Prompt title shown to the user
     * @param subtitle Optional subtitle (e.g., "Encrypt wallet seed")
     * @throws AuthenticationCancelledException if the user cancelled
     * @throws AuthenticationLockedOutException if the device is temporarily locked out
     * @throws AuthenticationPermanentlyLockedOutException if permanently locked out
     * @throws AuthenticationFailedException for all other failures
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
     * **Exception propagation:**
     * [android.security.keystore.KeyPermanentlyInvalidatedException] is thrown
     * synchronously from [WalletKeyManager.cipherForDecrypt] BEFORE the biometric
     * prompt is shown. The caller should catch it at the call site and trigger
     * the backup-restore recovery flow.
     *
     * @param activity The FragmentActivity hosting the biometric prompt
     * @param iv The 12-byte IV stored alongside the ciphertext during encryption
     * @param title Prompt title shown to the user
     * @param subtitle Optional subtitle (e.g., "Unlock wallet")
     * @throws AuthenticationCancelledException if the user cancelled
     * @throws AuthenticationLockedOutException if the device is temporarily locked out
     * @throws AuthenticationPermanentlyLockedOutException if permanently locked out
     * @throws AuthenticationFailedException for all other failures
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

    /**
     * Attempts to decrypt [ciphertext] with the master key WITHOUT showing
     * a BiometricPrompt. Succeeds silently when the Keystore auth-validity
     * window (set via [AuthPolicy.VALIDITY_DURATION_SECONDS]) is currently
     * open for the key. The caller MUST wipe the returned bytes once it's
     * done with the plaintext.
     *
     * Returns null when the window is expired
     * ([android.security.keystore.UserNotAuthenticatedException] from the
     * Cipher) or any other exception is hit. The caller should fall back
     * to the full [authenticateForDecrypt] path in that case.
     *
     * Why this is safe: `UserNotAuthenticatedException` is enforced in
     * secure hardware by Keystore — we cannot bypass the auth requirement
     * from app code. If the user authenticated recently enough for the
     * window to count, the decrypt is exactly equivalent to the next
     * decrypt after a fresh BiometricPrompt; if not, the cipher refuses
     * to operate and we get null.
     */
    fun tryDecryptWithinAuthWindow(
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray? {
        val cipher = try {
            keyManager.cipherForDecrypt(iv)
        } catch (e: Exception) {
            Log.w(TAG, "tryDecryptWithinAuthWindow: cipherForDecrypt failed; falling back to prompt", e)
            return null
        }
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            null  // expected when the auth-validity window has expired
        } catch (e: Exception) {
            Log.w(TAG, "tryDecryptWithinAuthWindow: doFinal failed unexpectedly; falling back to prompt", e)
            null
        }
    }

    /**
     * Attempts to obtain a Cipher initialized for encryption with the
     * master key WITHOUT showing a BiometricPrompt. Returns a fresh,
     * unused Cipher when the auth-validity window is open; null when
     * expired or anything else goes wrong, in which case the caller
     * should fall back to [authenticateForEncrypt].
     *
     * Implementation: probes the window with a throwaway cipher (a
     * 1-byte `doFinal` that forces Keystore's auth check), then on
     * success returns a *separate* cipher for the caller's actual
     * encrypt. The throwaway is necessary because GCM ciphers commit
     * to a fixed IV at `init()` time — reusing the probe cipher would
     * be a nonce-reuse vulnerability.
     */
    fun tryEncryptWithinAuthWindow(): Cipher? {
        val probeCipher = try {
            keyManager.cipherForEncrypt()
        } catch (e: Exception) {
            Log.w(TAG, "tryEncryptWithinAuthWindow: cipherForEncrypt (probe) failed; falling back to prompt", e)
            return null
        }
        try {
            probeCipher.doFinal(byteArrayOf(0x00))
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            return null  // expected when the auth-validity window has expired
        } catch (e: Exception) {
            Log.w(TAG, "tryEncryptWithinAuthWindow: probe doFinal failed unexpectedly; falling back to prompt", e)
            return null
        }
        return try {
            keyManager.cipherForEncrypt()
        } catch (e: Exception) {
            Log.w(TAG, "tryEncryptWithinAuthWindow: cipherForEncrypt (real) failed after successful probe; falling back to prompt", e)
            null
        }
    }

    /**
     * Presence-only authentication — shows the system biometric / device-credential
     * prompt purely to confirm "it's you", with NO [BiometricPrompt.CryptoObject]
     * and no crypto operation. For gating recoverable, non-secret actions (e.g.
     * sign-out) where a TEE-bound CryptoObject would be overkill and would couple
     * the action to the master key existing.
     *
     * Returns normally on success; throws the same exception types as
     * [authenticateForEncrypt] on cancel / lockout / failure so callers can treat
     * cancellation as "abort the action, change nothing".
     */
    suspend fun authenticatePresence(
        activity: FragmentActivity,
        title: String = "Confirm it's you",
        subtitle: String? = null,
    ): Unit = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!cont.isActive) return
                cont.resumeWithException(authErrorFor(errorCode, errString))
            }

            override fun onAuthenticationFailed() {
                // Single failed attempt — system shows retry / falls back to PIN.
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(promptInfo) // no CryptoObject — presence only

        cont.invokeOnCancellation { biometricPrompt.cancelAuthentication() }
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
                if (!cont.isActive) return
                val authenticatedCipher = result.cryptoObject?.cipher
                if (authenticatedCipher != null) {
                    cont.resume(AuthenticatedCipher(authenticatedCipher))
                } else {
                    cont.resumeWithException(
                        AuthenticationFailedException("CryptoObject cipher was null after authentication")
                    )
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!cont.isActive) return
                cont.resumeWithException(authErrorFor(errorCode, errString))
            }

            override fun onAuthenticationFailed() {
                // Called on each failed attempt (wrong fingerprint, etc.)
                // Don't cancel — the system will show retry or fall back to PIN.
                // After too many failures, onAuthenticationError will be called.
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
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

    /** Maps a [BiometricPrompt] error code to the typed exception callers expect. */
    private fun authErrorFor(errorCode: Int, errString: CharSequence): Exception = when (errorCode) {
        // User-initiated cancellation. ERROR_NEGATIVE_BUTTON typically won't fire in
        // our config (DEVICE_CREDENTIAL is allowed, so there's no negative button)
        // but we handle it defensively.
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED ->
            AuthenticationCancelledException(errString.toString())

        // Temporarily locked out after too many failed attempts (~30s).
        BiometricPrompt.ERROR_LOCKOUT ->
            AuthenticationLockedOutException(errString.toString())

        // Permanently locked out — requires device credential to reset.
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
            AuthenticationPermanentlyLockedOutException(errString.toString())

        else ->
            AuthenticationFailedException("[$errorCode] $errString")
    }

    private companion object {
        const val TAG = "BiometricGate"
    }
}

/**
 * Thrown when the user explicitly cancels biometric authentication.
 */
class AuthenticationCancelledException(message: String) : Exception(message)

/**
 * Thrown when biometric authentication is temporarily locked out after too many
 * failed attempts. Typically unlocks after ~30 seconds.
 */
class AuthenticationLockedOutException(message: String) : Exception(message)

/**
 * Thrown when biometric authentication is permanently locked out.
 * User must authenticate with device credential (PIN/pattern/password) to reset.
 */
class AuthenticationPermanentlyLockedOutException(message: String) : Exception(message)

/**
 * Thrown when biometric authentication fails for any other reason
 * (hardware error, no enrollment, etc.).
 */
class AuthenticationFailedException(message: String) : Exception(message)
