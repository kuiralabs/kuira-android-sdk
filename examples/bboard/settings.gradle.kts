// BBoard is the publish workflow's acceptance consumer: it builds against the
// SDK version being released BEFORE it reaches Central. So (a) the version is
// driven by `-PkuiraVersion` — the workflow passes the value from the monorepo's
// gradle.properties, so the test always exercises the exact release, never a
// stale pin — and (b) mavenLocal comes first, because the release is published
// there (`./gradlew publishToMavenLocal`) immediately before this build runs.
// The literal fallback is only used for local dev when -PkuiraVersion is unset.
pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.kuiralabs.contract") {
                useVersion(providers.gradleProperty("kuiraVersion").getOrElse("0.1.0-alpha03"))
            }
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
