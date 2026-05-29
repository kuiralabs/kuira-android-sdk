// ─────────────────────────────────────────────────────────────────────
// Kuira Contract Gradle Plugin
//
// A small Gradle plugin that replaces the hand-rolled "syncContractAssets"
// Copy task every Kuira dApp re-implements (see the Kicks and BBoard
// examples for the boilerplate this eliminates). Consumers apply:
//
//   plugins {
//     id("com.midnight.kuira.contract") version "0.1.0-alphaXX"
//   }
//   kuiraContract {
//     source.set("contract/src/managed/penalty")
//   }
//
// …and the plugin registers a `syncContractAssets` task wired into the
// Android module's `preBuild`, copying contract JS + circuit keys into
// the canonical assets layout the SDK expects at runtime.
//
// Published to Maven Central as `io.github.kuiralabs:contract-plugin`.
// Consumers also need `mavenCentral()` in their settings.gradle.kts
// `pluginManagement.repositories` block until we additionally publish
// to the Gradle Plugin Portal (alpha03+).
// ─────────────────────────────────────────────────────────────────────

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

// Note: the root `build.gradle.kts` only auto-applies vanniktech to
// modules with `com.android.library`. This module is a Gradle plugin
// (kotlin-jvm), so we apply + configure vanniktech here directly.
// `group` + `version` are inherited from gradle.properties (one source
// of truth shared with the rest of the SDK) — do not override.
apply(plugin = "com.vanniktech.maven.publish")

kotlin {
    jvmToolchain(17)
}

dependencies {
    // gradleApi() provides Gradle's classpath; Kotlin stdlib comes
    // transitively from the kotlin-jvm plugin.
    implementation(gradleApi())

    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("kuiraContract") {
            id = "com.midnight.kuira.contract"
            implementationClass = "com.midnight.kuira.contract.KuiraContractPlugin"
            displayName = "Kuira Contract Plugin"
            description = "Syncs compiled Compact contract artifacts into an Android app's assets directory at build time."
            tags.set(listOf("kuira", "midnight", "compact", "android", "zk"))
        }
    }
}

configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
    coordinates(project.group.toString(), project.name, project.version.toString())
    // GradlePlugin() configures vanniktech to publish both the main jar
    // AND the plugin marker artifact required for `plugins { id(...) }`
    // resolution. Includes sources + javadoc jars by default.
    configure(com.vanniktech.maven.publish.GradlePlugin(
        javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
        sourcesJar = true,
    ))
    pom {
        name.set("Kuira :contract-plugin")
        description.set(
            "Kuira Android SDK — Gradle plugin that wires compiled " +
                "Compact contract artifacts into an Android dApp's assets " +
                "directory. Replaces the per-app hand-rolled syncContractAssets " +
                "Copy task with a declarative `kuiraContract { source = ... }` block."
        )
        url.set("https://kuiralabs.github.io/kuira-sdk-android")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("nel349")
                name.set("nel349")
            }
        }
        scm {
            url.set("https://github.com/kuiralabs/kuira-sdk-android")
            connection.set("scm:git:git://github.com/kuiralabs/kuira-sdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/kuiralabs/kuira-sdk-android.git")
        }
    }
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent
    ) {
        signAllPublications()
    }
}
