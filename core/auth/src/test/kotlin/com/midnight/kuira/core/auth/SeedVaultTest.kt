// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.crypto.Cipher

/**
 * Unit tests for [SeedVault] — the encrypted-seed file lifecycle.
 *
 * Coverage focus: the file IO + cipher-branching contract. Real Keystore
 * + biometric prompts live behind [BiometricGate] and are mocked out so
 * the test runs purely on the JVM (Robolectric for `Context.filesDir`,
 * mockk for the cipher chain). What we verify here:
 *  - Silent (auth-window open) path uses [BiometricGate.tryEncryptWithinAuthWindow]
 *    and never invokes the BiometricPrompt.
 *  - Prompt (auth-window expired) path falls back to
 *    [BiometricGate.authenticateForEncrypt].
 *  - Wrong-size files on disk surface as [CorruptedSeedException]
 *    (the caller's signal to trigger a restore flow).
 *  - Writes are atomic — `kuira_seed.bin.tmp` never lingers after a
 *    successful `storeSeed`; the final file is exactly
 *    `IV (12) + plaintext (96) + GCM tag (16) = 124` bytes.
 *
 * Robolectric is overkill for *only* the file IO, but it's required
 * to get a real `Context.filesDir` without standing up a full
 * Android instrumentation harness. Cheap (~200 ms per test) and
 * keeps the test pure JVM.
 */
@RunWith(RobolectricTestRunner::class)
class SeedVaultTest {

    private lateinit var context: Context
    private lateinit var seedFile: File
    private lateinit var tempFile: File

    private val biometricGate: BiometricGate = mockk()
    private val activity: FragmentActivity = mockk(relaxed = true)

