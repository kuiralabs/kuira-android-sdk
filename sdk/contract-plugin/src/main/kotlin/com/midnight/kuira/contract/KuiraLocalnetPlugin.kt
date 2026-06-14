package com.midnight.kuira.contract

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
        // Wire ahead of installDebug so deploying to a device sets up the tunnel
        // as part of the same gradle invocation. configureEach: installDebug is
        // registered by AGP, which may not have applied yet.
        project.tasks.matching { it.name == "installDebug" }.configureEach {
            it.dependsOn(reverse)
        }
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
        val sdkRoot = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: throw GradleException("adbReverseLocalnet: set ANDROID_HOME (or ANDROID_SDK_ROOT)")
        val adb = "$sdkRoot/platform-tools/adb"
        val ports = listOf(8088, 9944, 6300)

        val devicesOut = ByteArrayOutputStream()
        execOps.exec {
            it.commandLine(adb, "devices")
            it.standardOutput = devicesOut
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
            logger.lifecycle("adbReverseLocalnet: no physical devices connected — skipping (emulators use 10.0.2.2)")
            return
        }
        physicalDevices.forEach { serial ->
            logger.lifecycle("adbReverseLocalnet: forwarding ${ports.joinToString(",")} on $serial")
            ports.forEach { port ->
                execOps.exec { it.commandLine(adb, "-s", serial, "reverse", "tcp:$port", "tcp:$port") }
            }
        }
        logger.lifecycle(
            "Localnet ports forwarded on ${physicalDevices.size} device(s). " +
                "Device localhost now reaches host localhost.",
        )
    }
}
