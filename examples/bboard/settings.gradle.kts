// BBoard is the publish workflow's acceptance consumer: it builds against the
// SDK version being released, before it reaches Central. The version comes from
// -PkuiraVersion (the workflow passes it), otherwise from the monorepo's
// gradle.properties — so there's no pinned version here to keep in sync, and the
// test always exercises the exact release. mavenLocal is first because the
// workflow publishes the release there (publishToMavenLocal) just before this runs.
pluginManagement {
    val kuiraVersion = providers.gradleProperty("kuiraVersion").orNull
        ?: file("../../gradle.properties").readLines()
            .first { it.startsWith("version=") }.substringAfter('=').trim()
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.kuiralabs.contract") useVersion(kuiraVersion)
        }
    }
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "BBoard"
include(":app")
