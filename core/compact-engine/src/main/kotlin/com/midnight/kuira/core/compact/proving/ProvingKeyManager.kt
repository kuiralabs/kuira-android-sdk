package com.midnight.kuira.core.compact.proving

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manages proving key provisioning, caching, and version tracking for local ZK proving.
 *
 * The protocol-level **wallet** keys (~24MB total) are provisioned once into app
 * internal storage; the phone can then prove transactions offline. Provisioning
 * prefers the **APK bundle** ([installWalletKeysFromAssets], staged at build time
 * by the contract Gradle plugin) so a fresh device never depends on the network,
 * and falls back to an S3 [downloadWalletKeys] only when the app didn't bundle
 * them. See [ensureWalletKeysAvailable] for the priority order.
 *
 * Key locations match the SDK's `WasmProver.makeDefaultKeyMaterialProvider()`:
 * - `zswap/{ver}/spend.prover` (10.5 MB)
 * - `zswap/{ver}/output.prover` (5.5 MB)
 * - `zswap/{ver}/sign.prover` (2.7 MB)
 * - `dust/{ver}/spend.prover` (2.1 MB)
 * - `bls_midnight_2p13` (1.5 MB)
 * - Corresponding `.verifier` and `.bzkir` files
 */
class ProvingKeyManager(
    private val context: Context,
    apkStampOverride: (() -> String?)? = null,
) {

    /** Directory where proving keys are cached on device. */
    val keysDir: File = File(context.filesDir, KEYS_DIR_NAME)

    /**
     * An opaque token that changes whenever the installed APK changes — which is
     * the only way bundled assets (proving keys shipped in the APK) can change.
     * Used by [installCircuitKeysFromAssets] as a cheap gate to decide whether the
     * device's cached keys need re-verifying against the (possibly recompiled)
     * assets. `lastUpdateTime` is bumped by every (re)install and update, so a
     * recompiled-then-reinstalled contract is always caught.
     *
     * Overridable in tests (the default reads [android.content.pm.PackageInfo],
     * which isn't available in pure-JVM unit tests). Returns null if package info
     * can't be read — callers then fall back to always verifying content.
     */
    private val apkStamp: () -> String? = apkStampOverride ?: {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .lastUpdateTime
                .toString()
        }.getOrNull()
    }

    /** Whether all wallet-required proving keys are cached and current version. */
    fun hasWalletKeys(): Boolean {
        if (!keysDir.exists()) return false
        val versionFile = File(keysDir, "version.txt")
        if (!versionFile.exists()) return false
        val cachedVersion = versionFile.readText().trim()
        if (cachedVersion != CURRENT_VERSION.toString()) return false

        return WALLET_KEY_FILES.all { relativePath ->
            File(keysDir, relativePath).isPopulated()
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
     * One-call wallet-key provisioning. Tries the dev shortcut first
     * ([installFromLocalTmp] — adb-pushed files in `/data/local/tmp/`),
     * then falls back to the S3 download ([downloadWalletKeys]) when
     * the local-tmp pass didn't populate the BLS params + zswap/dust
     * keys. After this returns, [hasWalletKeys] is true unless the
     * download itself failed (in which case the underlying IOException
     * propagates).
     *
     * **Why this lives here:** every dApp that builds a [com.midnight.kuira.sdk.MidnightSdk]
     * + does local proving needs the same recipe. Bolting it onto
     * each dApp ([com.midnight.kuira.dapp.wallet.WalletPanelViewModel],
     * Kicks's `MatchManager`, future consumers) duplicates the
     * conditional and leaves a trap: the first dev who skips the
     * fallback ships a "works on my pre-staged machine, blows up on
     * fresh emulator with `BLS params file not found`" regression
     * (the exact 2026-05-19 emulator-B incident).
     *
     * Idempotent + cheap on the hot path: once wallet keys are in
     * [keysDir], subsequent calls hit the `hasWalletKeys()` check and
     * return immediately without I/O.
     *
     * @param onDownloadProgress Forwarded to [downloadWalletKeys].
     *   Only fires when the S3 fallback runs.
     * @param logger Status sink (start / complete lines for the
     *   fallback path). Defaults to silent.
     */
    suspend fun ensureWalletKeysAvailable(
        onDownloadProgress: (Float) -> Unit = {},
        logger: (String) -> Unit = {},
    ) {
        // 1. Dev override: adb-pushed keys in /data/local/tmp (highest priority so a
        //    dev can test freshly-staged keys without rebuilding the APK).
        installFromLocalTmp()
        // 2. Offline bundle: keys shipped in the APK (the production path — no
        //    network). Copying ~24MB is blocking I/O, so keep it off the caller's
        //    thread even though file ops don't suspend.
        if (!hasWalletKeys()) {
            withContext(Dispatchers.IO) { installWalletKeysFromAssets() }
        }
        // 3. Last resort: download from S3 (only when the app didn't bundle keys).
        if (!hasWalletKeys()) {
            logger("Wallet keys not bundled — downloading from S3 (~24MB, one-time)")
            downloadWalletKeys(onDownloadProgress)
            logger("Wallet keys download complete (hasWalletKeys=${hasWalletKeys()})")
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
    suspend fun downloadWalletKeys(onProgress: (Float) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting proving key download (version $CURRENT_VERSION)")

        // Create directory structure
        File(keysDir, "zswap").mkdirs()
        File(keysDir, "dust").mkdirs()

        val totalFiles = WALLET_DOWNLOADS.size
        var completed = 0

        for ((s3Path, localPath) in WALLET_DOWNLOADS) {
            val localFile = File(keysDir, localPath)

            if (localFile.isPopulated()) {
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

    /**
     * A key file counts as present only if it's non-empty. A 0-byte (or
     * absent) file means a broken/interrupted provisioning — the prover would
     * load an empty key and fail with a cryptic header-tag error. Treating it
     * as missing lets the provisioning paths (local-tmp copy / S3 download)
     * re-fetch it. Mirrors the same fix in `SeedVault.hasSeed`.
     *
     * Note: this catches empty files, not partially-written ones — that's why
     * both write paths ([copyAtomically], [downloadFile]) write to a temp file
     * and rename, so a `dst` is only ever absent or complete.
     */
    private fun File.isPopulated(): Boolean = exists() && length() > 0L

    /**
     * Copy [src] → [dst] via a temp file + rename, so an interrupted copy can't
     * leave a truncated/0-byte [dst] (the failure that stranded wallet keys on
     * device). Same dir → rename is atomic on the filesystem.
     */
    private fun copyAtomically(src: File, dst: File) {
        val tmp = File(dst.parentFile, "${dst.name}.tmp")
        src.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dst)) {
            // Cross-filesystem fallback (shouldn't happen — same filesDir).
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
        Log.d(TAG, "Installed ${dst.name} (${dst.length()} bytes)")
    }

    /** Streaming SHA-256 of this stream's remaining bytes, as lowercase hex. */
    private fun InputStream.sha256Hex(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * True iff [a] and [b] both exist and hold identical bytes. A length
     * pre-check cheaply rejects most mismatches before hashing. This is the
     * content-aware replacement for the old presence-only skip: a key that
     * exists but whose bytes drifted from the source (e.g. a recompiled circuit)
     * is NOT considered the same, so it gets refreshed.
     */
    private fun sameContent(a: File, b: File): Boolean {
        if (!a.exists() || !b.exists()) return false
        if (a.length() != b.length()) return false
        return a.inputStream().use { it.sha256Hex() } == b.inputStream().use { it.sha256Hex() }
    }

    /** True iff [dest] exists and matches the bytes of `assets/[assetPath]`. */
    private fun destMatchesAsset(assetPath: String, dest: File): Boolean {
        if (!dest.exists()) return false
        val destHash = dest.inputStream().use { it.sha256Hex() }
        val assetHash = context.assets.open(assetPath).use { it.sha256Hex() }
        return destHash == assetHash
    }

    /** Copy `assets/[assetPath]` → [dest] via temp file + rename (never partial). */
    private fun copyAssetAtomically(assetPath: String, dest: File) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        context.assets.open(assetPath).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
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
                File(dir, "$circuit.$ext").isPopulated()
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
                // Content-aware: refresh when absent OR the source bytes drifted
                // (a recompiled circuit), not just when absent. Atomic copy.
                if (source.isPopulated() && (overwrite || !sameContent(source, dest))) {
                    copyAtomically(source, dest)
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
                if (!dest.isPopulated()) {
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
        return BLS_PARAM_FILES.all { File(keysDir, it).isPopulated() }
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
                File(keysDir, "$circuit.$ext").isPopulated()
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
                // Content-aware: refresh when absent OR the source bytes drifted
                // (a recompiled circuit), not just when absent. Atomic copy.
                if (source.isPopulated() && (overwrite || !sameContent(source, dest))) {
                    copyAtomically(source, dest)
                }
            }
        }
    }

    /**
     * Install a dApp's own contract circuit keys from the APK's bundled assets
     * into the prover's [keysDir], where the local prover resolves them.
     *
     * Contract keys (e.g. bboard's `post`/`takeDown`) are `compactc` build
     * outputs of the dApp's `.compact` source — unique to that contract, hosted
     * nowhere — so the dApp ships them inside the APK and this copies them onto
     * device storage once. This is the contract-key counterpart to
     * [ensureWalletKeysAvailable], which provisions the protocol-level **wallet**
     * keys (zswap/dust/BLS) that are shared across all dApps and downloaded from
     * S3. Wallet keys download; contract keys bundle.
     *
     * Reads every `.prover`/`.verifier`/`.bzkir` under `assets/[assetDir]`.
     *
     * **Content-aware (not just presence-aware).** A recompiled circuit ships new
     * key bytes in the APK, but the device may already hold the OLD key from a
     * prior install. A presence/size check can't tell them apart, so it would keep
     * the stale key — and the local prover would then prove against an outdated
     * verifier key the chain rejects (node error 115 / InvalidProof). This caused
     * Kicks's `revealRegulation` to fail after the 0.16 recompile while
     * `commitRegulation` (unchanged) kept working.
     *
     * The fix verifies content. Bundled assets can only change when the APK is
     * (re)installed, which bumps [apkStamp]; so:
     *  - **stamp unchanged** (the hot path): trust the cached copies, skip hashing.
     *  - **stamp changed / absent / [overwrite]**: SHA-256 each key against the
     *    asset and re-copy ONLY the ones whose bytes drifted (here, just the
     *    recompiled circuit's files — not the whole set).
     *
     * Idempotent and self-healing: writes via temp-file + rename (never a partial
     * key), and stamps LAST so an interrupted refresh re-verifies next launch.
     */
    fun installCircuitKeysFromAssets(assetDir: String = "keys", overwrite: Boolean = false) {
        validatePathSegment(assetDir, "assetDir")
        keysDir.mkdirs()

        val stamp = apkStamp()
        val stampFile = File(keysDir, ".$assetDir.apk_stamp")
        val stampMatches = stamp != null &&
            stampFile.takeIf { it.exists() }?.readText()?.trim() == stamp
        // When the stamp can't be read (null), never trust presence alone — verify.
        val verifyContent = overwrite || !stampMatches

        val names = context.assets.list(assetDir).orEmpty()
            .filter { it.substringAfterLast('.', "") in CIRCUIT_KEY_EXTENSIONS }
        for (name in names) {
            val dest = File(keysDir, name)
            // Hot path: a complete prior install under the same APK — trust it.
            if (dest.isPopulated() && !verifyContent) continue
            // Verify path: re-copy only when the device key's bytes actually drifted.
            if (dest.isPopulated() && destMatchesAsset("$assetDir/$name", dest)) continue
            copyAssetAtomically("$assetDir/$name", dest)
            Log.d(TAG, "Installed $name from assets/$assetDir (${dest.length()} bytes)")
        }

        // Stamp LAST: if any copy above threw or the process died mid-refresh, the
        // stamp stays stale and the next launch re-verifies — self-healing.
        if (stamp != null) stampFile.runCatching { writeText(stamp) }
    }

    /**
     * Install the protocol-level **wallet** keys (zswap/dust/BLS) from the APK's
     * bundled assets — the offline-bundle counterpart to the S3 [downloadWalletKeys].
     *
     * The wallet keys are version-pinned and shared across all dApps. Historically
     * they were fetched from a dev S3 bucket at first launch, which blocked the
     * wallet on flaky networks. When the dApp's build bundles them under
     * `assets/[assetDir]` (the contract Gradle plugin stages them there), this
     * copies them onto device storage once and the device never touches the
     * network. [ensureWalletKeysAvailable] prefers this path and only falls back
     * to S3 when the bundle is absent.
     *
     * Same content-aware machinery as [installCircuitKeysFromAssets]: the
     * [apkStamp] gates a cheap hot path, and a stamp change re-verifies each key by
     * SHA-256 so a recompiled/version-bumped key set is picked up.
     *
     * Layout under `assets/[assetDir]` mirrors [WALLET_KEY_FILES] — `zswap/…`,
     * `dust/…`, and the flat `bls_midnight_2p*` params.
     *
     * @return true if the bundle was present and [hasWalletKeys] is now satisfied;
     *   false if the keys aren't bundled (or the bundle is incomplete) — the
     *   caller should then fall back to [downloadWalletKeys].
     */
    fun installWalletKeysFromAssets(assetDir: String = WALLET_ASSET_DIR, overwrite: Boolean = false): Boolean {
        validatePathSegment(assetDir, "assetDir")
        // Bundle absent → not an error, just "this app didn't ship wallet keys".
        val bundled = runCatching { context.assets.list(assetDir)?.isNotEmpty() == true }.getOrDefault(false)
        if (!bundled) return false

        // Version guard: a bundle built for a different ledger version must NOT be
        // installed — copying it then stamping version.txt with CURRENT_VERSION
        // would falsely mark stale-version keys as current. Fall back to download
        // the right version instead. Absent version.txt (older bundle) → best-effort.
        val bundledVersion = runCatching {
            context.assets.open("$assetDir/version.txt").bufferedReader().use { it.readText().trim() }
        }.getOrNull()
        if (bundledVersion != null && bundledVersion != CURRENT_VERSION.toString()) {
            Log.w(TAG, "Bundled wallet keys are v$bundledVersion but SDK needs v$CURRENT_VERSION — ignoring bundle")
            return false
        }

        keysDir.mkdirs()
        File(keysDir, "zswap").mkdirs()
        File(keysDir, "dust").mkdirs()

        val stamp = apkStamp()
        val stampFile = File(keysDir, ".$assetDir.apk_stamp")
        val stampMatches = stamp != null &&
            stampFile.takeIf { it.exists() }?.readText()?.trim() == stamp
        // When the stamp can't be read (null), never trust presence alone — verify.
        val verifyContent = overwrite || !stampMatches

        try {
            for (relative in WALLET_KEY_FILES) {
                val assetPath = "$assetDir/$relative"
                val dest = File(keysDir, relative)
                dest.parentFile?.mkdirs()
                // Hot path: complete prior install under the same APK — trust it.
                if (dest.isPopulated() && !verifyContent) continue
                // Verify path: re-copy only when the device key's bytes drifted.
                if (dest.isPopulated() && destMatchesAsset(assetPath, dest)) continue
                copyAssetAtomically(assetPath, dest) // throws if this file isn't bundled
            }
        } catch (e: IOException) {
            // Incomplete bundle (a required key wasn't staged). Leave what copied
            // (atomic, never partial) and let the S3 fallback fetch the rest.
            Log.i(TAG, "Wallet-key bundle incomplete (${e.message}) — caller will fall back to download")
            return false
        }

        File(keysDir, "version.txt").writeText(CURRENT_VERSION.toString())
        // Stamp LAST so an interrupted refresh re-verifies next launch (self-healing).
        if (stamp != null) stampFile.runCatching { writeText(stamp) }
        return hasWalletKeys()
    }

    /**
     * Dev-only convenience: install proving keys from a local-tmp staging area
     * pushed via `adb push` (the convention used by Kicks's build script, the
     * SDK e2e test, and BBoard's canary). Looks at two well-known directories
     * on the device:
     *
     *   - `<localTmp>/bboard_keys/` → BLS params (`bls_midnight_2p*`) + any
     *     circuit-specific files the caller's app needs (post, takeDown, etc.
     *     are picked up by [installCircuitKeysForProving] elsewhere).
     *   - `<localTmp>/wallet_keys/` → flat `zswap/{spend,output,sign}` and
     *     `dust/spend` (.prover, .verifier, .bzkir) — what [hasWalletKeys]
     *     requires for [com.midnight.kuira.core.crypto.proving.ProvingMode.LOCAL].
     *
     * Idempotent (skips files that already exist in [keysDir]). Writes
     * `version.txt` so [hasWalletKeys] recognises the install. Returns true
     * iff [hasWalletKeys] is satisfied afterwards — the caller can use this
     * to decide whether to fall back to [downloadWalletKeys].
     *
     * **NOT for production.** This expects files pre-staged on the device's
     * `/data/local/tmp` (no permission for app processes to write there — only
     * `adb push`). Production apps call [downloadWalletKeys] which fetches
     * from S3.
     *
     * @return whether the keys directory now has a complete wallet key set.
     */
    fun installFromLocalTmp(localTmp: File = File("/data/local/tmp")): Boolean {
        keysDir.mkdirs()
        File(keysDir, "zswap").mkdirs()
        File(keysDir, "dust").mkdirs()

        // BLS params live under bboard_keys/ by convention.
        val blsSrc = File(localTmp, "bboard_keys")
        if (blsSrc.exists()) {
            for (name in BLS_PARAM_FILES) {
                val src = File(blsSrc, name)
                val dst = File(keysDir, name)
                if (src.isPopulated() && !dst.isPopulated()) copyAtomically(src, dst)
            }
        }

        // zswap/* + dust/* under wallet_keys/.
        val walletSrc = File(localTmp, "wallet_keys")
        if (walletSrc.exists()) {
            for (relative in WALLET_KEY_FILES) {
                // Skip BLS — already handled above (they live in bboard_keys/).
                if (relative.startsWith("bls_")) continue
                val src = File(walletSrc, relative)
                val dst = File(keysDir, relative)
                if (src.isPopulated() && !dst.isPopulated()) {
                    dst.parentFile?.mkdirs()
                    copyAtomically(src, dst)
                }
            }
        }

        // hasWalletKeys() requires version.txt to assert the install is current.
        val versionFile = File(keysDir, "version.txt")
        if (!versionFile.exists()) versionFile.writeText(CURRENT_VERSION.toString())

        return hasWalletKeys()
    }

    companion object {
        private const val TAG = "ProvingKeyManager"
        private const val KEYS_DIR_NAME = "proving_keys"

        /**
         * APK assets subdir the wallet keys are bundled under (staged by the
         * contract Gradle plugin). Kept distinct from the contract keys' `keys/`
         * dir so [installWalletKeysFromAssets] and [installCircuitKeysFromAssets]
         * never tread on each other's files. Layout mirrors [WALLET_KEY_FILES].
         */
        const val WALLET_ASSET_DIR = "wallet-keys"

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

        /**
         * BLS parameter files needed for proving. k=13/14/15 cover the
         * wallet's zswap/dust circuits; k=5..12 cover compiled **contract**
         * circuits — the native prover loads the param sized to the circuit
         * (a small counter circuit needs exactly `bls_midnight_2p5`). These
         * are universal SRS params (not contract-specific), so the SDK
         * provisions every size once at wallet-key setup and any dApp circuit
         * finds the size it needs — no per-app BLS staging. Source of truth
         * for [WALLET_KEY_FILES] and [WALLET_DOWNLOADS] below.
         */
        private val BLS_PARAM_FILES = listOf(
            "bls_midnight_2p5",
            "bls_midnight_2p6",
            "bls_midnight_2p7",
            "bls_midnight_2p8",
            "bls_midnight_2p9",
            "bls_midnight_2p10",
            "bls_midnight_2p11",
            "bls_midnight_2p12",
            "bls_midnight_2p13",
            "bls_midnight_2p14",
            "bls_midnight_2p15",
        )

        /** File extensions required per circuit. */
        private val CIRCUIT_KEY_EXTENSIONS = listOf("prover", "verifier", "bzkir")

        /**
         * All files a complete install needs: the wallet's zswap + dust
         * keys, plus the full [BLS_PARAM_FILES] set. Requiring the BLS set
         * here means an existing install (provisioned before the smaller
         * sizes were added) reports incomplete and re-provisions — and since
         * [downloadWalletKeys] skips already-cached files, only the missing
         * (small) BLS params actually download.
         */
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
        ) + BLS_PARAM_FILES

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
            // BLS params — version-independent universal SRS, stored top-level
            // (not under a version dir). Every size in BLS_PARAM_FILES: k=13/14/15
            // for the wallet, k=5..12 for small contract circuits.
        ) + BLS_PARAM_FILES.map { it to it }
    }
}
