package com.midnight.kuira.core.compact.proving

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
    fun `leaves an already-present non-empty key untouched`() {
        keysDir.mkdirs()
        val existing = ByteArray(2048) { 1 }
        File(keysDir, "post.prover").writeBytes(existing)
        stagePostCircuit("post.prover")

        ProvingKeyManager(context).installCircuitKeysFromAssets()

        assertArrayEquals(existing, File(keysDir, "post.prover").readBytes())
    }

    @Test
    fun `re-copies a zero-byte key so a truncated prior copy self-heals`() {
        keysDir.mkdirs()
        File(keysDir, "post.prover").writeBytes(ByteArray(0))
        stagePostCircuit("post.prover")

        ProvingKeyManager(context).installCircuitKeysFromAssets()

        assertArrayEquals(postProver, File(keysDir, "post.prover").readBytes())
    }
}
