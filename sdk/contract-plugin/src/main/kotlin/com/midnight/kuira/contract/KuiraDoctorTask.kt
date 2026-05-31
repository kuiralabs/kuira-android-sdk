package com.midnight.kuira.contract

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * `kuiraDoctor` — preflight check task that catches consumer-side
 * misconfigurations at build time instead of as runtime crashes on a
 * user's device.
 *
 * Runs four checks against the consumer project:
 *
 * 1. **minSdk** — Kuira SDK requires API 30+ (Block Store +
 *    CredentialManager). Catches a lowered `minSdk` before the
 *    Manifest merger surfaces the same thing as a cryptic error.
 * 2. **Debug-cleartext manifest** — flags consumers targeting a
 *    localnet without an `app/src/debug/AndroidManifest.xml` that
 *    permits cleartext to `10.0.2.2` / `localhost`. Catches the
 *    "indexer connection failed" generic IOException.
 * 3. **`assetlinks.json` reachability** — when [rpId] is set, the
 *    task fetches `https://<rpId>/.well-known/assetlinks.json`,
 *    confirms HTTP 200 + JSON-parseable content, and verifies the
 *    consumer's `applicationId` is listed as a target. Catches the
 *    `RP_ID_MISMATCH` / `PRF authentication failed` class.
 * 4. **Compact runtime pin** — reuses the contract-plugin's existing
 *    runtime-version cross-check (already enforced at
 *    `validateKuiraContractSource`); reported here for a unified view.
 *
 * Each check returns one of PASS / WARN / FAIL / SKIP. The task prints
 * a unified report. If any check is FAIL and [requireDoctorPass] is
 * `true`, the task fails the build; otherwise it logs the report and
 * returns success regardless of severity.
 *
 * Not wired to `preBuild` by default — invoke explicitly with
 * `./gradlew :app:kuiraDoctor`. Run before each release; consider
 * wiring to release-assemble lifecycle in your own build for a
 * gated-release workflow.
 */
abstract class KuiraDoctorTask : DefaultTask() {

    /** Consumer's passkey rpId. Skips reachability check when unset. */
    @get:Input
    @get:Optional
    abstract val rpId: Property<String>

    /**
     * Consumer's `applicationId` (auto-discovered from build.gradle.kts
     * via simple regex; can be overridden by the consumer via
     * `kuiraDoctor.applicationId.set(...)` if the auto-discovery
     * doesn't find it). Optional — if unset and not discoverable, the
     * assetlinks check downgrades to "URL reachable but applicationId
     * match not verifiable."
     */
    @get:Input
    @get:Optional
    abstract val applicationId: Property<String>

    /** Auto-discovered `minSdk` from build.gradle.kts. */
    @get:Input
    @get:Optional
    abstract val minSdk: Property<Int>

    /**
     * Compiled contract source directory (for the runtime-pin check).
     * `@Internal` because the doctor task already opts out of
     * up-to-date checks (`outputs.upToDateWhen { false }`) — input
     * tracking on this directory would only fight that. The directory
     * is read at task-execution time when present.
     */
    @get:Internal
    abstract val contractSource: DirectoryProperty

    /** Explicit override of expected compact-runtime version. */
    @get:Input
    @get:Optional
    abstract val expectedCompactRuntime: Property<String>

    /** Fail the build on any FAIL-severity result when true. */
    @get:Input
    abstract val requireDoctorPass: Property<Boolean>

    init {
        group = "verification"
        description = "Preflight checks that catch consumer misconfigurations at build time " +
            "instead of as runtime crashes on a user's device."
        // Diagnostic task — always run, never cached. Up-to-date would
        // hide a now-misconfigured rpId / assetlinks endpoint.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun runDoctor() {
        val results = listOf(
            checkMinSdk(),
            checkCleartextManifest(),
            checkAssetlinks(),
            checkCompactRuntimePin(),
        )
        printReport(results)

        val failed = results.filter { it.severity == Severity.FAIL }
        if (failed.isNotEmpty() && requireDoctorPass.get()) {
            throw GradleException(
                "kuiraDoctor failed: ${failed.size} check(s) FAILed (see report above). " +
                    "Set kuiraContract.requireDoctorPass.set(false) to downgrade to warning.",
            )
        }
    }

