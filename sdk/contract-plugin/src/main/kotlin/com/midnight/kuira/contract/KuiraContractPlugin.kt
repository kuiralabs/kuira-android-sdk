package com.midnight.kuira.contract

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskProvider
import java.util.concurrent.Callable

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
 *  (default = `source` dirname).
 * 2. Registers a `syncContractAssets` task that copies, from `source`:
 *  - `contract/index.js` to `assets/runtime/<alias>-contract.js`
 *  - prover and verifier files (`.prover`, `.verifier`) under `keys`
 *  to `assets/keys`
 *  - bzkir files (`.bzkir`) under `zkir` to `assets/keys`
 * 3. Wires `syncContractAssets` as a dependency of the Android module's
 *  `preBuild` task, so assets are synced before any APK is assembled.
 * 4. Fails fast at task execution time with a clear message if the
 *  source directory is missing.
 * 5. Cross-checks the compiled contract's
 *  `compiler/contract-info.json` `runtime-version` against the
 *  consumer's pinned `@midnight-ntwrk/compact-runtime` version (either
 *  auto-discovered from a co-located `package.json`, or explicitly
 *  set via `kuiraContract.expectedRuntimeVersion`). Fails the build
 *  on mismatch so the consumer doesn't ship an APK that crashes at
 *  runtime with "Unsupported bytecode version."
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
        // A dApp configures ONE contract via the top-level `source` shorthand, OR many via the
        // `contracts { }` container. Each contract gets its own validate + sync + generated facade;
        // collect the sync tasks to wire into preBuild once (afterEvaluate). registerContract is a
        // PRIVATE METHOD (not a local function) so it doesn't capture apply()'s scope — a captured
        // local leaks a non-serializable Project/Copy into sibling tasks' config-cache state.
        val syncTasks = mutableListOf<TaskProvider<Copy>>()

        // Single-contract shorthand.
        syncTasks += registerContract(
            project, "", extension.source, extension.alias,
            extension.expectedRuntimeVersion, extension.requireRuntimeMatch,
        )

        // Multi-contract container. `.all` fires per entry as the consumer's `contracts { }` block
        // adds them (config time), so the onVariants wiring lands during configuration.
        extension.contracts.all { spec ->
            spec.alias.convention(project.provider { spec.name })
            spec.requireRuntimeMatch.convention(extension.requireRuntimeMatch)
            syncTasks += registerContract(
                project, spec.name, spec.source, spec.alias,
                spec.expectedRuntimeVersion, spec.requireRuntimeMatch,
            )
        }

        // provisionWalletKeys — downloads + stages the protocol wallet proving
        // keys into assets when bundleWalletKeys is on (the offline-bundle path).
        // gradleUserHomeDir is captured at configuration time so the action stays
        // configuration-cache clean (no `project` access at execution).
        val gradleUserHome = project.gradle.gradleUserHomeDir
        // Capture the Property, NOT `extension`: an onlyIf lambda is serialized into the config
        // cache, and capturing `extension` drags in its `contracts` NamedDomainObjectContainer, which
        // holds a non-serializable Project → the config cache fails to store (surfaced here on
        // provisionWalletKeys). A bare Property is config-cache-safe.
        val bundleWalletKeys = extension.bundleWalletKeys
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
            task.onlyIf { bundleWalletKeys.get() }
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
            if (!extension.source.isPresent && extension.contracts.isEmpty()) {
                throw GradleException(
                    "kuiraContract needs a contract: set `source.set(\"contract/src/managed/<name>\")` " +
                        "for one, or a `contracts { register(\"<Name>\") { source.set(...) } }` block for " +
                        "many. Paths are relative to the project directory.",
                )
            }
            // A container entry is never optional — unlike the shorthand it can't silently skip. Fail
            // loudly if one forgets `source`, else its (onlyIf-gated) validate never runs and the build
            // ships an asset-less / never-generated contract with no diagnostic.
            extension.contracts.forEach { spec ->
                if (!spec.source.isPresent) {
                    throw GradleException(
                        "kuiraContract.contracts entry '${spec.name}' is missing its source — add " +
                            "`source.set(\"contract/src/managed/${spec.name}\")` to it.",
                    )
                }
            }
            // Every contract (shorthand + container) emits <Alias>Contract into ONE generated package
            // and a name-discriminated sync/generate task. Aliases that collapse to the same class /
            // task name (case-only or separator-only differences, or a shorthand+container overlap)
            // would produce a duplicate-class compile error or a task-name clash — catch it here with
            // a clear message instead.
            val aliases = buildList {
                if (extension.source.isPresent) add(extension.alias.get())
                extension.contracts.forEach { add(it.alias.get()) }
            }
            aliases.groupBy { it.lowercase().filter(Char::isLetterOrDigit) }
                .filterValues { it.size > 1 }
                .forEach { (_, clashing) ->
                    throw GradleException(
                        "kuiraContract: contract aliases $clashing collapse to the same generated class " +
                            "and task name. Give each contract a distinct alias.",
                    )
                }
            val preBuild = project.tasks.findByName(ANDROID_PREBUILD_TASK)
                ?: throw GradleException(
                    "io.github.kuiralabs.contract requires an Android plugin " +
                        "(com.android.application or com.android.library) to be applied. " +
                        "Apply one of those before configuring kuiraContract.",
                )
            // Each contract's sync task is onlyIf-gated on its own source, so wiring the unused
            // half (shorthand or container) costs nothing.
            syncTasks.forEach { preBuild.dependsOn(it) }
            // Always wired; the task's onlyIf skips it when bundleWalletKeys is off,
            // so a consumer that doesn't opt in pays nothing.
            preBuild.dependsOn(provisionWalletKeysTask)
        }
    }

    /**
     * Register validate + sync + the generated facade for ONE contract; returns its sync task.
     *
     * A PRIVATE METHOD (project passed in) — deliberately NOT a local function inside [apply] — so it
     * does not capture apply()'s local scope. A local function that captures `project` + the sync-task
     * list changes Kotlin's closure compilation such that a non-serializable `Project`/`Copy` leaks
     * into sibling tasks' config-cache state (it surfaced as a `provisionWalletKeys` store problem).
     *
     * `discriminator` = "" is the single-contract shorthand (keeps the legacy task names); a container
     * entry passes its name (name-discriminated tasks). Each contract's tasks onlyIf-skip when its
     * source isn't set, so the shorthand and the container coexist.
     */
    private fun registerContract(
        project: Project,
        discriminator: String,
        source: Property<String>,
        alias: Provider<String>,
        expectedRuntimeVersion: Provider<String>,
        requireRuntimeMatch: Provider<Boolean>,
    ): TaskProvider<Copy> {
        val sourceDir = source.map { rel -> project.layout.projectDirectory.dir(rel) }
        // Present iff this contract's source is set; read at task time (after the consumer's
        // kuiraContract{} block), so `false` cleanly disables the unused shorthand/container half.
        val enabled = source.map { true }.orElse(false)
        // Gradle resolves a task's inputs even when onlyIf will skip it. An unset source (the unused
        // shorthand in a container-only build) makes `sourceDir` ABSENT — and `Copy.from(absent
        // provider)` fails dependency resolution. Fall back to a nonexistent build dir so the inputs
        // resolve to EMPTY instead; the task onlyIf-skips execution regardless.
        val safeSourceDir = sourceDir.orElse(project.layout.buildDirectory.dir("kuira-no-contract"))
        val suffix = discriminator.replaceFirstChar { it.uppercaseChar() }
        val validateName = if (discriminator.isEmpty()) ValidateKuiraContractSourceTask.TASK_NAME
        else "validate${suffix}ContractSource"
        val syncName = if (discriminator.isEmpty()) SYNC_TASK_NAME else "sync${suffix}ContractAssets"

        val validateTask = project.tasks.register(
            validateName,
            ValidateKuiraContractSourceTask::class.java,
        ) { task ->
            task.sourceDirectory.set(safeSourceDir)
            task.expectedRuntimeVersion.set(expectedRuntimeVersion)
            task.requireRuntimeMatch.set(requireRuntimeMatch)
            task.onlyIf { enabled.get() }
        }

        // A container entry syncs its circuit keys to a per-alias dir (`<alias>-keys`) so contracts
        // that share a circuit name don't overwrite each other in one `assets/keys/`; the shorthand
        // keeps the plain `keys` dir (existing consumers read from there). Resolved lazily (a Callable)
        // because the alias may be `alias.set(...)` later in the consumer's block. Mirrors the
        // generated facade's KEYS_ASSET_DIR constant.
        val keysAssetDest = Callable {
            if (discriminator.isEmpty()) ASSETS_KEYS_SUBDIR else "${alias.getOrElse(discriminator)}-keys"
        }
        val syncTask = project.tasks.register(syncName, Copy::class.java) { task ->
            task.group = "build"
            task.description = "Sync compiled Compact contract artifacts into Android assets."
            task.dependsOn(validateTask)
            task.from(safeSourceDir.map { it.dir(CONTRACT_SUBDIR) }) { spec ->
                spec.include("index.js")
                spec.rename { _ -> "${alias.getOrElse("contract")}-contract.js" }
                spec.into(ASSETS_RUNTIME_SUBDIR)
            }
            task.from(safeSourceDir.map { it.dir(KEYS_SUBDIR) }) { spec ->
                spec.include("*.prover", "*.verifier")
                spec.into(keysAssetDest)
            }
            task.from(safeSourceDir.map { it.dir(ZKIR_SUBDIR) }) { spec ->
                spec.include("*.bzkir")
                spec.into(keysAssetDest)
            }
            task.into(project.layout.projectDirectory.dir(ASSETS_DEST))
            task.onlyIf { enabled.get() }
        }

        // generate<Variant><Contract>ContractApi — a typed facade from the ABI, wired into the Kotlin
        // compile per variant. Must register during config (onVariants), which plugins.withId
        // (shorthand) + contracts.all (container) both satisfy.
        val contractInfoProvider = sourceDir.map { dir ->
            dir.file("$COMPILER_SUBDIR/$CONTRACT_INFO_FILE")
        }
        project.plugins.withId(ANDROID_APP_PLUGIN_ID) {
            GeneratedSourceRegistrar.register(project, discriminator, contractInfoProvider, alias, enabled)
        }
        project.plugins.withId(ANDROID_LIB_PLUGIN_ID) {
            GeneratedSourceRegistrar.register(project, discriminator, contractInfoProvider, alias, enabled)
        }
        return syncTask
    }

    companion object {
        internal const val SYNC_TASK_NAME = "syncContractAssets"
        internal const val ANDROID_PREBUILD_TASK = "preBuild"

        // String plugin ids used to gate the AGP source-registration touch
        // without referencing AGP types from this class (see afterEvaluate).
        internal const val ANDROID_APP_PLUGIN_ID = "com.android.application"
        internal const val ANDROID_LIB_PLUGIN_ID = "com.android.library"

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

        // Wallet-key offline bundle. Subdir kept distinct from contract
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
