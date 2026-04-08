package com.midnight.kuira.core.compact.proving

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages proving key download, caching, and version tracking for local ZK proving.
 *
 * Proving keys are downloaded from Midnight's S3 once (~24MB total) and cached
 * in app internal storage. The phone can then prove transactions offline.
 *
 * Key locations match the SDK's `WasmProver.makeDefaultKeyMaterialProvider()`:
 * - `zswap/{ver}/spend.prover` (10.5 MB)
 * - `zswap/{ver}/output.prover` (5.5 MB)
 * - `zswap/{ver}/sign.prover` (2.7 MB)
 * - `dust/{ver}/spend.prover` (2.1 MB)
 * - `bls_midnight_2p13` (1.5 MB)
 * - Corresponding `.verifier` and `.bzkir` files
 */
class ProvingKeyManager(private val context: Context) {

    /** Directory where proving keys are cached on device. */
    val keysDir: File = File(context.filesDir, KEYS_DIR_NAME)

    /** Whether all wallet-required proving keys are cached and current version. */
    fun hasWalletKeys(): Boolean {
        if (!keysDir.exists()) return false
        val versionFile = File(keysDir, "version.txt")
        if (!versionFile.exists()) return false
        val cachedVersion = versionFile.readText().trim()
        if (cachedVersion != CURRENT_VERSION.toString()) return false

        return WALLET_KEY_FILES.all { relativePath ->
            File(keysDir, relativePath).exists()
        }
    }

    /** Total size of cached keys in bytes. */
    fun cachedSizeBytes(): Long {
        if (!keysDir.exists()) return 0
        return keysDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Delete all cached proving keys. */
    fun clearCache() {
        if (keysDir.exists()) {
            keysDir.deleteRecursively()
            Log.d(TAG, "Proving key cache cleared")
        }
    }

    /**
     * Download all wallet proving keys from S3.
     *
     * Downloads ~24MB total. Shows progress via callback.
     * Safe to call multiple times — skips already-downloaded files.
     *
     * @param onProgress Callback with progress 0.0 to 1.0
     * @throws IOException if download fails
     */
    suspend fun downloadWalletKeys(onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting proving key download (version $CURRENT_VERSION)")

        // Create directory structure
        File(keysDir, "zswap").mkdirs()
        File(keysDir, "dust").mkdirs()

        val totalFiles = WALLET_DOWNLOADS.size
        var completed = 0

        for ((s3Path, localPath) in WALLET_DOWNLOADS) {
            val localFile = File(keysDir, localPath)

            if (localFile.exists()) {
                Log.d(TAG, "  Skipping $localPath (already cached)")
                completed++
                onProgress(completed.toFloat() / totalFiles)
                continue
            }

            val url = "$S3_BASE_URL/$s3Path"
            Log.d(TAG, "  Downloading $s3Path → $localPath")

            downloadFile(url, localFile)

            completed++
            onProgress(completed.toFloat() / totalFiles)
            Log.d(TAG, "  Downloaded $localPath (${localFile.length()} bytes)")
        }

        // Write version file
        File(keysDir, "version.txt").writeText(CURRENT_VERSION.toString())

        val totalSize = cachedSizeBytes()
        Log.d(TAG, "Proving key download complete: ${totalSize / 1024 / 1024}MB cached")
    }

    private fun downloadFile(urlString: String, destination: File) {
        // Write to temp file first, rename on completion (atomic — prevents partial files)
        val tempFile = File(destination.parent, "${destination.name}.tmp")
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                throw IOException("HTTP $responseCode downloading $urlString")
            }

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            // Atomic rename — only succeeds if download was complete
            if (!tempFile.renameTo(destination)) {
                // Fallback: copy and delete (renameTo can fail across filesystems)
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete() // Clean up partial download
            throw e
        } finally {
            connection.disconnect()
        }
    }

    // ── Contract-Specific Keys ──

    /**
     * Directory for a specific contract's proving keys.
     * Keys are stored at: proving_keys/contracts/{contractName}/{circuit}.{prover,verifier,bzkir}
     */
    fun contractKeysDir(contractName: String): File {
        validatePathSegment(contractName, "contractName")
        return File(keysDir, "contracts/$contractName")
    }

    /**
     * Whether all required circuit keys are cached for a contract.
     *
     * @param contractName Short name (e.g., "bboard")
     * @param circuitNames List of circuit names (e.g., ["post", "takeDown"])
     */
    fun hasContractKeys(contractName: String, circuitNames: List<String>): Boolean {
        circuitNames.forEach { validatePathSegment(it, "circuitName") }
        val dir = contractKeysDir(contractName)
        if (!dir.exists()) return false
        return circuitNames.all { circuit ->
            CIRCUIT_KEY_EXTENSIONS.all { ext ->
                File(dir, "$circuit.$ext").exists()
            }
        }
    }