    private lateinit var vault: SeedVault

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        seedFile = File(context.filesDir, SeedVault.SEED_FILE_NAME)
        tempFile = File(context.filesDir, SeedVault.TEMP_FILE_NAME)
        // Defense in depth — Robolectric files dir is per-test but
        // re-using a cached instance across tests would still bite.
        seedFile.delete()
        tempFile.delete()
        vault = SeedVault(context, biometricGate)
    }

    @After
    fun tearDown() {
        seedFile.delete()
        tempFile.delete()
    }

    @Test
    fun `storeSeed silent path uses tryEncryptWithinAuthWindow`() = runTest {
        val cipher = fakeEncryptCipher()
        every { biometricGate.tryEncryptWithinAuthWindow() } returns cipher

        vault.storeSeed(activity) { producePlaintextSeed() }

        verify(exactly = 1) { biometricGate.tryEncryptWithinAuthWindow() }
        // Prompt branch must NOT fire when the silent path supplied a cipher —
        // we'd be needlessly waking the user mid-restore (the bug auth-window
        // refactor was meant to kill).
        coVerify(exactly = 0) {
            biometricGate.authenticateForEncrypt(any(), any(), any())
        }
        assertTrue("seed file written", seedFile.exists())
    }

    @Test
    fun `storeSeed prompt path calls authenticateForEncrypt when window expired`() = runTest {
        // `null` from tryEncryptWithinAuthWindow signals "auth-validity window
        // expired or probe failed" — SeedVault must fall back to the full
        // BiometricPrompt path.
        every { biometricGate.tryEncryptWithinAuthWindow() } returns null
        coEvery {
            biometricGate.authenticateForEncrypt(activity, any(), any())
        } returns AuthenticatedCipher(fakeEncryptCipher())

        vault.storeSeed(activity) { producePlaintextSeed() }

        verify(exactly = 1) { biometricGate.tryEncryptWithinAuthWindow() }
        coVerify(exactly = 1) {
            biometricGate.authenticateForEncrypt(activity, any(), any())
        }
        assertTrue("seed file written via prompt path", seedFile.exists())
    }

    @Test
    fun `loadSeed throws CorruptedSeedException on wrong-size file`() = runTest {
        // 50 bytes of garbage — less than the minimum (12 IV + 96 plaintext
        // + 16 tag = 124 bytes). storeSeed never produces a file this short,
        // so we expect the caller to treat it as corruption.
        seedFile.writeBytes(ByteArray(50) { it.toByte() })

        val ex = assertThrows(CorruptedSeedException::class.java) {
            // runTest's TestScope can't `runBlocking` — use the inline
            // `runTest` wrapper. assertThrows on suspend functions requires
            // the `runBlocking` bridge here because the failure is
            // synchronous from check() / readBytes() before the first
            // suspension point.
            kotlinx.coroutines.runBlocking { vault.loadSeed(activity) }
        }
        assertTrue(
            "Exception should mention the size mismatch, got: ${ex.message}",
            ex.message?.contains("wrong size") == true,
        )
    }

    @Test
    fun `storeSeed writes IV+ciphertext atomically — temp file gone, final size correct`() = runTest {
        val cipher = fakeEncryptCipher()
        every { biometricGate.tryEncryptWithinAuthWindow() } returns cipher

        vault.storeSeed(activity) { producePlaintextSeed() }

        // After a successful storeSeed:
        //  - the temp file is renamed away (atomic write contract)
        //  - the final file is exactly IV (12) + plaintext (96) + tag (16) = 124 bytes
        assertFalse(
            "temp file must be renamed away after a successful store — " +
                "leftover temp = crash mid-write",
            tempFile.exists(),
        )
        assertEquals(
            "final file size = IV + plaintext + GCM tag",
            EXPECTED_FILE_SIZE,
            seedFile.length(),
        )

        // Bonus: the IV that was prepended must match the cipher's iv.
        val onDisk = seedFile.readBytes()
        val ivOnDisk = onDisk.copyOfRange(0, WalletKeyManager.GCM_IV_LENGTH)
        assertArrayEquals(
            "stored IV prefix must match cipher.iv (otherwise loadSeed will hit the wrong nonce)",
            FAKE_IV,
            ivOnDisk,
        )
    }

    @Test
    fun `hasSeed is false when no file exists`() = runTest {
        assertFalse("no file → no seed", vault.hasSeed())
    }

    @Test
    fun `hasSeed is false for a zero-byte file — the field-bricking case`() = runTest {
        // Reproduces the on-device failure: interrupted write / recovery churn
        // left a 0-byte kuira_seed.bin. exists() is true but the file is empty.
        // hasSeed() must report false so WalletSeedSource re-derives from PRF
        // instead of trapping on CorruptedSeedException on every sign-in.
        seedFile.writeBytes(ByteArray(0))
        assertTrue("file exists on disk", seedFile.exists())
        assertFalse("empty file is not a valid seed", vault.hasSeed())
    }

    @Test
    fun `hasSeed is false for a truncated file`() = runTest {
        seedFile.writeBytes(ByteArray(50) { it.toByte() }) // < the 124-byte valid size
        assertTrue(seedFile.exists())
        assertFalse("wrong-size file is not a valid seed", vault.hasSeed())
    }

    @Test
    fun `hasSeed is true after a successful storeSeed`() = runTest {
        every { biometricGate.tryEncryptWithinAuthWindow() } returns fakeEncryptCipher()
        vault.storeSeed(activity) { producePlaintextSeed() }

        assertTrue("a valid ${EXPECTED_FILE_SIZE}-byte seed reads as present", vault.hasSeed())
    }

    // ── Helpers ──

    private fun producePlaintextSeed(): PlaintextSeed = PlaintextSeed(
        mnemonicEntropy = ByteArray(PlaintextSeed.ENTROPY_SIZE) { 0xAA.toByte() },
        bip39Seed = ByteArray(PlaintextSeed.SEED_SIZE) { 0xBB.toByte() },
    )

    /**
     * Returns a mocked [Cipher] that pretends to produce 112 bytes of
     * ciphertext (96 plaintext + 16 GCM auth tag) for any input, and
     * reports a fixed [FAKE_IV] so we can assert on what's written.
     *
     * We don't run real AES here — SeedVault's contract is "write
     * `cipher.iv || cipher.doFinal(plaintext)` atomically". The real
     * AES-GCM behavior lives in the JDK / Keystore and is tested
     * separately (`androidTest` instrumentation against the device's
     * actual hardware-backed key).
     */
    private fun fakeEncryptCipher(): Cipher {
        val cipher = mockk<Cipher>()
        every { cipher.iv } returns FAKE_IV
        every { cipher.doFinal(any<ByteArray>()) } returns ByteArray(
            PlaintextSeed.PLAINTEXT_SIZE + GCM_TAG_BYTES,
        ) { 0xCC.toByte() }
        return cipher
    }

    companion object {
        private const val GCM_TAG_BYTES = WalletKeyManager.GCM_TAG_LENGTH / 8
        private const val EXPECTED_FILE_SIZE: Long =
            (WalletKeyManager.GCM_IV_LENGTH + PlaintextSeed.PLAINTEXT_SIZE + GCM_TAG_BYTES).toLong()
        private val FAKE_IV = ByteArray(WalletKeyManager.GCM_IV_LENGTH) { it.toByte() }
    }
}
