pluginManagement {
    repositories {
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