    /**
     * Copy contract proving keys from a source directory into the cache.
     *
     * Used during development/testing to install keys from the local filesystem
     * or from bundled assets.
     *
     * @param contractName Short name (e.g., "bboard")
     * @param circuitNames Circuit names to copy (e.g., ["post", "takeDown"])
     * @param keysSourceDir Directory containing {circuit}.prover, .verifier files
     * @param zkirSourceDir Directory containing {circuit}.bzkir files
     * @param overwrite If true, replaces existing keys (for version upgrades)
     */
    fun installContractKeys(
        contractName: String,
        circuitNames: List<String>,
        keysSourceDir: File,
        zkirSourceDir: File,
        overwrite: Boolean = false,
    ) {
        circuitNames.forEach { validatePathSegment(it, "circuitName") }
        val destDir = contractKeysDir(contractName)
        destDir.mkdirs()

        for (circuit in circuitNames) {
            for (ext in CIRCUIT_KEY_EXTENSIONS) {
                val sourceDir = if (ext == "bzkir") zkirSourceDir else keysSourceDir
                val source = File(sourceDir, "$circuit.$ext")
                val dest = File(destDir, "$circuit.$ext")
                if (source.exists() && (overwrite || !dest.exists())) {
                    source.copyTo(dest, overwrite = true)
                    Log.d(TAG, "Installed $contractName/$circuit.$ext (${dest.length()} bytes)")
                }
            }
        }
    }

