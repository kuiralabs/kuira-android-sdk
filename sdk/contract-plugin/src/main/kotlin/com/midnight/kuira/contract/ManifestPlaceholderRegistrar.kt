package com.midnight.kuira.contract

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project

/**
 * Sets the SDK's manifest placeholders on every application variant.
 *
 * Its own class because AGP is a `compileOnly` dependency: referencing an AGP type from
 * [KuiraContractPlugin] stops Gradle decorating the plugin at all. Isolated here, the class loads
 * only when [register] is called behind an Android-plugin gate.
 */
internal object ManifestPlaceholderRegistrar {

    fun register(project: Project, values: Map<String, String>) {
        project.extensions
            .findByType(ApplicationAndroidComponentsExtension::class.java)
            ?.onVariants { variant -> values.forEach(variant.manifestPlaceholders::put) }
    }
}
