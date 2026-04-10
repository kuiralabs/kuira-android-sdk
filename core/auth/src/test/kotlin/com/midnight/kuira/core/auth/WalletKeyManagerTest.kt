// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WalletKeyManager] constants and configuration.
 *
 * Note: Actual Keystore operations (generateKey, cipherForEncrypt/Decrypt)
 * require Android instrumentation tests since AndroidKeyStore provider
 * is only available on real devices/emulators. Tests that exercise the
 * Keystore belong in `androidTest/`.
 *
 * These tests verify the configuration values that can be tested without hardware.
 */
class WalletKeyManagerTest {

    @Test
    fun `master key alias is stable and non-empty`() {
        // The alias is used to look up the key — changing it would orphan existing keys
        assertEquals("kuira_master_key", WalletKeyManager.MASTER_KEY_ALIAS)
        assertTrue(WalletKeyManager.MASTER_KEY_ALIAS.isNotBlank())
    }

    @Test
    fun `GCM IV length is 12 bytes per NIST standard`() {
        // AES-GCM mandates 12-byte (96-bit) IV for optimal security
        assertEquals(12, WalletKeyManager.GCM_IV_LENGTH)
    }

    @Test
    fun `GCM tag length is 128 bits for full authentication strength`() {
        // 128-bit tag is the maximum and recommended for AES-GCM
        assertEquals(128, WalletKeyManager.GCM_TAG_LENGTH)
    }
}
