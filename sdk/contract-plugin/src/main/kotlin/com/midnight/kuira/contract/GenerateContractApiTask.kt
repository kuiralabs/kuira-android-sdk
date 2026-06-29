package com.midnight.kuira.contract

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates a typed Kotlin facade (`<Alias>Contract`) over [MidnightContract.call]
 * from the compiled contract's `compiler/contract-info.json` ABI, so a dApp calls
 * circuits with compile-time-checked argument types instead of `call(name, Any?...)`.
 *
 * A typed task (not a `doLast` closure) so its action captures only the declared
 * properties — never the Gradle [org.gradle.api.Project] — keeping the consumer
 * build **configuration-cache** compatible, matching the convention the rest of
 * this plugin's tasks follow.
 *
 * The [outputDir] is registered as a Kotlin source root via the AGP variant
 * `sources.kotlin.addGeneratedSourceDirectory` API in [KuiraContractPlugin], so
 * the generated file compiles into the consumer's module with no manual wiring.
 */
abstract class GenerateContractApiTask : DefaultTask() {

    /**
     * The compiled contract's `compiler/contract-info.json`. `@InputFile` with
     * NAME_ONLY sensitivity: the file's CONTENT drives the generated API, and its
     * absolute path is irrelevant to the output.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val contractInfoFile: RegularFileProperty

    /** Contract alias (managed-dir name) — drives the generated class name. */
    @get:Input
    abstract val alias: Property<String>

    /**
     * Generated-source output directory. AGP sets its convention via
     * `addGeneratedSourceDirectory`; the task writes one `.kt` under it.
     */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "build"
        description = "Generate a typed Kotlin contract facade from the Compact ABI (contract-info.json)."
    }

    @TaskAction
    fun generate() {
        val infoFile = contractInfoFile.get().asFile
        if (!infoFile.isFile) {
            throw GradleException(
                "Cannot generate the typed contract API: $infoFile not found. " +
                    "Compile your contract first (e.g. `npm run compact`).",
            )
        }
        val json = infoFile.readText()
        val aliasValue = alias.get()

        val fileSpec = try {
            ContractApiGenerator.generate(aliasValue, json)
        } catch (e: Exception) {
            throw GradleException(
                "Failed to generate the typed contract API for '$aliasValue' from $infoFile: ${e.message}",
                e,
            )
        }

        val outDir = outputDir.get().asFile
        // Wipe stale output so a renamed/removed circuit doesn't leave a ghost
        // facade behind across incremental builds.
        outDir.deleteRecursively()
        outDir.mkdirs()
        fileSpec.writeTo(outDir)
    }

    companion object {
        internal const val TASK_NAME = "generateContractApi"
    }
}
