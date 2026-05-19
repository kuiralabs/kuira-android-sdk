package com.midnight.kuira.core.compact.proving

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression coverage for [ProvingKeyManager.ensureWalletKeysAvailable].
 *
 * Two paths to lock down:
 *  - **Fast path** — `/data/local/tmp/` had the keys (dev box where
 *    `adb push` ran previously, BBoard canary, etc.). [installFromLocalTmp]
 *    populated everything; [hasWalletKeys] reports `true`. The S3
 *    download must NOT fire — that would burn ~24MB of bandwidth per
 *    launch for nothing.
 *  - **Production fallback** — fresh emulator / new device, nothing in
 *    `/data/local/tmp/`. After the local-tmp pass, [hasWalletKeys]
 *    stays `false`. [downloadWalletKeys] MUST fire so the user isn't
 *    stranded behind a `BLS params file not found` proving error
 *    (the 2026-05-19 emulator-B incident in Kicks).
 *
 * Failing either assertion = the runtime regression that broke
 * emulator B has crept back in. Hard to spot in manual QA because
 * any dev box that's ever staged `/data/local/tmp/` for one app
 * carries those files forward to every future install.
 */
class EnsureWalletKeysAvailableTest {

    private val context: Context = mockk()
    private lateinit var tempDir: File

    @Before
    fun setup() {
        // ProvingKeyManager's constructor reads `context.filesDir` to
        // derive `keysDir`. Point it at a real (empty) temp directory
        // so construction succeeds; the spy intercepts every method
        // that would actually touch it.
        tempDir = Files.createTempDirectory("pkm-test").toFile()
        every { context.filesDir } returns tempDir
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `fast path — local tmp had keys so download is skipped`() = runTest {
        val pkm = spyk(ProvingKeyManager(context))
        every { pkm.installFromLocalTmp() } returns true
        every { pkm.hasWalletKeys() } returns true
        val log = mutableListOf<String>()

        pkm.ensureWalletKeysAvailable(logger = { log += it })

        // The whole point of the helper: when the dev shortcut covered
        // the keys, we must NOT spend the user's bandwidth.
        coVerify(exactly = 0) { pkm.downloadWalletKeys(any()) }
        assertEquals(
            "no log lines on the fast path — silence == happy",
            emptyList<String>(),
            log,
        )
    }

    @Test
    fun `production fallback — empty local tmp triggers S3 download`() = runTest {
        val pkm = spyk(ProvingKeyManager(context))
        every { pkm.installFromLocalTmp() } returns false
        // First call (the gate check) returns false → triggers download.
        // Second call (the post-download log line interpolation) returns
        // true → the download succeeded. Sequencing matters: a single
        // `returns false` would log "hasWalletKeys=false" and mask a
        // real failure.
        every { pkm.hasWalletKeys() } returnsMany listOf(false, true)
        coEvery { pkm.downloadWalletKeys(any()) } returns Unit
        val log = mutableListOf<String>()

        pkm.ensureWalletKeysAvailable(logger = { log += it })

        coVerify(exactly = 1) { pkm.downloadWalletKeys(any()) }
        // Two log lines bookend the download — start + end. Assert on
        // the contract, not the exact prose, so wording can drift
        // without breaking the test.
        assertEquals(2, log.size)
        assert(log[0].contains("missing")) {
            "first log line should announce missing keys, got: ${log[0]}"
        }
        assert(log[1].contains("complete") && log[1].contains("hasWalletKeys=true")) {
            "second log line should report download complete + verify keys present, got: ${log[1]}"
        }
    }
}
