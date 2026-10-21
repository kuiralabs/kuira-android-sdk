package com.midnight.kuira.core.compact.proving

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Coverage for the opt-in large-circuit parameters (`kuiraContract { maxCircuitK = … }`).
 *
 * A circuit is proved with the universal parameter sized to it exactly, so a contract at
 * k=16 or above needs a parameter file that the baseline install never provisions. These
 * tests pin the two halves of that being *optional*: it installs when an app bundled it,
 * and its absence never makes an otherwise-complete install look broken.
 *
 * Instrumented rather than a JVM unit test because the behaviour under test is reading the
 * APK's own asset bundle. It runs against the **test** APK's context, whose assets hold the
 * stand-in parameter files under `androidTest/assets/wallet-keys/` — real SRS files are
 * 12MB+ and nothing here parses them, only copies them.
 */
@RunWith(AndroidJUnit4::class)
class OptionalBlsParamsTest {

    private val context = InstrumentationRegistry.getInstrumentation().context
    private lateinit var manager: ProvingKeyManager

    @Before
    fun setUp() {
        manager = ProvingKeyManager(context)
        manager.clearCache()
    }

    @Test
    fun installsTheSizesTheAppBundled() {
        val installed = manager.installOptionalBlsParamsFromAssets()

        assertEquals("both bundled fixtures should install", 2, installed)
        assertTrue(File(manager.keysDir, "bls_midnight_2p16").length() > 0)
        assertTrue(File(manager.keysDir, "bls_midnight_2p17").length() > 0)
    }

    /**
     * k=18 is within the prover's ceiling but is not in this fixture bundle — the ordinary
     * case for an app that asked for k=17 and no further. A size the app never requested
     * must be skipped quietly, not reported as a failure.
     */
    @Test
    fun aSizeTheAppDidNotBundleIsNotAnError() {
        manager.installOptionalBlsParamsFromAssets()

        assertFalse(File(manager.keysDir, "bls_midnight_2p18").exists())
    }

    @Test
    fun isIdempotentAndCheapOnTheSecondCall() {
        assertEquals(2, manager.installOptionalBlsParamsFromAssets())

        // Nothing left to do — and nothing re-copied, which is what keeps this safe to
        // call on every provisioning pass.
        assertEquals(0, manager.installOptionalBlsParamsFromAssets())
    }

    /**
     * The regression this guards: these parameters are deliberately absent from the
     * completeness check, so a device holding a complete baseline install must not be
     * judged incomplete for lacking them — that would send every launch back to the S3
     * download for files S3 is never asked for.
     */
    @Test
    fun theirAbsenceDoesNotMakeAnInstallIncomplete() {
        // Fake a complete baseline install: every required file present, version stamped.
        plantCompleteBaselineInstall()
        assertTrue("fixture must represent a complete install", manager.hasWalletKeys())

        // No large params on device at all, yet the install stays complete.
        assertFalse(File(manager.keysDir, "bls_midnight_2p16").exists())
        assertTrue(manager.hasWalletKeys())
        assertTrue(manager.hasBLSParams())
    }

    /**
     * The staleness trap: provisioning short-circuits once the install is complete, so
     * installing these only on the incomplete path would mean a device that was already
     * set up never picks up a size added by an app update — and then fails at prove time
     * with a parameter file it was actually shipped.
     */
    @Test
    fun installsIntoAnAlreadyCompleteInstall() {
        plantCompleteBaselineInstall()
        assertTrue(manager.hasWalletKeys())

        val installed = manager.installOptionalBlsParamsFromAssets()

        assertEquals("an app update must still deliver the new sizes", 2, installed)
        assertTrue(File(manager.keysDir, "bls_midnight_2p17").length() > 0)
    }

    /**
     * Writes the file set [ProvingKeyManager.hasWalletKeys] requires, with placeholder
     * bytes. Only presence and the version stamp are being asserted on, so content does
     * not matter — nothing in these tests loads a key.
     */
    private fun plantCompleteBaselineInstall() {
        manager.keysDir.mkdirs()
        val circuits = listOf("zswap/spend", "zswap/output", "zswap/sign", "dust/spend")
        for (circuit in circuits) {
            for (ext in listOf("prover", "verifier", "bzkir")) {
                File(manager.keysDir, "$circuit.$ext").apply {
                    parentFile?.mkdirs()
                    writeText("placeholder")
                }
            }
        }
        for (k in 5..15) {
            File(manager.keysDir, "bls_midnight_2p$k").writeText("placeholder")
        }
        File(manager.keysDir, "version.txt")
            .writeText(ProvingKeyManager.CURRENT_VERSION.toString())
    }
}
