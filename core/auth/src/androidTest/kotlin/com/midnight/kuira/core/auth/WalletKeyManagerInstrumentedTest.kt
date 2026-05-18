// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for [WalletKeyManager].
 *
 * These tests run on a real device/emulator and exercise the Android Keystore.
 *
 * **Note:** Tests that exercise a Cipher *after* key generation can't be
 * fully automated — they need a real BiometricPrompt or device-credential
 * auth to satisfy the [AuthPolicy.VALIDITY_DURATION_SECONDS] window
 * before the Cipher will run doFinal. Those are tested manually.
 *
 * These tests use the actual Keystore but with a test-specific alias
 * to avoid interfering with production keys.
 */
@RunWith(AndroidJUnit4::class)
class WalletKeyManagerInstrumentedTest {

    private val keyManager = WalletKeyManager()

    @Before
    fun setUp() {
        // Clean up any leftover test key
        keyManager.deleteKey()
    }

    @After
    fun tearDown() {
        // Always clean up
        keyManager.deleteKey()
    }

    @Test
    fun hasKey_returnsFalse_whenNoKeyExists() {
        assertFalse(keyManager.hasKey())
    }

    @Test
    fun generateKey_createsKey_andHasKeyReturnsTrue() {
        keyManager.generateKey()
        assertTrue(keyManager.hasKey())
    }

    @Test
    fun generateKey_throwsIfKeyAlreadyExists() {
        keyManager.generateKey()

        try {
            keyManager.generateKey()
            fail("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("already exists"))
        }
    }

    @Test
    fun deleteKey_removesKey() {
        keyManager.generateKey()
        assertTrue(keyManager.hasKey())

        keyManager.deleteKey()
        assertFalse(keyManager.hasKey())
    }

    @Test
    fun deleteKey_isIdempotent() {
        // Should not throw even if key doesn't exist
        keyManager.deleteKey()
        keyManager.deleteKey()
    }

    @Test
    fun cipherForEncrypt_throwsWhenNoKeyExists() {
        try {
            keyManager.cipherForEncrypt()
            fail("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Master key not found"))
        }
    }

    @Test
    fun cipherForDecrypt_rejectsInvalidIVLength() {
        keyManager.generateKey()

        // Too short
        try {
            keyManager.cipherForDecrypt(ByteArray(8))
            fail("Should have thrown IllegalArgumentException for 8-byte IV")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("12 bytes"))
        }

        // Too long
        try {
            keyManager.cipherForDecrypt(ByteArray(16))
            fail("Should have thrown IllegalArgumentException for 16-byte IV")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("12 bytes"))
        }
    }

    // NOTE: cipherForEncrypt() and cipherForDecrypt() with valid IV will
    // succeed at Cipher.init() but the cipher won't be usable until the
    // user has authenticated within the last [AuthPolicy.VALIDITY_DURATION_SECONDS]
    // — either via BiometricPrompt or device-credential. Full encrypt /
    // decrypt round-trip requires manual biometric testing.
}
