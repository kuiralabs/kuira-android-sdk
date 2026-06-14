package com.midnight.kuira.core.compact.proving

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.File
import java.nio.file.Files

/**
 * Coverage for [ProvingKeyManager.installWalletKeysFromAssets] — the offline
 * bundle path for the protocol-level wallet keys (#256), the in-APK alternative
 * to the S3 [ProvingKeyManager.downloadWalletKeys]. Mirrors
 * [InstallCircuitKeysFromAssetsTest] (mockk AssetManager, real temp filesDir).
 *
 * The contract the higher-level [ProvingKeyManager.ensureWalletKeysAvailable]
 * relies on: returns true (and [ProvingKeyManager.hasWalletKeys] holds) when the
 * full set is bundled; returns false — so the caller falls back to download —
 * when the bundle is absent or incomplete.
 */
class InstallWalletKeysFromAssetsTest {

    private val context: Context = mockk()
    private val assets: AssetManager = mockk()
    private lateinit var tempDir: File
    private lateinit var keysDir: File

    // Mirrors ProvingKeyManager.WALLET_KEY_FILES (private there): the zswap/dust
    // circuit keys plus the full BLS param set k=5..15.
    private val walletRelatives = listOf(
        "zswap/spend.prover", "zswap/spend.verifier", "zswap/spend.bzkir",
        "zswap/output.prover", "zswap/output.verifier", "zswap/output.bzkir",
        "zswap/sign.prover", "zswap/sign.verifier", "zswap/sign.bzkir",
        "dust/spend.prover", "dust/spend.verifier", "dust/spend.bzkir",
    )
    private val blsNames = (5..15).map { "bls_midnight_2p$it" }
    private val allWalletFiles = walletRelatives + blsNames

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("pkm-wallet-assets").toFile()
        every { context.filesDir } returns tempDir
        every { context.assets } returns assets
        keysDir = File(tempDir, "proving_keys")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Distinct, non-empty bytes per file so a wrong/stale copy is detectable. */
    private fun bytesFor(relative: String): ByteArray =
        ByteArray(32) { (relative.hashCode() + it).toByte() }

    /**
     * Stub `assets/wallet-keys` to contain [present]. A file listed as present
     * streams [bytesFor]; one omitted from [present] throws FileNotFoundException
     * on open — exactly how AssetManager reports a key the build didn't stage.
     */
    private fun stageBundle(present: Collection<String> = allWalletFiles) {
        // Non-empty listing → the "is anything bundled?" gate passes.
        every { assets.list("wallet-keys") } returns arrayOf("zswap", "dust") + blsNames.toTypedArray()
        for (rel in allWalletFiles) {
            val path = "wallet-keys/$rel"
            if (rel in present) {
                every { assets.open(path) } answers { ByteArrayInputStream(bytesFor(rel)) }
            } else {
                every { assets.open(path) } throws FileNotFoundException(path)
            }
        }
    }

    @Test
    fun `installs the full wallet key set and satisfies hasWalletKeys`() {
        stageBundle()

        val ok = ProvingKeyManager(context).installWalletKeysFromAssets()

        assertTrue("complete bundle → installed", ok)
        allWalletFiles.forEach { rel ->
            assertArrayEquals(
                "bundled $rel copied verbatim",
                bytesFor(rel), File(keysDir, rel).readBytes(),
            )
        }
        assertEquals(
            ProvingKeyManager.CURRENT_VERSION.toString(),
            File(keysDir, "version.txt").readText().trim(),
        )
    }

    @Test
    fun `returns false when no wallet keys are bundled`() {
        // App didn't ship wallet keys at all — caller must fall back to download.
        every { assets.list("wallet-keys") } returns null

        assertFalse(ProvingKeyManager(context).installWalletKeysFromAssets())
        assertFalse("nothing installed", File(keysDir, "version.txt").exists())
    }

    @Test
    fun `returns false on an incomplete bundle so the caller falls back`() {
        stageBundle(present = allWalletFiles - "dust/spend.prover")

        val ok = ProvingKeyManager(context).installWalletKeysFromAssets()

        assertFalse("a missing key means the bundle can't stand alone", ok)
        // Atomic copies: the files that WERE bundled are intact (download skips them).
        assertArrayEquals(
            bytesFor("zswap/spend.prover"),
            File(keysDir, "zswap/spend.prover").readBytes(),
        )
        assertFalse(File(keysDir, "dust/spend.prover").exists())
    }

    @Test
    fun `ignores a bundle built for a different ledger version`() {
        stageBundle()
        // The bundle declares a version that doesn't match the SDK's CURRENT_VERSION.
        val otherVersion = (ProvingKeyManager.CURRENT_VERSION - 1).toString()
        every { assets.open("wallet-keys/version.txt") } answers {
            ByteArrayInputStream(otherVersion.toByteArray())
        }

        val ok = ProvingKeyManager(context).installWalletKeysFromAssets()

        assertFalse("a version-mismatched bundle must be ignored → fall back to download", ok)
        assertFalse("nothing stamped", File(keysDir, "version.txt").exists())
    }

    @Test
    fun `a changed apk stamp refreshes a drifted bundled key`() {
        // Recompile/version-bump path: device holds an OLD key; the new APK ships
        // new bytes. A changed stamp forces content re-verify → refresh.
        keysDir.mkdirs()
        File(keysDir, "zswap").mkdirs()
        File(keysDir, "zswap/spend.prover").writeBytes(ByteArray(32) { 0xAB.toByte() })
        File(keysDir, ".wallet-keys.apk_stamp").writeText("build-41")
        stageBundle()

        ProvingKeyManager(context, apkStampOverride = { "build-42" }).installWalletKeysFromAssets()

        assertArrayEquals(
            "stale bundled key must be refreshed to current asset bytes",
            bytesFor("zswap/spend.prover"),
            File(keysDir, "zswap/spend.prover").readBytes(),
        )
        assertEquals("build-42", File(keysDir, ".wallet-keys.apk_stamp").readText().trim())
    }
}
