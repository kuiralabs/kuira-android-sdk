// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumentation tests for [SeedVault] file lifecycle.
 *
 * These tests cover the parts of SeedVault that don't require BiometricPrompt:
 * - File existence checks ([SeedVault.hasSeed])
 * - File deletion ([SeedVault.deleteSeed])
 * - Stale temp file cleanup
 *
 * **Not covered here (require manual testing on device with biometrics):**
 * - [SeedVault.storeSeed] full encrypt flow
 * - [SeedVault.loadSeed] full decrypt flow
 * - Round-trip verification
 * - KeyPermanentlyInvalidatedException recovery
 *
 * Those flows are tested during E2E verification (Step 8A.9).
 */
@RunWith(AndroidJUnit4::class)
class SeedVaultInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // We only exercise non-biometric operations, so we can pass a minimal
    // BiometricGate wired to a WalletKeyManager that won't actually be used.
    private val seedVault = SeedVault(context, BiometricGate(WalletKeyManager()))

    private val seedFile: File
        get() = File(context.filesDir, SeedVault.SEED_FILE_NAME)

    private val tempFile: File
        get() = File(context.filesDir, SeedVault.TEMP_FILE_NAME)

    @Before
    fun setUp() {
        seedVault.deleteSeed()
    }

    @After
    fun tearDown() {
        seedVault.deleteSeed()
    }

    @Test
    fun hasSeed_returnsFalse_whenNoSeedStored() {
        assertFalse(seedVault.hasSeed())
    }

    @Test
    fun hasSeed_returnsTrue_whenFileExists() {
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
        seedVault.deleteSeed()
        seedVault.deleteSeed()
        assertFalse(seedVault.hasSeed())
    }

    @Test
    fun deleteSeed_cleansUpStaleTempFile() {
        // Simulate a crashed write: temp file exists but main file doesn't
        tempFile.writeBytes(ByteArray(124))
        assertTrue(tempFile.exists())
        assertFalse(seedVault.hasSeed())

        seedVault.deleteSeed()

        assertFalse(tempFile.exists())
        assertFalse(seedFile.exists())
    }

    @Test
    fun deleteSeed_cleansUpBothMainAndTempFiles() {
        seedFile.writeBytes(ByteArray(124))
        tempFile.writeBytes(ByteArray(124))

        seedVault.deleteSeed()

        assertFalse(seedFile.exists())
        assertFalse(tempFile.exists())
    }
}
