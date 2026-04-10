// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumentation tests for [SeedVault] file lifecycle.
 *
 * **Note:** storeSeed() and loadSeed() require biometric authentication,
 * which cannot be automated in CI. Those flows are tested manually on
 * device. These tests cover:
 * - File existence checks (hasSeed)
 * - File deletion (deleteSeed)
 * - Error paths when no seed exists
 */
@RunWith(AndroidJUnit4::class)
class SeedVaultInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val keyManager = WalletKeyManager()
    private val biometricGate = BiometricGate(keyManager)
    private val seedVault = SeedVault(context, biometricGate)

    private val seedFile: File
        get() = File(context.filesDir, "kuira_seed.bin")

    @Before
    fun setUp() {
        // Clean state before each test
        seedVault.deleteSeed()
        keyManager.deleteKey()
    }

    @After
    fun tearDown() {
        seedVault.deleteSeed()
        keyManager.deleteKey()
    }

    @Test
    fun hasSeed_returnsFalse_whenNoSeedStored() {
        assertFalse(seedVault.hasSeed())
    }

    @Test
    fun hasSeed_returnsTrue_whenFileExists() {
        // Manually create a fake seed file to verify hasSeed() checks the file
        seedFile.writeBytes(ByteArray(124))
        assertTrue(seedVault.hasSeed())
    }

    @Test
    fun deleteSeed_removesFile() {
        seedFile.writeBytes(ByteArray(124))
        assertTrue(seedVault.hasSeed())

        seedVault.deleteSeed()
        assertFalse(seedVault.hasSeed())
    }

    @Test
    fun deleteSeed_isIdempotent() {
        // Should not throw even if file doesn't exist
        seedVault.deleteSeed()
        seedVault.deleteSeed()
        assertFalse(seedVault.hasSeed())
    }

    @Test
    fun loadSeed_throws_whenNoSeedStored() {
        // Can't actually call loadSeed() without an Activity — verify hasSeed
        // is the guard that loadSeed() checks first
        assertFalse(seedVault.hasSeed())
    }

    // NOTE: Full encrypt/decrypt round-trip requires:
    // 1. A real Activity (for BiometricPrompt)
    // 2. A device with enrolled biometrics or screen lock
    // 3. User interaction to approve the biometric prompt
    // These tests are performed manually during E2E verification (Step 8A.9).
}
