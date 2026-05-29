import java.io.ByteArrayOutputStream

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.hilt) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    // Dokka applied at the root so the multi-module aggregate task
    // (`dokkaHtmlMultiModule`) is registered here; subprojects opt in
    // via the `plugins.withId("com.android.library")` block below.
    alias(libs.plugins.dokka)
    // Locks the public API surface to checked-in `api/<module>.api`
    // files. Breaking changes surface as PR diffs instead of consumer
    // crash logs. Configured below to only validate published modules
    // (the `feature:*` modules + `app:` are wallet-app internals).
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
}

apiValidation {
    // Don't validate non-published modules — the wallet app itself,
    // its feature screens, and the example dApps are NOT part of the
    // consumer SDK surface.
    ignoredProjects.addAll(
        listOf(
            "app",
            "balance", "dust", "onboarding", "send", "settings", // feature:*
        ),
    )
}

// ── adb reverse localnet (auto-wired before every installDebug) ──
//
// On a physical device the wallet hits `127.0.0.1` to reach the developer's
// laptop services; `adb reverse` tunnels those ports over the USB / wifi
// debugging connection so the device's localhost actually hits the host's
// localhost. Emulators don't need this (they use `10.0.2.2`) — emulator
// serials starting with `emulator-` are skipped to avoid noisy "more than
// one device" errors when both are connected.
//
// **Auto-wired:** every `installDebug` task across the project gets a
// dependency on this, so deploying to a device sets up the tunnel as part
// of the same gradle invocation — no separate `./gradlew adbReverseLocalnet`
// step needed.
//
// Lives at root scope rather than in `:app` because it's project-wide
// infrastructure consumed by every deployable module (:app, :examples:bboard,
// :examples:midnight-kicks). The SDK is a library and doesn't install.
//
// Ports tunneled (kept in sync with `NetworkConfig.LOCALNET_*_PORT`):
//   - 8088: indexer GraphQL
//   - 9944: node RPC
//   - 6300: proof server
val adbReverseLocalnet by tasks.registering {
    group = "kuira"
    description = "Forward localnet ports (8088, 9944, 6300) from each connected physical device to host via adb reverse."

    doLast {
        // Resolve adb via ANDROID_HOME / ANDROID_SDK_ROOT (root build script
        // can't see subprojects' `android.sdkDirectory`). Both env vars are
        // standard SDK locations; the second is the modern name.
        val sdkRoot = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("adbReverseLocalnet: set ANDROID_HOME (or ANDROID_SDK_ROOT)")
        val adb = "$sdkRoot/platform-tools/adb"
        val ports = listOf(8088, 9944, 6300)

        // Enumerate connected devices. `adb devices` lines look like:
        //   List of devices attached
        //   emulator-5554   device
        //   RFCY71QNXXJ     device
        val devicesOutput = ByteArrayOutputStream()
        exec {
            commandLine(adb, "devices")
            standardOutput = devicesOutput
        }
        val physicalDevices = devicesOutput.toString()
            .lineSequence()
            .drop(1) // header line
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == "device") parts[0] else null
            }
            .filterNot { it.startsWith("emulator-") }
            .toList()

        if (physicalDevices.isEmpty()) {
            println("adbReverseLocalnet: no physical devices connected — skipping (emulators use 10.0.2.2)")
            return@doLast
        }

        physicalDevices.forEach { serial ->
            println("adbReverseLocalnet: forwarding ${ports.joinToString(",")} on $serial")
            ports.forEach { port ->
                exec {
                    commandLine(adb, "-s", serial, "reverse", "tcp:$port", "tcp:$port")
                }
            }
        }
        println("Localnet ports forwarded on ${physicalDevices.size} device(s). Device localhost now reaches host localhost.")
    }
}

// Wire adbReverseLocalnet ahead of every `installDebug` task across the
// project. `projectsEvaluated` because some subprojects define their
// installDebug lazily during their own evaluation.
gradle.projectsEvaluated {
    rootProject.allprojects.forEach { p ->
        p.tasks.matching { it.name == "installDebug" }.configureEach {
            dependsOn(adbReverseLocalnet)
        }
    }
}

