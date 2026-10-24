package com.midnight.kuira.contract

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project

/**
 * Sets the `blockfrostProjectId` manifest placeholder on every application variant.
 *
 * Its own class for the same reason as [GeneratedSourceRegistrar]: AGP is a `compileOnly`
 * dependency, so referencing an AGP type from [KuiraContractPlugin] stops Gradle decorating the
 * plugin at all. Isolated here, the class loads only when [register] is called behind an
 * Android-plugin gate.
 */
internal object BlockfrostPlaceholderRegistrar {

    fun register(project: Project, placeholder: String, projectId: String) {
        project.extensions
            .findByType(ApplicationAndroidComponentsExtension::class.java)
            ?.onVariants { variant -> variant.manifestPlaceholders.put(placeholder, projectId) }
    }
}
