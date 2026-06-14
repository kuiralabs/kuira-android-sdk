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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `production fallback — no local tmp and no bundle triggers S3 download`() = runTest {
        val pkm = spyk(ProvingKeyManager(context))
        every { pkm.installFromLocalTmp() } returns false
        // App didn't bundle wallet keys → the offline-bundle step is a no-op.
        every { pkm.installWalletKeysFromAssets(any(), any()) } returns false
        // Three hasWalletKeys() calls in order now: the bundle gate (false →
        // try assets), the download gate (false → download), the post-download
        // log interpolation (true → succeeded). A single `returns false` would
        // log "hasWalletKeys=false" and mask a real failure.
        every { pkm.hasWalletKeys() } returnsMany listOf(false, false, true)
        coEvery { pkm.downloadWalletKeys(any()) } returns Unit
        val log = mutableListOf<String>()

        pkm.ensureWalletKeysAvailable(logger = { log += it })

        coVerify(exactly = 1) { pkm.downloadWalletKeys(any()) }
        // Two log lines bookend the download — start + end. Assert on
        // the contract, not the exact prose, so wording can drift
        // without breaking the test.
        assertEquals(2, log.size)
        assert(log[0].contains("download")) {
            "first log line should announce the download, got: ${log[0]}"
        }
        assert(log[1].contains("complete") && log[1].contains("hasWalletKeys=true")) {
            "second log line should report download complete + verify keys present, got: ${log[1]}"
        }
    }

    @Test
    fun `bundle path — bundled keys satisfy the wallet and skip the download`() = runTest {
        val pkm = spyk(ProvingKeyManager(context))
        every { pkm.installFromLocalTmp() } returns false
        // The offline bundle (APK assets) provisions everything…
        every { pkm.installWalletKeysFromAssets(any(), any()) } returns true
        // …so the gate after the bundle step reports complete and S3 is skipped.
        every { pkm.hasWalletKeys() } returnsMany listOf(false, true)
        val log = mutableListOf<String>()

        pkm.ensureWalletKeysAvailable(logger = { log += it })

        coVerify(exactly = 0) { pkm.downloadWalletKeys(any()) }
        assertEquals(
            "bundled keys are silent like the fast path — no S3 log lines",
            emptyList<String>(),
            log,
        )
    }

    // ── Real file behaviour: 0-byte keys must read as missing + self-heal ──
    //
    // The on-device failure: an interrupted local-tmp copy left a 0-byte
    // dust/spend.prover. The old exists()-only checks treated it as valid, so
    // installFromLocalTmp skipped it forever and the prover loaded an empty key
    // ("expected header tag 'midnight:ir-source[v2]:', got ''"). These use a
    // REAL ProvingKeyManager over the temp filesDir (not the spy above).

    private val walletRelatives = listOf(
        "zswap/spend.prover", "zswap/spend.verifier", "zswap/spend.bzkir",
        "zswap/output.prover", "zswap/output.verifier", "zswap/output.bzkir",
        "zswap/sign.prover", "zswap/sign.verifier", "zswap/sign.bzkir",
        "dust/spend.prover", "dust/spend.verifier", "dust/spend.bzkir",
    )
    // Full BLS set a complete install needs: k=13/14/15 for the wallet,
    // k=5..12 for small contract circuits (mirrors ProvingKeyManager's
    // BLS_PARAM_FILES). A "complete" staging must include all of them, else
    // hasWalletKeys() — which now requires the full set — reports incomplete.
    private val blsNames = (5..15).map { "bls_midnight_2p$it" }

    /** Stage a complete, non-empty key set under `<localTmp>/{bboard_keys,wallet_keys}`. */
    private fun stageCompleteLocalTmp(localTmp: File) {
        val bboard = File(localTmp, "bboard_keys").apply { mkdirs() }
        blsNames.forEach { File(bboard, it).writeBytes(ByteArray(8) { 1 }) }
        val wallet = File(localTmp, "wallet_keys")
        walletRelatives.forEach {
            File(wallet, it).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(8) { 1 })
        }
    }

    @Test
    fun `hasWalletKeys is false when a cached key is zero bytes`() {
        val localTmp = Files.createTempDirectory("pkm-stage").toFile()
        try {
            stageCompleteLocalTmp(localTmp)
            val pkm = ProvingKeyManager(context)
            assertTrue("complete staging populates the cache", pkm.installFromLocalTmp(localTmp))

            // Truncate one cached key — the on-device failure mode.
            File(File(tempDir, "proving_keys"), "dust/spend.prover").writeBytes(ByteArray(0))

            assertFalse("a 0-byte key must read as missing", pkm.hasWalletKeys())
        } finally {
            localTmp.deleteRecursively()
        }
    }

    @Test
    fun `installFromLocalTmp overwrites a pre-existing zero-byte key`() {
        val localTmp = Files.createTempDirectory("pkm-stage").toFile()
        try {
            // Cache already holds a 0-byte key + a current version.txt — the
            // stranded state the old exists()-only check never repaired.
            val keysDir = File(tempDir, "proving_keys").apply { mkdirs() }
            File(keysDir, "dust").mkdirs()
            File(keysDir, "dust/spend.prover").writeBytes(ByteArray(0))
            File(keysDir, "version.txt").writeText(ProvingKeyManager.CURRENT_VERSION.toString())

            stageCompleteLocalTmp(localTmp)
            val pkm = ProvingKeyManager(context)

            val ok = pkm.installFromLocalTmp(localTmp)

            assertTrue("0-byte key repaired from staging → hasWalletKeys", ok)
            assertTrue(
                "the 0-byte key was overwritten with real bytes",
                File(keysDir, "dust/spend.prover").length() > 0L,
            )
        } finally {
            localTmp.deleteRecursively()
        }
    }
}
