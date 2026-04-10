// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Security level of the Keystore key backing.
 *
 * Ordered from strongest to weakest — StrongBox is a dedicated secure element
 * (separate CPU, tamper-resistant), TEE shares the main processor but runs in
 * a hardware-isolated TrustZone, and Software has no hardware backing.
 */
enum class KeySecurityLevel {
    STRONGBOX,
    TEE,
    SOFTWARE
}

/**
 * Detects device hardware security capabilities.
 *
 * Used by [WalletKeyManager] to determine the best available security level
 * and by the onboarding UI to inform users about their device's protection.
 */
@Singleton
class SecurityCapabilities @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val biometricManager = BiometricManager.from(context)

    /**
     * Whether the device has a dedicated StrongBox secure element
     * (e.g., Google Titan M2, Samsung eSE, Qualcomm SPU).
     */
    val hasStrongBox: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /**
     * Whether the device can authenticate with Class 3 (strong) biometrics.
     * This is fingerprint, face, or iris that meets Android CDD requirements.
     */
    val hasStrongBiometric: Boolean
        get() = biometricManager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Whether the device has any enrolled authenticator (biometric OR device credential).
     * Returns true if at least a PIN/pattern/password is set.
     */
    val hasAnyAuthenticator: Boolean
        get() = biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Whether strong biometrics are available but not yet enrolled.
     * Used to prompt the user to enroll via Settings.ACTION_BIOMETRIC_ENROLL.
     */
    val biometricNotEnrolled: Boolean
        get() = biometricManager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

    /**
     * Whether the device has no biometric hardware at all.
     */
    val noBiometricHardware: Boolean
        get() = biometricManager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

    /**
     * Query the actual security level of an existing Keystore key.
     *
     * @param alias The Keystore alias of the key to check
     * @return The security level, or null if the key doesn't exist
     */
    fun getKeySecurityLevel(alias: String): KeySecurityLevel? {
        val keyInfo = getKeyInfo(alias) ?: return null
        return when (keyInfo.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TEE
            else -> KeySecurityLevel.SOFTWARE
        }
    }

    /**
     * Whether a key's user authentication requirement is enforced by secure hardware.
     *
     * @param alias The Keystore alias of the key to check
     * @return true if hardware-enforced, false if software-only, null if key doesn't exist
     */
    fun isAuthEnforcedByHardware(alias: String): Boolean? {
        val keyInfo = getKeyInfo(alias) ?: return null
        @Suppress("DEPRECATION")
        return keyInfo.isUserAuthenticationRequirementEnforcedBySecureHardware
    }

    private fun getKeyInfo(alias: String): KeyInfo? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry ?: return null
        val key: SecretKey = entry.secretKey

        val factory = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
        return factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }
}
