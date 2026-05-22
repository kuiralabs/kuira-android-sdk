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
include(":core:compact-engine")
include(":core:connector")
include(":core:designsystem")
include(":core:auth")
include(":core:identity")
include(":core:testing")
include(":feature:balance")
include(":feature:send")
include(":feature:dust")
include(":feature:onboarding")
include(":feature:settings")
// :examples:bboard is now a standalone Gradle project under examples/bboard/
// (consumes Kuira via mavenLocal), like :examples:midnight-kicks. Not registered here.
include(":sdk:midnight-sdk")
include(":sdk:dapp-ui")
include(":sdk:wallet-seed")
