package com.midnight.kuira.contract

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * Wires a [GenerateContractApiTask]'s output into the consumer's Kotlin compilation via AGP's
 * `Variant.sources.kotlin.addGeneratedSourceDirectory` — so the generated `<Alias>Contract` facade
 * actually compiles into the module (and `compile<Variant>Kotlin` depends on generation).
 *
 * **One task PER VARIANT.** `addGeneratedSourceDirectory` takes ownership of the wired task's
 * `outputDir` and manages it per-variant. A single task shared across `debug` + `release` therefore
 * has its output clobbered by the second wiring and the compile-dependency edge never forms — which
 * is why the generated facade was silently produced but never on any compile classpath (no consumer
 * ever imported it, so it went unnoticed). Registering a dedicated task inside `onVariants` is the
 * AGP-sanctioned pattern and fixes it.
 *
 * This lives in its OWN class — separate from [KuiraContractPlugin] — on purpose: AGP
 * (`com.android.tools.build:gradle-api`) is a `compileOnly` dependency, so the AGP classes
 * (`AndroidComponentsExtension`, `Variant`) are NOT on the plugin's runtime classpath unless the
 * consuming build also applies an Android plugin. By isolating the AGP touch here, [KuiraContractPlugin]
 * loads without AGP on the classpath, and this class is only loaded when [register] is invoked —
 * which the plugin gates on an Android plugin being applied.
 */
internal object GeneratedSourceRegistrar {

    /**
     * For each Android variant, register a dedicated [GenerateContractApiTask] and add its output as
     * a generated Kotlin source directory. The ABI (`contractInfoProvider`) and alias are variant-
     * independent; the per-variant task is required only so AGP can own each variant's output dir.
     *
     * @return true if the AGP variant extension was found and wiring was applied.
     */
    fun register(
        project: Project,
        contractInfoProvider: Provider<RegularFile>,
        aliasProvider: Provider<String>,
    ): Boolean {
        val androidComponents = project.extensions.findByType(
            AndroidComponentsExtension::class.java,
        ) ?: return false

        androidComponents.onVariants { variant ->
            // Wire into the JAVA source container, not `kotlin`: in a Kotlin-Android module the
            // Kotlin compile consumes the java source dirs (a module's own `.kt` under src/main/java
            // is compiled that way), and `sources.kotlin.addGeneratedSourceDirectory` did NOT create
            // the compile dependency on AGP 8.13 + Kotlin 2.3.20 (the facade stayed off the
            // classpath). `sources.java` reliably feeds compile<Variant>Kotlin.
            val java = variant.sources.java ?: return@onVariants
            val taskName = "generate${variant.name.replaceFirstChar { it.uppercaseChar() }}ContractApi"
            val generateTask = project.tasks.register(taskName, GenerateContractApiTask::class.java) { task ->
                task.contractInfoFile.set(contractInfoProvider)
                task.alias.set(aliasProvider)
            }
            // AGP owns outputDir per-variant; no convention needed here.
            java.addGeneratedSourceDirectory(generateTask) { it.outputDir }
        }
        return true
    }
}
