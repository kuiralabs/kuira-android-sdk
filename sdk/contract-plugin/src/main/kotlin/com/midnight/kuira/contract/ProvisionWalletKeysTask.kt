package com.midnight.kuira.contract

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the protocol-level wallet proving keys (zswap/dust/BLS) ONCE at
 * build time and stages them into `assets/wallet-keys` so the APK ships them —
 * the offline-bundle half of. Pairs with the SDK's
 * `ProvingKeyManager.installWalletKeysFromAssets`, which installs them on device
 * with no network.
 *
 * **Why build-time, not runtime:** the runtime S3 download is the unreliable
 * step that strands a fresh device behind "BLS params file not found". Moving it
 * to the build machine makes it retryable, cached (paid once per machine per
 * version), and — once bundled — the device never touches the network.
 *
 * Configuration-cache compatible: a typed task whose action reads only declared
 * properties, never `project`.
 */
abstract class ProvisionWalletKeysTask : DefaultTask() {

    /** Proving-key version (matches the SDK's ledger-pinned CURRENT_VERSION). */
    @get:Input
    abstract val version: Property<Int>

    /** Base URL the keys are downloaded from. */
    @get:Input
    abstract val baseUrl: Property<String>

    /**
     * Machine-shared download cache root (under the Gradle user home). Not an
     * output — it is reused across projects/builds. The action resolves a
     * per-version subdir so different ledger versions never collide.
     */
    @get:Internal
    abstract val cacheRoot: DirectoryProperty

    /** `src/main/assets/wallet-keys` — the staged bundle the APK packages. */
    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun provision() {
        val v = version.get()
        val base = baseUrl.get().trimEnd('/')
        val cache = File(cacheRoot.get().asFile, "v$v").apply { mkdirs() }
        val out = assetsDir.get().asFile

        val files = walletKeyFiles(v)

        // 1. Ensure each key is in the machine cache (download with retry only
        //  when missing/empty — the keys are immutable per version).
        var downloaded = 0
        for ((remotePath, localRel) in files) {
            val cached = File(cache, localRel)
            if (cached.isFileNonEmpty()) continue
            cached.parentFile?.mkdirs()
            downloadWithRetry("$base/$remotePath", cached)
            downloaded++
        }

        // 2. Stage cache → assets (content-aware: copy only when bytes differ, so
        //  an unchanged set leaves the assets untouched between builds).
        for ((_, localRel) in files) {
            val src = File(cache, localRel)
            val dst = File(out, localRel)
            dst.parentFile?.mkdirs()
            if (!dst.exists() || dst.length() != src.length() || sha256(dst) != sha256(src)) {
                copyAtomically(src, dst)
            }
        }

        // 3. version.txt — what ProvingKeyManager.hasWalletKeys() checks.
        File(out, "version.txt").writeText(v.toString())

        logger.lifecycle(
            "Kuira: wallet proving keys v$v ready (${files.size} files, " +
                "$downloaded downloaded) → ${out.absolutePath}",
        )
    }

    private fun File.isFileNonEmpty(): Boolean = isFile && length() > 0L

    private fun copyAtomically(src: File, dst: File) {
        val tmp = File(dst.parentFile, "${dst.name}.tmp")
        src.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Download [url] → [dest] with retry/backoff, writing to a temp file and
     * renaming on success so a failed attempt never leaves a partial key.
     */
    private fun downloadWithRetry(url: String, dest: File) {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                downloadOnce(url, dest)
                return
            } catch (e: IOException) {
                lastError = e
                val backoffMs = INITIAL_BACKOFF_MS shl attempt
                logger.warn(
                    "Kuira: wallet-key download failed (${e.message}), " +
                        "retry ${attempt + 1}/$MAX_ATTEMPTS in ${backoffMs}ms — $url",
                )
                Thread.sleep(backoffMs)
            }
        }
        throw IOException("Failed to download wallet key after $MAX_ATTEMPTS attempts: $url", lastError)
    }

    private fun downloadOnce(url: String, dest: File) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != HTTP_OK) throw IOException("HTTP $code for $url")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, bufferSize = COPY_BUFFER_BYTES) }
            }
            if (tmp.length() == 0L) throw IOException("Empty body for $url")
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TASK_NAME = "provisionWalletKeys"

        private const val MAX_ATTEMPTS = 4
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val HTTP_OK = 200
        private const val COPY_BUFFER_BYTES = 64 * 1024

        // BLS params are version-independent (universal SRS); zswap/dust keys are
        // versioned. Mirrors the SDK's ProvingKeyManager file set.
        private val BLS_PARAMS = (5..15).map { "bls_midnight_2p$it" }
        private val ZSWAP_DUST = listOf(
            "zswap/spend.prover", "zswap/spend.verifier", "zswap/spend.bzkir",
            "zswap/output.prover", "zswap/output.verifier", "zswap/output.bzkir",
            "zswap/sign.prover", "zswap/sign.verifier", "zswap/sign.bzkir",
            "dust/spend.prover", "dust/spend.verifier", "dust/spend.bzkir",
        )

        /** (remote path, local relative path) for every wallet key at [version]. */
        internal fun walletKeyFiles(version: Int): List<Pair<String, String>> {
            // zswap/dust live under a version dir remotely, flat locally.
            val versioned = ZSWAP_DUST.map { local ->
                val (group, file) = local.split("/", limit = 2)
                "$group/$version/$file" to local
            }
            // BLS params are top-level and version-independent.
            val bls = BLS_PARAMS.map { it to it }
            return versioned + bls
        }
    }
}