    // ── Checks ───────────────────────────────────────────────────────

    private fun checkMinSdk(): CheckResult {
        val value = minSdk.orNull
            ?: return CheckResult(
                name = "minSdk",
                severity = Severity.SKIP,
                message = "Could not auto-discover minSdk from app/build.gradle.kts. " +
                    "Pass it explicitly with kuiraDoctor.minSdk.set(...) to enable this check.",
            )
        return if (value >= MIN_SDK_REQUIRED) {
            CheckResult(
                name = "minSdk",
                severity = Severity.PASS,
                message = "minSdk = $value (Kuira SDK requires ≥ $MIN_SDK_REQUIRED)",
            )
        } else {
            CheckResult(
                name = "minSdk",
                severity = Severity.FAIL,
                message = "minSdk = $value is below Kuira SDK's required floor of " +
                    "$MIN_SDK_REQUIRED (Block Store + CredentialManager). " +
                    "Bump android.defaultConfig.minSdk to $MIN_SDK_REQUIRED or higher.",
            )
        }
    }

    private fun checkCleartextManifest(): CheckResult {
        val debugManifest = project.layout.projectDirectory
            .file("src/debug/AndroidManifest.xml").asFile
        if (!debugManifest.isFile) {
            return CheckResult(
                name = "debug-cleartext",
                severity = Severity.WARN,
                message = "No app/src/debug/AndroidManifest.xml found. " +
                    "If you're targeting a localnet (Midnight node + indexer on " +
                    "127.0.0.1 / 10.0.2.2), Android 9+ blocks cleartext to those " +
                    "hosts by default. Add a debug-only manifest with " +
                    "usesCleartextTraffic=\"true\" or a networkSecurityConfig.",
            )
        }
        val content = runCatching { debugManifest.readText() }.getOrNull()
            ?: return CheckResult(
                name = "debug-cleartext",
                severity = Severity.WARN,
                message = "Could not read $debugManifest to verify cleartext config.",
            )
        val hasCleartext =
            content.contains("usesCleartextTraffic=\"true\"") ||
            content.contains("networkSecurityConfig")
        return if (hasCleartext) {
            CheckResult(
                name = "debug-cleartext",
                severity = Severity.PASS,
                message = "Debug manifest permits cleartext (localnet builds work).",
            )
        } else {
            CheckResult(
                name = "debug-cleartext",
                severity = Severity.WARN,
                message = "Debug manifest exists but doesn't declare cleartext / " +
                    "networkSecurityConfig. Localnet indexer connections to " +
                    "10.0.2.2 / localhost will fail with an opaque IOException.",
            )
        }
    }

