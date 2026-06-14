package com.midnight.kuira.contract

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * `io.github.kuiralabs.localnet` — develop a Kuira dApp against a localnet on a
 * PHYSICAL device with no manual `adb reverse`.
 *
 * On a physical device the wallet reaches the developer's laptop services via
 * `127.0.0.1`; that only works if the localnet ports are tunnelled over the
 * debug bridge. This plugin registers an `adbReverseLocalnet` task and wires it
 * ahead of `installDebug`, so deploying to a device forwards the ports
 * automatically. Emulators use `10.0.2.2` and are skipped.
 *
 * Apply once, no configuration:
 * ```
 * plugins { id("io.github.kuiralabs.localnet") version "<sdk-version>" }
 * ```
 *
 * Replaces the ~60-line `adbReverseLocalnet` task each dApp otherwise copies
 * into its root build script. Uses injected [ExecOperations] (the API that
 * survives Gradle 9's removal of `Project.exec` at execution time).
 *
 * Ports (kept in sync with the SDK's `NetworkConfig.LOCALNET_*_PORT`):
 *   - 8088 indexer GraphQL
 *   - 9944 node RPC
 *   - 6300 proof server
 */
class KuiraLocalnetPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val reverse = project.tasks.register(
            "adbReverseLocalnet",
            AdbReverseLocalnetTask::class.java,
        ) { task ->
            task.group = "kuira"
            task.description =
                "Forward localnet ports (8088, 9944, 6300) from each connected " +
                "physical device to host via adb reverse."
        }
        // Wire ahead of BOTH installDebug and assembleDebug. `installDebug` covers
        // a CLI `./gradlew installDebug`, but Android Studio's Run/Deploy bypasses
        // that task — it builds then installs the APK itself — so the only Gradle
        // task it reliably runs first is the variant assemble (via "Gradle-aware
        // Make"). Hooking assembleDebug means the tunnel is (re)established on every
        // AS deploy too, not just CLI installs. The task is a no-op without a
        // connected physical device, so it's cheap on ordinary builds.
        project.tasks.matching { it.name == "installDebug" || it.name == "assembleDebug" }
            .configureEach { it.dependsOn(reverse) }
    }
}

/**
 * Forwards the localnet ports from each connected physical device to the host.
 * No-op (logs + returns) when only emulators are connected.
 */
abstract class AdbReverseLocalnetTask : DefaultTask() {

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun forward() {
        // Fail-safe throughout: this runs on every assembleDebug, so a missing
        // SDK / adb / device must SKIP, never break the build.
        val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (sdkRoot == null) {
            logger.info("adbReverseLocalnet: ANDROID_HOME / ANDROID_SDK_ROOT not set — skipping")
            return
        }
        val adb = "$sdkRoot/platform-tools/adb"
        val ports = listOf(8088, 9944, 6300)

        val devicesOut = ByteArrayOutputStream()
        try {
            execOps.exec {
                it.commandLine(adb, "devices")
                it.standardOutput = devicesOut
                it.isIgnoreExitValue = true
            }
        } catch (e: Exception) {
            logger.info("adbReverseLocalnet: could not run '$adb devices' — skipping: ${e.message}")
            return
        }
        // `adb devices` lines look like: "<serial>\t<state>"; the first line is
        // a header. Keep only `device`-state serials that aren't emulators.
        val physicalDevices = devicesOut.toString()
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == "device") parts[0] else null
            }
            .filterNot { it.startsWith("emulator-") }
            .toList()

        if (physicalDevices.isEmpty()) {
            logger.info("adbReverseLocalnet: no physical devices connected — skipping (emulators use 10.0.2.2)")
            return
        }
        physicalDevices.forEach { serial ->
            ports.forEach { port ->
                try {
                    execOps.exec {
                        it.commandLine(adb, "-s", serial, "reverse", "tcp:$port", "tcp:$port")
                        it.isIgnoreExitValue = true
                    }
                } catch (e: Exception) {
                    logger.warn("adbReverseLocalnet: reverse tcp:$port failed on $serial — ${e.message}")
                }
            }
            logger.lifecycle(
                "adbReverseLocalnet: forwarded ${ports.joinToString(",")} on $serial — " +
                    "device localhost now reaches host localhost.",
            )
        }
    }
}