    /**
     * Remove all cached keys for a contract.
     */
    fun removeContractKeys(contractName: String) {
        validatePathSegment(contractName, "contractName")
        val dir = contractKeysDir(contractName)
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.d(TAG, "Removed contract keys for $contractName")
        }
    }

    /**
     * Download contract proving keys from a remote URL.
     *
     * Downloads {circuit}.prover, .verifier, .bzkir for each circuit.
     * The base URL should point to a directory containing these files.
     *
     * @param contractName Short name for local storage (e.g., "bboard")
     * @param circuitNames Circuit names to download (e.g., ["post", "takeDown"])
     * @param baseUrl URL prefix where keys are hosted (e.g., "https://example.com/keys/bboard")
     * @param onProgress Callback with progress 0.0 to 1.0
     */
    suspend fun downloadContractKeys(
        contractName: String,
        circuitNames: List<String>,
        baseUrl: String,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        circuitNames.forEach { validatePathSegment(it, "circuitName") }
        val destDir = contractKeysDir(contractName)
        destDir.mkdirs()

        val totalFiles = circuitNames.size * CIRCUIT_KEY_EXTENSIONS.size
        var completed = 0

        for (circuit in circuitNames) {
            for (ext in CIRCUIT_KEY_EXTENSIONS) {
                val dest = File(destDir, "$circuit.$ext")
                if (!dest.exists()) {
                    val url = "$baseUrl/$circuit.$ext"
                    Log.d(TAG, "Downloading $contractName/$circuit.$ext")
                    downloadFile(url, dest)
                }
                completed++
                onProgress(completed.toFloat() / totalFiles)
            }
        }

        Log.d(TAG, "Contract keys for $contractName downloaded")
    }

    /**
     * Whether BLS parameters are available (needed for all proving).
     *
     * BLS params are shared between wallet and contract circuits.
     * Downloaded as part of [downloadWalletKeys].
     */
    fun hasBLSParams(): Boolean {
        return BLS_PARAM_FILES.all { File(keysDir, it).exists() }
    }

    /**
     * Whether the prover can find circuit keys at the root keys directory.
     *
     * The Rust prover resolves keys by: `keysDir/{circuitName}.{prover,verifier,bzkir}`.
     * Contract keys must be in the root keysDir (not a subdirectory) for the prover to find them.
     */
    fun hasProvableCircuitKeys(circuitNames: List<String>): Boolean {
        circuitNames.forEach { validatePathSegment(it, "circuitName") }
        return circuitNames.all { circuit ->
            CIRCUIT_KEY_EXTENSIONS.all { ext ->
                File(keysDir, "$circuit.$ext").exists()
            }
        } && hasBLSParams()
    }

    /**
     * Install circuit keys to the root keys directory for the prover.
     *
     * The Rust prover resolves by flat path: `keysDir/{name}.prover`.
     * This copies circuit keys from a source directory directly to keysDir root.
     *
     * @param circuitNames Circuit names (e.g., ["post", "takeDown"])
     * @param keysSourceDir Directory containing {circuit}.prover, .verifier files
     * @param zkirSourceDir Directory containing {circuit}.bzkir files
     * @param overwrite If true, replaces existing keys
     */
    fun installCircuitKeysForProving(
        circuitNames: List<String>,
        keysSourceDir: File,
        zkirSourceDir: File,
        overwrite: Boolean = false,
    ) {
        circuitNames.forEach { validatePathSegment(it, "circuitName") }
        keysDir.mkdirs()

        for (circuit in circuitNames) {
            for (ext in CIRCUIT_KEY_EXTENSIONS) {
                val sourceDir = if (ext == "bzkir") zkirSourceDir else keysSourceDir
                val source = File(sourceDir, "$circuit.$ext")
                val dest = File(keysDir, "$circuit.$ext")
                if (source.exists() && (overwrite || !dest.exists())) {
                    source.copyTo(dest, overwrite = true)
                    Log.d(TAG, "Installed $circuit.$ext to keysDir (${dest.length()} bytes)")
                }
            }
        }
    }

    companion object {
        private const val TAG = "ProvingKeyManager"
        private const val KEYS_DIR_NAME = "proving_keys"

        /** Current proving key version (matches ledger version). */
        const val CURRENT_VERSION = 9

        /** S3 bucket URL (same as SDK's WasmProver). */
        private const val S3_BASE_URL =
            "https://midnight-s3-fileshare-dev-eu-west-1.s3.eu-west-1.amazonaws.com"

        private val SAFE_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")

        private fun validatePathSegment(value: String, name: String) {
            require(value.isNotEmpty() && SAFE_NAME_REGEX.matches(value)) {
                "$name must be alphanumeric/underscore/hyphen, got: ${value.take(20)}"
            }
        }

        /** BLS parameter files needed for any proving (wallet or contract). */
        private val BLS_PARAM_FILES = listOf(
            "bls_midnight_2p13",
            "bls_midnight_2p14",
            "bls_midnight_2p15",
        )

        /** File extensions required per circuit. */
        private val CIRCUIT_KEY_EXTENSIONS = listOf("prover", "verifier", "bzkir")

        /** All files needed for wallet transactions (zswap + dust). */
        private val WALLET_KEY_FILES = listOf(
            "zswap/spend.prover",
            "zswap/spend.verifier",
            "zswap/spend.bzkir",
            "zswap/output.prover",
            "zswap/output.verifier",
            "zswap/output.bzkir",
            "zswap/sign.prover",
            "zswap/sign.verifier",
            "zswap/sign.bzkir",
            "dust/spend.prover",
            "dust/spend.verifier",
            "dust/spend.bzkir",
            "bls_midnight_2p13",
            "bls_midnight_2p14",
            "bls_midnight_2p15",
        )

        /** S3 path → local path mapping for downloads. */
        private val WALLET_DOWNLOADS = listOf(
            // Zswap proving keys
            "zswap/$CURRENT_VERSION/spend.prover" to "zswap/spend.prover",
            "zswap/$CURRENT_VERSION/spend.verifier" to "zswap/spend.verifier",
            "zswap/$CURRENT_VERSION/spend.bzkir" to "zswap/spend.bzkir",
            "zswap/$CURRENT_VERSION/output.prover" to "zswap/output.prover",
            "zswap/$CURRENT_VERSION/output.verifier" to "zswap/output.verifier",
            "zswap/$CURRENT_VERSION/output.bzkir" to "zswap/output.bzkir",
            "zswap/$CURRENT_VERSION/sign.prover" to "zswap/sign.prover",
            "zswap/$CURRENT_VERSION/sign.verifier" to "zswap/sign.verifier",
            "zswap/$CURRENT_VERSION/sign.bzkir" to "zswap/sign.bzkir",
            // Dust proving keys
            "dust/$CURRENT_VERSION/spend.prover" to "dust/spend.prover",
            "dust/$CURRENT_VERSION/spend.verifier" to "dust/spend.verifier",
            "dust/$CURRENT_VERSION/spend.bzkir" to "dust/spend.bzkir",
            // BLS parameters (k=13 for dust, k=14 for zswap output, k=15 for zswap spend)
            "bls_midnight_2p13" to "bls_midnight_2p13",
            "bls_midnight_2p14" to "bls_midnight_2p14",
            "bls_midnight_2p15" to "bls_midnight_2p15",
        )
    }
}