    private fun checkAssetlinks(): CheckResult {
        val host = rpId.orNull
            ?: return CheckResult(
                name = "assetlinks-reachability",
                severity = Severity.SKIP,
                message = "kuiraContract.rpId is unset — assetlinks reachability " +
                    "check skipped. Set it to your hosted-domain hostname (e.g. " +
                    "\"kuiralabs.github.io\") to enable this check.",
            )
        val url = "https://$host/.well-known/assetlinks.json"
        val (status, body) = httpGetWithTimeout(url)
            ?: return CheckResult(
                name = "assetlinks-reachability",
                severity = Severity.WARN,
                message = "Could not reach $url (network unreachable or timeout). " +
                    "If you're offline or behind a firewall this is expected; " +
                    "re-run kuiraDoctor when connected.",
            )

        if (status == HTTP_NOT_FOUND) {
            return CheckResult(
                name = "assetlinks-reachability",
                severity = Severity.FAIL,
                message = buildString {
                    append("$url returned HTTP 404. ")
                    append("Common causes:\n")
                    append("  1. The file is not yet hosted at this path — see the ")
                    append("'Bind your app to a passkey domain' recipe.\n")
                    append("  2. The file IS in the repo but GitHub Pages stripped it via Jekyll: ")
                    append("commit an empty .nojekyll at the repo root + redeploy.\n")
                    append("  3. The hostname in kuiraContract.rpId is wrong — verify ")
                    append("https://$host/ loads at all.")
                },
            )
        }
        if (status !in HTTP_2XX) {
            return CheckResult(
                name = "assetlinks-reachability",
                severity = Severity.FAIL,
                message = "$url returned HTTP $status. Expected 200.",
            )
        }
        if (body == null || !ASSETLINKS_ROOT_JSON.containsMatchIn(body)) {
            return CheckResult(
                name = "assetlinks-reachability",
                severity = Severity.FAIL,
                message = "$url returned HTTP $status but the body does not " +
                    "parse as the expected assetlinks.json shape (a JSON array " +
                    "starting with [). Inspect the body manually.",
            )
        }

        // applicationId check
        val appId = applicationId.orNull
            ?: return CheckResult(
                name = "assetlinks-reachability",
                severity = Severity.WARN,
                message = "$url is reachable + JSON-parseable. Skipped the " +
                    "applicationId-in-targets check because applicationId could " +
                    "not be auto-discovered. Pass it via kuiraDoctor.applicationId.set(...) " +
                    "to verify your app is listed as a target.",
            )
        val packageRefRegex = """"package_name"\s*:\s*"${Regex.escape(appId)}"""".toRegex()
        return if (packageRefRegex.containsMatchIn(body)) {
            CheckResult(
                name = "assetlinks-reachability",
                severity = Severity.PASS,
                message = "$url returns 200 + lists '$appId' as a target.",
            )
        } else {
            CheckResult(
                name = "assetlinks-reachability",
                severity = Severity.FAIL,
                message = "$url is reachable but does NOT list '$appId' as a " +
                    "target. Forge will fail with RP_ID_MISMATCH / PRF " +
                    "authentication failed on a real device. Add an entry to " +
                    "the assetlinks.json array with " +
                    "target.package_name = \"$appId\" and your app's signing " +
                    "fingerprint, then re-run.",
            )
        }
    }