// ── Maven publish (alpha → Maven Central via the Central Portal) ──
//
// Every Android library module publishes its `release` variant under
// `io.github.kuiralabs:<module-name>:<version>` (artifactId = the module's last
// path segment: `:core:crypto` → `crypto`). Downstream apps with their own
// Gradle root (examples/midnight-kicks, examples/bboard, third-party dApps)
// consume from `mavenLocal()` today and from Maven Central once published.
//
// The vanniktech plugin owns the release variant + sources/javadoc jars, the
// POM metadata Central requires, signing, and the Central Portal upload — see
// RELEASE.md for the per-release ritual. Signing/upload credentials are read
// from ~/.gradle/gradle.properties (never committed); a keyless
// `publishToMavenLocal` works for local dev + dry-runs.
val kuiraGroup = "io.github.kuiralabs"
val kuiraVersion = "0.1.0-alpha01"

subprojects {
    plugins.withId("com.android.library") {
        // Only the SDK surface (core:* + sdk:*) is published. feature:* modules
        // are the wallet app's screens (balance / dust / onboarding / send /
        // settings) — wallet-app internals, NOT consumer SDK — so they don't
        // belong under io.github.kuiralabs.
        if (path.startsWith(":feature:")) return@withId

        apply(plugin = "com.vanniktech.maven.publish")
        // Dokka renders KDoc into HTML for the javadoc.jar. With this
        // plugin applied, vanniktech auto-detects Dokka and replaces its
        // empty placeholder javadoc.jar with the real one. Aggregated
        // multi-module HTML for the docs site is produced by the root
        // :dokkaGenerate task (Dokka v2).
        apply(plugin = "org.jetbrains.dokka")

        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            coordinates(kuiraGroup, project.name, kuiraVersion)
            configure(
                com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
                    variant = "release",
                    sourcesJar = true,
                    publishJavadocJar = true,
                ),
            )
            pom {
                name.set("Kuira :${project.name}")
                description.set("Kuira Android SDK — build Midnight zero-knowledge dApps on Android. Module: ${project.name}.")
                // Project landing page — the public docs site. The source
                // repo is private; consumers clicking through from Central
                // should land on something useful, not a GitHub 404.
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
                        name.set("Norman Lopez")
                    }
                }
                // SCM points at the public docs repository — the one repo
                // a consumer can actually clone. The source monorepo is
                // private; pointing scm.* at it returns 404 for everyone.
                scm {
                    url.set("https://github.com/kuiralabs/kuira-sdk-android")
                    connection.set("scm:git:git://github.com/kuiralabs/kuira-sdk-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/kuiralabs/kuira-sdk-android.git")
                }
            }
            publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
            // Sign only when a key is configured, so a keyless publishToMavenLocal
            // dry-run still works; Central uploads sign.
            if (providers.gradleProperty("signingInMemoryKey").isPresent ||
                providers.gradleProperty("signing.keyId").isPresent
            ) {
                signAllPublications()
            }
        }
    }
}

// ── IDE compatibility shim ──
//
// Older Android Studio Gradle integrations call `:<module>:prepareKotlinBuildScriptModel`
// during project sync to query the Kotlin DSL build script model. That task was
// renamed in Kotlin 2.x (the equivalent is `kotlinDslAccessorsReport`), so on
// this project (Gradle 8.13 + Kotlin 2.3.20 + AGP 8.13.2) the IDE's sync request
// fails with "Task 'prepareKotlinBuildScriptModel' not found in project ':...'".
//
// This block registers an empty stub task on every subproject so the sync
// succeeds. It's a pure compat shim — it does nothing at build time and never
// runs as part of `assemble`/`build`/etc. Remove once your Android Studio is
// updated to a version that uses the new sync API.
//
// Real fix: update Android Studio (Iguana / Hedgehog patches address this).
subprojects {
    tasks.register("prepareKotlinBuildScriptModel") {
        group = "ide"
        description = "Compat shim for older Android Studio Kotlin DSL sync. No-op."
    }
}