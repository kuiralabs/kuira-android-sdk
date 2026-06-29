package com.midnight.kuira.contract

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Registers the [GenerateContractApiTask] output as a Kotlin source root on every
 * Android variant, via AGP's `Variant.sources.kotlin.addGeneratedSourceDirectory`.
 *
 * This lives in its OWN class — separate from [KuiraContractPlugin] — on purpose:
 * AGP (`com.android.tools.build:gradle-api`) is a `compileOnly` dependency, so the
 * AGP classes (`AndroidComponentsExtension`, `Variant`) are NOT on the plugin's
 * runtime classpath unless the consuming build also applies an Android plugin
 * (which brings AGP). If [KuiraContractPlugin] referenced these types directly,
 * Gradle's plugin-class decoration would eagerly try to load `Variant` (it appears
 * in the `onVariants` lambda signature) and fail to apply the plugin in any
 * non-Android build — including the plugin's own TestKit suite.
 *
 * By isolating the AGP touch here, [KuiraContractPlugin] loads without AGP on the
 * classpath, and this class is only loaded (and its AGP references resolved) when
 * [register] is actually invoked — which the plugin gates on the AGP extension
 * being present.
 */
internal object GeneratedSourceRegistrar {

    /**
     * Wire [generateTask]'s output into every variant's Kotlin sources. AGP owns
     * the output directory location (it sets the task's `outputDir` convention),
     * so the generated facade compiles into the consumer module automatically.
     *
     * @return true if the AGP variant extension was found and wiring was applied.
     */
    fun register(project: Project, generateTask: TaskProvider<GenerateContractApiTask>): Boolean {
        val androidComponents = project.extensions.findByType(
            AndroidComponentsExtension::class.java,
        ) ?: return false

        androidComponents.onVariants { variant ->
            variant.sources.kotlin?.addGeneratedSourceDirectory(generateTask) { it.outputDir }
        }
        return true
    }
}
