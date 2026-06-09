pluginManagement {
    repositories {
        // Kuira's contract plugin is published to mavenLocal by the parent
        // build (`./gradlew publishToMavenLocal`); its plugin marker isn't on
        // the Plugin Portal, so mavenLocal must be a plugin repo too — not only
        // a dependency repo below. Without this the `com.midnight.kuira.contract`
        // plugin can't resolve.
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Kuira SDK is published from the parent build via
        // `./gradlew publishToMavenLocal`. POMs include transitive deps,
        // so BBoard consumes Kuira as plain Maven coords (no project refs).
        mavenLocal()
    }
}

rootProject.name = "BBoard"
include(":app")
