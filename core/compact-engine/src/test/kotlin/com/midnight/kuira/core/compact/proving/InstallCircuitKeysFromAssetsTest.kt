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
import java.io.File
import java.nio.file.Files

/**
 * Coverage for [ProvingKeyManager.installCircuitKeysFromAssets] — the
 * contract-key counterpart to the wallet-key path in
 * [EnsureWalletKeysAvailableTest]. Both example dApps (BBoard, Kicks) install
 * their `compactc`-built circuit keys through this one method, so its
 * filter/idempotency/self-heal behaviour is load-bearing.
 */
class InstallCircuitKeysFromAssetsTest {

    private val context: Context = mockk()
    private val assets: AssetManager = mockk()
    private lateinit var tempDir: File
    private lateinit var keysDir: File

    private val postProver = ByteArray(2048) { (it % 7).toByte() }
    private val postVerifier = ByteArray(64) { 3 }
    private val postBzkir = ByteArray(16) { 9 }

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("pkm-assets").toFile()
        every { context.filesDir } returns tempDir
        every { context.assets } returns assets
        keysDir = File(tempDir, "proving_keys")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun stagePostCircuit(vararg listed: String) {
        every { assets.list("keys") } returns arrayOf(*listed)
        every { assets.open("keys/post.prover") } answers { ByteArrayInputStream(postProver) }
        every { assets.open("keys/post.verifier") } answers { ByteArrayInputStream(postVerifier) }
        every { assets.open("keys/post.bzkir") } answers { ByteArrayInputStream(postBzkir) }
    }

    @Test
    fun `copies prover verifier bzkir into root keysDir with exact bytes`() {
        stagePostCircuit("post.prover", "post.verifier", "post.bzkir")

        ProvingKeyManager(context).installCircuitKeysFromAssets()

        assertArrayEquals(postProver, File(keysDir, "post.prover").readBytes())
        assertArrayEquals(postVerifier, File(keysDir, "post.verifier").readBytes())
        assertArrayEquals(postBzkir, File(keysDir, "post.bzkir").readBytes())
    }

    @Test
    fun `ignores asset files that are not circuit keys`() {
        every { assets.list("keys") } returns arrayOf("post.prover", "README.md", "index.js")
        every { assets.open("keys/post.prover") } answers { ByteArrayInputStream(postProver) }

        ProvingKeyManager(context).installCircuitKeysFromAssets()

        assertTrue(File(keysDir, "post.prover").exists())
        assertFalse(File(keysDir, "README.md").exists())
        assertFalse(File(keysDir, "index.js").exists())
        assertFalse("no temp file left behind", File(keysDir, "post.prover.tmp").exists())
    }

    @Test
    fun `leaves an already-present key with matching content untouched`() {
        keysDir.mkdirs()
        // Same bytes as the asset → content matches → no rewrite needed.
        File(keysDir, "post.prover").writeBytes(postProver)
        stagePostCircuit("post.prover")

        ProvingKeyManager(context).installCircuitKeysFromAssets()

        assertArrayEquals(postProver, File(keysDir, "post.prover").readBytes())
    }

    /**
     * THE error-115 regression: a recompiled circuit ships new key bytes in the
     * APK, but the device still holds the OLD key from a prior install. A
     * presence/size check keeps the stale key; the content check must replace it.
     * (Here the stamp is unreadable in unit tests → null → always verify content.)
     */
    @Test
    fun `refreshes a present key whose content drifted from assets`() {
        keysDir.mkdirs()
        val staleKey = ByteArray(2048) { 0xAB.toByte() } // different bytes than postProver
        File(keysDir, "post.prover").writeBytes(staleKey)
        stagePostCircuit("post.prover")

        ProvingKeyManager(context).installCircuitKeysFromAssets()

        assertArrayEquals(
            "stale device key must be refreshed to the current asset bytes",
            postProver, File(keysDir, "post.prover").readBytes(),
        )
    }

    @Test
    fun `re-copies a zero-byte key so a truncated prior copy self-heals`() {
        keysDir.mkdirs()
        File(keysDir, "post.prover").writeBytes(ByteArray(0))
        stagePostCircuit("post.prover")

        ProvingKeyManager(context).installCircuitKeysFromAssets()

        assertArrayEquals(postProver, File(keysDir, "post.prover").readBytes())
    }

    // ── APK-stamp gate ──
    // The stamp (default: PackageInfo.lastUpdateTime) is injected here so the gate
    // is testable in pure-JVM. It changes only when the APK is (re)installed, which
    // is the only way bundled assets can change.

    @Test
    fun `writes the apk stamp after a successful install`() {
        stagePostCircuit("post.prover", "post.verifier", "post.bzkir")

        ProvingKeyManager(context, apkStampOverride = { "build-42" }).installCircuitKeysFromAssets()

        assertEquals("build-42", File(keysDir, ".keys.apk_stamp").readText().trim())
    }

    @Test
    fun `a matching stamp trusts the cache and skips re-verify`() {
        // Device holds a key; the stamp says "this APK already installed". The gate
        // must NOT re-hash/replace — assets can't have changed under the same APK.
        keysDir.mkdirs()
        val cached = ByteArray(2048) { 5 }
        File(keysDir, "post.prover").writeBytes(cached)
        File(keysDir, ".keys.apk_stamp").writeText("build-42")
        stagePostCircuit("post.prover")

        ProvingKeyManager(context, apkStampOverride = { "build-42" }).installCircuitKeysFromAssets()

        assertArrayEquals("matching stamp → cached key left as-is",
            cached, File(keysDir, "post.prover").readBytes())
    }

    @Test
    fun `a changed stamp re-verifies and refreshes drifted keys`() {
        // The recompile+reinstall path: APK changed (new stamp) and the device key
        // drifted → it must be refreshed to the current asset bytes.
        keysDir.mkdirs()
        File(keysDir, "post.prover").writeBytes(ByteArray(2048) { 5 })
        File(keysDir, ".keys.apk_stamp").writeText("build-41")
        stagePostCircuit("post.prover")

        ProvingKeyManager(context, apkStampOverride = { "build-42" }).installCircuitKeysFromAssets()

        assertArrayEquals(postProver, File(keysDir, "post.prover").readBytes())
        assertEquals("build-42", File(keysDir, ".keys.apk_stamp").readText().trim())
    }

    @Test
    fun `a changed stamp leaves an unchanged key in place`() {
        // Stamp changed but this particular key's bytes match → no needless rewrite.
        keysDir.mkdirs()
        File(keysDir, "post.prover").writeBytes(postProver)
        File(keysDir, ".keys.apk_stamp").writeText("build-41")
        stagePostCircuit("post.prover")

        ProvingKeyManager(context, apkStampOverride = { "build-42" }).installCircuitKeysFromAssets()

        assertArrayEquals(postProver, File(keysDir, "post.prover").readBytes())
    }
}
