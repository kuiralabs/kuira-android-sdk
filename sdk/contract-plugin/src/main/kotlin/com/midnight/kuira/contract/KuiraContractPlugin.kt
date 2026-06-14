package com.midnight.kuira.contract

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy

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
        // Wallet-key bundling is opt-in (it adds ~24MB to the APK). When on, the
        // version/source default to the SDK's pinned values.
        extension.bundleWalletKeys.convention(false)
        extension.walletKeysVersion.convention(DEFAULT_WALLET_KEYS_VERSION)
        extension.walletKeysBaseUrl.convention(DEFAULT_WALLET_KEYS_BASE_URL)

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
        //
        // It's a typed task (not a `doLast` closure) so its action
        // captures only the declared properties — never `project` — and
        // is therefore compatible with the configuration cache.
        val validateTask = project.tasks.register(
            ValidateKuiraContractSourceTask.TASK_NAME,
            ValidateKuiraContractSourceTask::class.java,
        ) { task ->
            task.sourceDirectory.set(sourceProvider)
            task.expectedRuntimeVersion.set(extension.expectedRuntimeVersion)
            task.requireRuntimeMatch.set(extension.requireRuntimeMatch)
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

        // provisionWalletKeys — downloads + stages the protocol wallet proving
        // keys into assets when bundleWalletKeys is on (the offline-bundle path).
        // gradleUserHomeDir is captured at configuration time so the action stays
        // configuration-cache clean (no `project` access at execution).
        val gradleUserHome = project.gradle.gradleUserHomeDir
        val provisionWalletKeysTask = project.tasks.register(
            ProvisionWalletKeysTask.TASK_NAME,
            ProvisionWalletKeysTask::class.java,
        ) { task ->
            task.group = "build"
            task.description =
                "Download + stage protocol wallet proving keys into assets (offline bundle, #256)."
            task.version.set(extension.walletKeysVersion)
            task.baseUrl.set(extension.walletKeysBaseUrl)
            task.cacheRoot.fileValue(gradleUserHome.resolve(WALLET_KEYS_CACHE_SUBPATH))
            task.assetsDir.set(
                project.layout.projectDirectory.dir("$ASSETS_DEST/$ASSETS_WALLET_KEYS_SUBDIR"),
            )
            // Skip entirely (no download, no staging) unless the consumer opted in.
            task.onlyIf { extension.bundleWalletKeys.get() }
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
            // Always wired; the task's onlyIf skips it when bundleWalletKeys is off,
            // so a consumer that doesn't opt in pays nothing.
            preBuild.dependsOn(provisionWalletKeysTask)
        }
    }

    companion object {
        internal const val SYNC_TASK_NAME = "syncContractAssets"
        internal const val ANDROID_PREBUILD_TASK = "preBuild"

        internal const val CONTRACT_SUBDIR = "contract"
        internal const val KEYS_SUBDIR = "keys"
        internal const val ZKIR_SUBDIR = "zkir"

        // Shared contract-source path names — read by both
        // ValidateKuiraContractSourceTask and KuiraDoctorTask when
        // probing the compiled contract's emitted runtime-version.
        internal const val COMPILER_SUBDIR = "compiler"
        internal const val CONTRACT_INFO_FILE = "contract-info.json"
        internal const val PACKAGE_JSON_FILE = "package.json"

        internal const val ASSETS_DEST = "src/main/assets"
        internal const val ASSETS_RUNTIME_SUBDIR = "runtime"
        internal const val ASSETS_KEYS_SUBDIR = "keys"

        // Wallet-key offline bundle (#256). Subdir kept distinct from contract
        // keys ("keys") so the two asset installers never collide. Matches the
        // SDK's ProvingKeyManager.WALLET_ASSET_DIR.
        internal const val ASSETS_WALLET_KEYS_SUBDIR = "wallet-keys"
        // Default ledger-pinned version + key source (the SDK's CURRENT_VERSION
        // and S3_BASE_URL). Overridable via the kuiraContract extension.
        private const val DEFAULT_WALLET_KEYS_VERSION = 9
        private const val DEFAULT_WALLET_KEYS_BASE_URL =
            "https://midnight-s3-fileshare-dev-eu-west-1.s3.eu-west-1.amazonaws.com"
        // Machine-shared download cache, relative to the Gradle user home.
        private const val WALLET_KEYS_CACHE_SUBPATH = "caches/kuira-wallet-keys"

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
    }
}
