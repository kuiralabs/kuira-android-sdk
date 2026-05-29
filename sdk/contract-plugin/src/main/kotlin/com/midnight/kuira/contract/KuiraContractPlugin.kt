package com.midnight.kuira.contract

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy

/**
 * `com.midnight.kuira.contract` — wires a compiled Compact contract into
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
            task.description = "Verify the Kuira contract source directory exists and is non-empty."
            // Always run — the check is cheap and the cost of a false
            // up-to-date is "consumer ships a broken APK."
            task.outputs.upToDateWhen { false }
            task.doLast {
                val sourceDir = sourceProvider.get().asFile
                if (!sourceDir.exists()) {
                    throw GradleException(
                        "Kuira contract source not found at $sourceDir — " +
                            "compile your contract first (e.g. `npm run compact` in your contract directory).",
                    )
                }
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

        // Wire into preBuild so any forgotten copy is caught at build
        // time, not runtime. Done in afterEvaluate because the Android
        // plugin's `preBuild` task is registered during configuration.
        project.afterEvaluate {
            val preBuild = project.tasks.findByName(ANDROID_PREBUILD_TASK)
                ?: throw GradleException(
                    "com.midnight.kuira.contract requires an Android plugin " +
                        "(com.android.application or com.android.library) to be applied. " +
                        "Apply one of those before configuring kuiraContract.",
                )
            preBuild.dependsOn(syncTask)
        }
    }

    companion object {
        internal const val SYNC_TASK_NAME = "syncContractAssets"
        internal const val VALIDATE_TASK_NAME = "validateKuiraContractSource"
        internal const val ANDROID_PREBUILD_TASK = "preBuild"

        internal const val CONTRACT_SUBDIR = "contract"
        internal const val KEYS_SUBDIR = "keys"
        internal const val ZKIR_SUBDIR = "zkir"

        internal const val ASSETS_DEST = "src/main/assets"
        internal const val ASSETS_RUNTIME_SUBDIR = "runtime"
        internal const val ASSETS_KEYS_SUBDIR = "keys"
    }
}