    private fun checkCompactRuntimePin(): CheckResult {
        val srcDir = contractSource.orNull?.asFile
            ?: return CheckResult(
                name = "compact-runtime-pin",
                severity = Severity.SKIP,
                message = "kuiraContract.source is unset — no contract artifacts " +
                    "to inspect for runtime-version drift.",
            )
        if (!srcDir.exists()) {
            return CheckResult(
                name = "compact-runtime-pin",
                severity = Severity.SKIP,
                message = "Contract source dir $srcDir does not exist yet. " +
                    "Compile your contract first; the validateKuiraContractSource " +
                    "task will enforce runtime-version match at sync time.",
            )
        }
        val emitted = readContractInfoRuntime(srcDir)
            ?: return CheckResult(
                name = "compact-runtime-pin",
                severity = Severity.SKIP,
                message = "No compiler/contract-info.json under $srcDir — older " +
                    "compactc output may not emit this file.",
            )
        val expected = expectedCompactRuntime.orNull ?: discoverPackageRuntime(srcDir)
            ?: return CheckResult(
                name = "compact-runtime-pin",
                severity = Severity.SKIP,
                message = "No package.json declaring @midnight-ntwrk/compact-runtime " +
                    "found near $srcDir, and expectedCompactRuntime was not set.",
            )
        return if (emitted == expected) {
            CheckResult(
                name = "compact-runtime-pin",
                severity = Severity.PASS,
                message = "Contract emits + consumer pins @midnight-ntwrk/compact-runtime $emitted.",
            )
        } else {
            CheckResult(
                name = "compact-runtime-pin",
                severity = Severity.FAIL,
                message = "Contract was compiled against runtime $emitted but " +
                    "consumer pinned $expected. The runtime would crash with " +
                    "'Unsupported bytecode version' at runtime. " +
                    "validateKuiraContractSource will also catch this; see its " +
                    "error message for the full remediation list.",
            )
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun readContractInfoRuntime(srcDir: File): String? {
        val infoFile = srcDir.resolve("${KuiraContractPlugin.COMPILER_SUBDIR}/" +
            KuiraContractPlugin.CONTRACT_INFO_FILE)
        if (!infoFile.isFile) return null
        val text = runCatching { infoFile.readText() }.getOrNull() ?: return null
        return RUNTIME_VERSION_REGEX.find(text)?.groupValues?.get(1)
    }

    private fun discoverPackageRuntime(srcDir: File): String? {
        var current: File? = srcDir
        repeat(MAX_PACKAGE_JSON_WALK_UP) {
            current?.let { dir ->
                val pkg = dir.resolve(KuiraContractPlugin.PACKAGE_JSON_FILE)
                if (pkg.isFile) {
                    val text = runCatching { pkg.readText() }.getOrNull()
                    if (text != null) {
                        val match = COMPACT_RUNTIME_DEP_REGEX.find(text)
                        if (match != null) return match.groupValues[1]
                    }
                }
                current = dir.parentFile
            }
        }
        return null
    }

    /**
     * Returns (HTTP status, body) on a completed request — null on
     * network error / timeout (host unreachable, DNS failure, TLS
     * handshake failure, etc.). 5-second timeouts on connect + read so
     * a flaky network doesn't hang a build.
     */
    private fun httpGetWithTimeout(url: String): Pair<Int, String?>? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            val status = conn.responseCode
            val body = runCatching {
                val stream = if (status in HTTP_2XX) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            conn.disconnect()
            status to body
        } catch (_: Exception) {
            null
        }
    }

    private fun printReport(results: List<CheckResult>) {
        val passed = results.count { it.severity == Severity.PASS }
        val warned = results.count { it.severity == Severity.WARN }
        val failed = results.count { it.severity == Severity.FAIL }
        val skipped = results.count { it.severity == Severity.SKIP }

        logger.lifecycle("")
        logger.lifecycle("─── kuiraDoctor ─────────────────────────────────────────")
        results.forEach { r ->
            val symbol = when (r.severity) {
                Severity.PASS -> "✓"
                Severity.WARN -> "⚠"
                Severity.FAIL -> "✗"
                Severity.SKIP -> "—"
            }
            logger.lifecycle("$symbol ${r.severity.name.padEnd(5)} ${r.name}")
            r.message.lines().forEach { line -> logger.lifecycle("       $line") }
        }
        logger.lifecycle("─────────────────────────────────────────────────────────")
        logger.lifecycle("$passed passed, $warned warning, $failed error, $skipped skipped")
        logger.lifecycle("")
    }

    enum class Severity { PASS, WARN, FAIL, SKIP }

    data class CheckResult(
        val name: String,
        val severity: Severity,
        val message: String,
    )

    companion object {
        internal const val TASK_NAME = "kuiraDoctor"

        // Kuira SDK requires API 30 (Block Store API + CredentialManager
        // + Android 11+ scoped storage assumptions).
        internal const val MIN_SDK_REQUIRED = 30

        private const val HTTP_TIMEOUT_MS = 5_000
        private const val HTTP_NOT_FOUND = 404
        private val HTTP_2XX = 200..299

        // Bound the package.json walk-up identically to KuiraContractPlugin.
        private const val MAX_PACKAGE_JSON_WALK_UP = 6

        private val RUNTIME_VERSION_REGEX =
            """"runtime-version"\s*:\s*"([^"]+)"""".toRegex()
        private val COMPACT_RUNTIME_DEP_REGEX =
            """"@midnight-ntwrk/compact-runtime"\s*:\s*"([^"]+)"""".toRegex()
        // The assetlinks.json spec mandates a top-level JSON array.
        // A response that starts with anything else (HTML, plain text)
        // is a hosting misconfiguration.
        private val ASSETLINKS_ROOT_JSON = """^\s*\[""".toRegex()
    }
}
