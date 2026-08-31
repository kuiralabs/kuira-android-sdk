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
// The standalone wallet app (:app) and its feature:* screens were the legacy surface —
// superseded by the SDK's own wallet UI in :sdk:dapp-ui (the published product). Removed.
// :examples:bboard is now a standalone Gradle project under examples/bboard/
// (consumes Kuira via mavenLocal), like :examples:midnight-kicks. Not registered here.
include(":sdk:midnight-sdk")
include(":sdk:dapp-ui")
include(":sdk:wallet-seed")
include(":sdk:wallet-runtime")
include(":sdk:contract-plugin")
