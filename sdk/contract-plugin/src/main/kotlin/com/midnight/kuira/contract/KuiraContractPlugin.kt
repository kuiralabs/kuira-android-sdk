package com.midnight.kuira.contract

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import java.io.File

/**
 * `io.github.kuiralabs.contract` — wires a compiled Compact contract into
 * an Android app's `assets` directory using a declarative
 * `kuiraContract` block.
 *
 * Replaces the hand-rolled `syncContractAssets` Copy task each Kuira
 * dApp re-implemented in its own build script (see Kicks and BBoard
 * pre-alpha02 for the boilerplate this eliminates).
 *
 * Behaviour:
 *
 * 1. Reads the configured `source` directory and resolves the alias
 *    (default = `source` dirname).
 * 2. Registers a `syncContractAssets` task that copies, from `source`:
 *    - `contract/index.js` to `assets/runtime/<alias>-contract.js`
 *    - prover and verifier files (`.prover`, `.verifier`) under `keys`
 *      to `assets/keys`
 *    - bzkir files (`.bzkir`) under `zkir` to `assets/keys`
 * 3. Wires `syncContractAssets` as a dependency of the Android module's
 *    `preBuild` task, so assets are synced before any APK is assembled.
 * 4. Fails fast at task execution time with a clear message if the
 *    source directory is missing.
 * 5. Cross-checks the compiled contract's
 *    `compiler/contract-info.json` `runtime-version` against the
 *    consumer's pinned `@midnight-ntwrk/compact-runtime` version (either
 *    auto-discovered from a co-located `package.json`, or explicitly
 *    set via `kuiraContract.expectedRuntimeVersion`). Fails the build
 *    on mismatch so the consumer doesn't ship an APK that crashes at
 *    runtime with "Unsupported bytecode version."
 *
 * The plugin requires an Android plugin (`com.android.application` or
 * `com.android.library`) to be applied to the same project.
 */
class KuiraContractPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "kuiraContract",
            KuiraContractExtension::class.java,
        )

        // Default alias = source dirname. Lazy via Property.map so the
        // computation runs at task-configuration time, after the
        // consumer's build script has set `source`.
        extension.alias.convention(
            extension.source.map { it.trimEnd('/').substringAfterLast('/') },
        )
        // Runtime-mismatch failure is the safe default — silently shipping
        // a mismatched APK is the failure mode this plugin exists to
        // prevent. Consumers opt out explicitly per the extension's
        // documentation if they have a deliberate reason.
        extension.requireRuntimeMatch.convention(true)
        // kuiraDoctor is warn-only by default. Consumers gating release
        // builds in CI set this to true to convert FAIL severities into
        // task failures.
        extension.requireDoctorPass.convention(false)

        // Resolve source lazily so it picks up the consumer's
        // configuration even if it's set after these register calls.
        val sourceProvider = extension.source.map { rel ->
            project.layout.projectDirectory.dir(rel)
        }
        val aliasProvider = extension.alias

        // The Copy task gets skipped (NO-SOURCE) by Gradle when its input
        // directory does not exist, which would silently let a build
        // proceed when the consumer forgot to compile their contract.
        // A separate validation task with a forced output state runs
        // first so the failure is loud and the diagnostic is owned by
        // the plugin (not deferred to a runtime crash).
        val validateTask = project.tasks.register(VALIDATE_TASK_NAME) { task ->
            task.group = "verification"
            task.description = "Verify the Kuira contract source directory exists and its emitted runtime-version matches the consumer's pin."
            // Always run — the check is cheap and the cost of a false
            // up-to-date is "consumer ships a broken APK."
            task.outputs.upToDateWhen { false }
            task.doLast {
                // `source` presence is checked at configuration time in
                // afterEvaluate below — by the time task actions run,
                // Gradle has already resolved providers, so a missing
                // `source` would have surfaced earlier. Here we only
                // catch the "configured but not compiled" case.
                val sourceDir = sourceProvider.get().asFile
                if (!sourceDir.exists()) {
                    throw GradleException(
                        "Kuira contract source not found at $sourceDir — " +
                            "compile your contract first (e.g. `npm run compact` in your contract directory).",
                    )
                }
                validateRuntimeVersion(
                    project = project,
                    sourceDir = sourceDir,
                    explicitExpected = extension.expectedRuntimeVersion.orNull,
                    requireMatch = extension.requireRuntimeMatch.get(),
                )
            }
        }

        val syncTask = project.tasks.register(SYNC_TASK_NAME, Copy::class.java) { task ->
            task.group = "build"
            task.description = "Sync compiled Compact contract artifacts into Android assets."
            task.dependsOn(validateTask)

            task.from(sourceProvider.map { it.dir(CONTRACT_SUBDIR) }) { spec ->
                spec.include("index.js")
                spec.rename { _ -> "${aliasProvider.get()}-contract.js" }
                spec.into(ASSETS_RUNTIME_SUBDIR)
            }
            task.from(sourceProvider.map { it.dir(KEYS_SUBDIR) }) { spec ->
                spec.include("*.prover", "*.verifier")
                spec.into(ASSETS_KEYS_SUBDIR)
            }
            task.from(sourceProvider.map { it.dir(ZKIR_SUBDIR) }) { spec ->
                spec.include("*.bzkir")
                spec.into(ASSETS_KEYS_SUBDIR)
            }
            task.into(project.layout.projectDirectory.dir(ASSETS_DEST))
        }

        // kuiraDoctor — standalone preflight task. NOT wired to preBuild.
        // Consumer invokes explicitly (`./gradlew :app:kuiraDoctor`) or
        // wires into a release-only lifecycle in their own build script.
        project.tasks.register(KuiraDoctorTask.TASK_NAME, KuiraDoctorTask::class.java) { task ->
            task.rpId.set(extension.rpId)
            task.contractSource.set(sourceProvider)
            task.expectedCompactRuntime.set(extension.expectedRuntimeVersion)
            task.requireDoctorPass.set(extension.requireDoctorPass)
            // Auto-discover applicationId + minSdk from the consumer's
            // app/build.gradle.kts via simple regex. Robust enough for
            // the canonical Android Kotlin DSL layout; consumers with
            // non-standard setups can override via task properties.
            val buildFile = project.layout.projectDirectory.file(CONSUMER_BUILD_FILE).asFile
            if (buildFile.isFile) {
                val text = runCatching { buildFile.readText() }.getOrNull()
                if (text != null) {
                    APP_ID_REGEX.find(text)?.groupValues?.get(1)?.let { task.applicationId.set(it) }
                    MIN_SDK_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { task.minSdk.set(it) }
                }
            }
        }

        // Validate consumer config + wire into preBuild. afterEvaluate runs
        // at the end of project configuration, BEFORE Gradle resolves the
        // task graph — so missing-config errors fire here instead of as
        // an inscrutable MissingValueException at task scheduling time.
        project.afterEvaluate {
            if (!extension.source.isPresent) {
                throw GradleException(
                    "kuiraContract.source must be set — declare " +
                        "`kuiraContract { source.set(\"contract/src/managed/<name>\") }` " +
                        "in your build script. The path is relative to the project directory.",
                )
            }
            val preBuild = project.tasks.findByName(ANDROID_PREBUILD_TASK)
                ?: throw GradleException(
                    "io.github.kuiralabs.contract requires an Android plugin " +
                        "(com.android.application or com.android.library) to be applied. " +
                        "Apply one of those before configuring kuiraContract.",
                )
            preBuild.dependsOn(syncTask)
        }
    }

    /**
     * Verify the contract's emitted runtime-version matches the
     * consumer's pin.
     *
     * Resolution order for the expected version:
     *   1. [explicitExpected] (if `kuiraContract.expectedRuntimeVersion` was set)
     *   2. Auto-discovered from the nearest co-located `package.json`
     *      that declares `@midnight-ntwrk/compact-runtime` in its
     *      `dependencies` (walked up from [sourceDir])
     *   3. Skip the check (log only) if neither is available
     *
     * Skipped silently when:
     *   - `compiler/contract-info.json` is missing or unparseable
     *     (older compactc output; can't make a meaningful claim)
     *   - No expected version can be determined (case 3 above)
     *
     * Fails loudly when versions are known and don't match, unless
     * [requireMatch] is false (logs warning instead).
     */
    private fun validateRuntimeVersion(
        project: Project,
        sourceDir: File,
        explicitExpected: String?,
        requireMatch: Boolean,
    ) {
        val emitted = readEmittedRuntimeVersion(sourceDir) ?: run {
            project.logger.lifecycle(
                "[$PLUGIN_NAME] No compiler/contract-info.json under $sourceDir " +
                    "— runtime-version check skipped. Older compactc output may not emit this file.",
            )
            return
        }

        val expected = explicitExpected ?: autoDiscoverExpectedRuntime(sourceDir) ?: run {
            project.logger.lifecycle(
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
        project.logger.warn("[$PLUGIN_NAME] $message")
    }

    /**
     * Extract `runtime-version` from `<sourceDir>/compiler/contract-info.json`.
     * Returns null if the file or field is missing — we don't pull a
     * JSON parser dependency into a Gradle plugin's classpath for a
     * single-field probe; regex is sufficient and resilient to the
     * cosmetic JSON layout (compactc emits a stable shape, and any
     * future shape change would surface in code review).
     */
    private fun readEmittedRuntimeVersion(sourceDir: File): String? {
        val infoFile = sourceDir.resolve("$COMPILER_SUBDIR/$CONTRACT_INFO_FILE")
        if (!infoFile.isFile) return null
        val text = runCatching { infoFile.readText() }.getOrNull() ?: return null
        return RUNTIME_VERSION_REGEX.find(text)?.groupValues?.get(1)
    }

    /**
     * Walk up from `sourceDir` looking for a `package.json` that
     * declares `@midnight-ntwrk/compact-runtime` in its `dependencies`.
     * Returns the pinned version string or null.
     *
     * Stops at the filesystem root or after [MAX_PACKAGE_JSON_WALK_UP]
     * directory levels, whichever comes first — bounds the walk so an
     * unrelated package.json many levels up doesn't get picked up by
     * accident.
     */
    private fun autoDiscoverExpectedRuntime(sourceDir: File): String? {
        var current: File? = sourceDir
        repeat(MAX_PACKAGE_JSON_WALK_UP) {
            current?.let { dir ->
                val pkg = dir.resolve(PACKAGE_JSON_FILE)
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
        internal const val SYNC_TASK_NAME = "syncContractAssets"
        internal const val VALIDATE_TASK_NAME = "validateKuiraContractSource"
        internal const val ANDROID_PREBUILD_TASK = "preBuild"

        internal const val CONTRACT_SUBDIR = "contract"
        internal const val KEYS_SUBDIR = "keys"
        internal const val ZKIR_SUBDIR = "zkir"
        internal const val COMPILER_SUBDIR = "compiler"
        internal const val CONTRACT_INFO_FILE = "contract-info.json"
        internal const val PACKAGE_JSON_FILE = "package.json"

        internal const val ASSETS_DEST = "src/main/assets"
        internal const val ASSETS_RUNTIME_SUBDIR = "runtime"
        internal const val ASSETS_KEYS_SUBDIR = "keys"

        // Consumer's app-module build script used for applicationId +
        // minSdk auto-discovery by the kuiraDoctor task.
        private const val CONSUMER_BUILD_FILE = "build.gradle.kts"

        // Regex-based readers for the most common Android-Kotlin-DSL
        // shapes. Robust enough for canonical setups; consumers with
        // computed/property-based config can override via task inputs.
        private val APP_ID_REGEX =
            """applicationId\s*=\s*"([^"]+)"""".toRegex()
        private val MIN_SDK_REGEX =
            """minSdk\s*=\s*(\d+)""".toRegex()

        // Plugin label used in lifecycle/warn log lines so the user
        // can tell our diagnostics from arbitrary Gradle output.
        private const val PLUGIN_NAME = "kuira-contract"

        // Bound the package.json walk-up to avoid picking up an
        // unrelated package.json from a parent monorepo. Six levels
        // covers the canonical contract/src/managed/<name> layout
        // (which is 3 levels deep) plus reasonable nesting headroom.
        private const val MAX_PACKAGE_JSON_WALK_UP = 6

        // Single-line JSON regex for the contract-info.json runtime-version
        // field. compactc emits whitespace-normalized JSON; this matches
        // both `"runtime-version": "0.16.0"` and `"runtime-version":"0.16.0"`.
        private val RUNTIME_VERSION_REGEX =
            """"runtime-version"\s*:\s*"([^"]+)"""".toRegex()

        // package.json npm-dependency entry for @midnight-ntwrk/compact-runtime.
        // Captures the version specifier verbatim (caret/tilde ranges
        // included — the comparator at the call site does exact string
        // match, which surfaces "you pinned ^0.16.0 but the contract
        // wants 0.16.0" as a mismatch the consumer must resolve
        // explicitly. Exact pinning is the recommended pattern for
        // wallet-grade reproducibility.).
        private val COMPACT_RUNTIME_DEP_REGEX =
            """"@midnight-ntwrk/compact-runtime"\s*:\s*"([^"]+)"""".toRegex()
    }
}
