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

/**
 * Verifies the Kuira contract source directory exists and that its
 * emitted `runtime-version` matches the consumer's pinned
 * `@midnight-ntwrk/compact-runtime` — failing the build on a mismatch so
 * the consumer doesn't ship an APK that crashes at runtime with
 * "Unsupported bytecode version."
 *
 * A typed task (rather than a `doLast` closure on a generic task) so it
 * captures only its declared properties — never the Gradle [org.gradle.api.Project]
 * — and is therefore compatible with the **configuration cache**. A
 * `Project`-capturing task action is the canonical config-cache
 * violation; doing the work here against [DefaultTask.getLogger] and
 * the property values keeps every consumer build cacheable.
 */
abstract class ValidateKuiraContractSourceTask : DefaultTask() {

    /**
     * Compiled contract source directory. `@Internal` (not
     * `@InputDirectory`) because the task validates the directory's
     * existence itself — an `@InputDirectory` would make Gradle fail
     * with a generic "directory does not exist" before this task's
     * friendly "compile your contract first" message can fire — and it
     * already opts out of up-to-date checks below.
     */
    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    /** Explicit override of the expected compact-runtime version. */
    @get:Input
    @get:Optional
    abstract val expectedRuntimeVersion: Property<String>

    /** Fail the build on a runtime-version mismatch when true. */
    @get:Input
    abstract val requireRuntimeMatch: Property<Boolean>

    init {
        group = "verification"
        description = "Verify the Kuira contract source directory exists and its emitted " +
            "runtime-version matches the consumer's pin."
        // Always run — the check is cheap and the cost of a false
        // up-to-date is "consumer ships a broken APK."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun validate() {
        val sourceDir = sourceDirectory.get().asFile
        if (!sourceDir.exists()) {
            throw GradleException(
                "Kuira contract source not found at $sourceDir — " +
                    "compile your contract first (e.g. `npm run compact` in your contract directory).",
            )
        }
        validateRuntimeVersion(
            sourceDir = sourceDir,
            explicitExpected = expectedRuntimeVersion.orNull,
            requireMatch = requireRuntimeMatch.get(),
        )
    }

    /**
     * Verify the contract's emitted runtime-version matches the
     * consumer's pin.
     *
     * Resolution order for the expected version:
     *   1. [explicitExpected] (if `kuiraContract.expectedRuntimeVersion` was set)
     *   2. Auto-discovered from the nearest co-located `package.json`
     *      that declares `@midnight-ntwrk/compact-runtime` (walked up
     *      from [sourceDir])
     *   3. Skip the check (log only) if neither is available
     *
     * Skipped silently when `compiler/contract-info.json` is missing or
     * unparseable (older compactc output). Fails loudly when versions
     * are known and don't match, unless [requireMatch] is false (logs a
     * warning instead).
     */
    private fun validateRuntimeVersion(sourceDir: File, explicitExpected: String?, requireMatch: Boolean) {
        val emitted = readEmittedRuntimeVersion(sourceDir) ?: run {
            logger.lifecycle(
                "[$PLUGIN_NAME] No compiler/contract-info.json under $sourceDir " +
                    "— runtime-version check skipped. Older compactc output may not emit this file.",
            )
            return
        }

        val expected = explicitExpected ?: autoDiscoverExpectedRuntime(sourceDir) ?: run {
            logger.lifecycle(
                "[$PLUGIN_NAME] No package.json declaring @midnight-ntwrk/compact-runtime " +
                    "found near $sourceDir, and kuiraContract.expectedRuntimeVersion was not set " +
                    "— runtime-version check skipped. Set expectedRuntimeVersion if you ship " +
                    "contracts without a co-located package.json.",
            )
            return
        }

        if (emitted == expected) return

        val message = buildString {
            append("Compact runtime version mismatch.\n")
            append("  Contract at $sourceDir was compiled against @midnight-ntwrk/compact-runtime $emitted\n")
            append("  Consumer pinned @midnight-ntwrk/compact-runtime $expected\n")
            append("\n")
            append("  Shipping the APK with this mismatch will produce an 'Unsupported bytecode " +
                "version' error at runtime when the QuickJS contract runtime loads the contract.\n")
            append("\n")
            append("  Fix one of:\n")
            append("    - Bump the package.json dependency on @midnight-ntwrk/compact-runtime to $emitted, " +
                "or set kuiraContract.expectedRuntimeVersion.set(\"$emitted\") if you don't have a co-located package.json.\n")
            append("    - Recompile the contract with a compactc that emits runtime $expected " +
                "(compactc --runtime-version to confirm before recompiling).\n")
            append("    - As a last resort, opt out for this build with kuiraContract.requireRuntimeMatch.set(false).")
        }
        if (requireMatch) {
            throw GradleException(message)
        }
        logger.warn("[$PLUGIN_NAME] $message")
    }

    /**
     * Extract `runtime-version` from `<sourceDir>/compiler/contract-info.json`.
     * Returns null if the file or field is missing — regex is sufficient
     * for a single-field probe and resilient to cosmetic JSON layout, so
     * we don't pull a JSON parser onto the plugin classpath.
     */
    private fun readEmittedRuntimeVersion(sourceDir: File): String? {
        val infoFile = sourceDir.resolve(
            "${KuiraContractPlugin.COMPILER_SUBDIR}/${KuiraContractPlugin.CONTRACT_INFO_FILE}",
        )
        if (!infoFile.isFile) return null
        val text = runCatching { infoFile.readText() }.getOrNull() ?: return null
        return RUNTIME_VERSION_REGEX.find(text)?.groupValues?.get(1)
    }

    /**
     * Walk up from `sourceDir` looking for a `package.json` that declares
     * `@midnight-ntwrk/compact-runtime`. Returns the pinned version or
     * null. Bounded to [MAX_PACKAGE_JSON_WALK_UP] levels so an unrelated
     * parent-monorepo package.json isn't picked up by accident.
     */
    private fun autoDiscoverExpectedRuntime(sourceDir: File): String? {
        var current: File? = sourceDir
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

    companion object {
        internal const val TASK_NAME = "validateKuiraContractSource"

        // Plugin label used in lifecycle/warn log lines so the user can
        // tell our diagnostics from arbitrary Gradle output.
        private const val PLUGIN_NAME = "kuira-contract"

        // Six levels covers the canonical contract/src/managed/<name>
        // layout (3 deep) plus reasonable nesting headroom.
        private const val MAX_PACKAGE_JSON_WALK_UP = 6

        // compactc emits whitespace-normalized JSON; matches both
        // `"runtime-version": "0.16.0"` and `"runtime-version":"0.16.0"`.
        private val RUNTIME_VERSION_REGEX =
            """"runtime-version"\s*:\s*"([^"]+)"""".toRegex()

        // package.json npm-dependency entry for @midnight-ntwrk/compact-runtime.
        // Captures the specifier verbatim; the comparator does an exact
        // string match so `^0.16.0` vs `0.16.0` surfaces as a mismatch
        // the consumer resolves explicitly (exact pinning is the
        // recommended pattern for wallet-grade reproducibility).
        private val COMPACT_RUNTIME_DEP_REGEX =
            """"@midnight-ntwrk/compact-runtime"\s*:\s*"([^"]+)"""".toRegex()
    }
}
