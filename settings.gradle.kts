pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kuira"
include(":app")
include(":core:crypto")
include(":core:indexer")
include(":core:ledger")
include(":core:network")
include(":core:wallet")
include(":core:testing")
include(":feature:balance")
include(":feature:send")
