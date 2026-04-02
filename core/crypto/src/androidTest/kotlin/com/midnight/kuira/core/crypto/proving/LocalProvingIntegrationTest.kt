package com.midnight.kuira.core.crypto.proving

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for local ZK proving on Android.
 *
 * Downloads real proving keys from S3 on first test, then verifies
 * the local prover works correctly.
 */
@RunWith(AndroidJUnit4::class)
class LocalProvingIntegrationTest {

    private lateinit var keyManager: ProvingKeyManager

    companion object {
        private const val TAG = "LocalProvingTest"
        private var keysDownloaded = false
    }

    @Before
    fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keyManager = ProvingKeyManager(context)

        // Download keys once across all tests
        if (!keysDownloaded && !keyManager.hasWalletKeys()) {
            Log.d(TAG, "Downloading proving keys from S3...")
            keyManager.downloadWalletKeys { progress ->
                Log.d(TAG, "Download: ${(progress * 100).toInt()}%")
            }
        }
        keysDownloaded = true
    }

    @Test
    fun given_downloaded_keys_when_hasWalletKeys_then_returns_true() {
        assertTrue("Keys should be cached", keyManager.hasWalletKeys())
    }

    @Test
    fun given_downloaded_keys_when_checkSize_then_exceeds_20MB() {
        val size = keyManager.cachedSizeBytes()
        assertTrue("Cached size should be > 20MB, was ${size / 1024 / 1024}MB", size > 20_000_000)
    }

    @Test
    fun given_cached_keys_when_proveTransaction_with_invalid_input_then_returns_null() {
        val result = LocalProver.proveTransaction(
            unprovenTxHex = "deadbeef",
            keysDir = keyManager.keysDir.absolutePath,
        )
        assertNull("Invalid input should return null, not crash", result)
    }

    @Test
    fun given_cached_keys_when_checking_critical_prover_files_then_all_exist() {
        val criticalFiles = listOf(
            "zswap/spend.prover",
            "zswap/output.prover",
            "zswap/sign.prover",
            "dust/spend.prover",
            "bls_midnight_2p13",
        )
        for (file in criticalFiles) {
            val f = File(keyManager.keysDir, file)
            assertTrue("$file should exist", f.exists())
            assertTrue("$file should not be empty", f.length() > 0)
        }
    }

    @Test
    fun given_cached_keys_when_checking_verifier_and_ir_files_then_all_exist() {
        val supportFiles = listOf(
            "zswap/spend.verifier", "zswap/spend.bzkir",
            "zswap/output.verifier", "zswap/output.bzkir",
            "zswap/sign.verifier", "zswap/sign.bzkir",
            "dust/spend.verifier", "dust/spend.bzkir",
        )
        for (file in supportFiles) {
            val f = File(keyManager.keysDir, file)
            assertTrue("$file should exist", f.exists())
            assertTrue("$file should not be empty", f.length() > 0)
        }
    }
}
