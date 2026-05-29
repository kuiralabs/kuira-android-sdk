package com.midnight.kuira.contract

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TestKit acceptance tests for the Kuira Contract Gradle Plugin.
 *
 * Each test materialises a self-contained Gradle project, applies the
 * plugin, runs the `syncContractAssets` task, and asserts the resulting
 * asset layout. No Android SDK / device required — the plugin is
 * exercised in isolation against a synthetic Android-library-shaped
 * project with the `preBuild` lifecycle task stubbed in.
 */
class KuiraContractPluginTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var projectDir: File

    @Before
    fun setup() {
        projectDir = tempDir.root
    }

    @Test
    fun `task syncContractAssets copies contract js with alias rename`() {
        writeCanonicalContractTree("penalty")
        writeBuildScript(source = "contract/src/managed/penalty")

        val result = run("syncContractAssets")

        assertEquals(TaskOutcome.SUCCESS, result.task(":syncContractAssets")?.outcome)
        val runtimeDir = projectDir.resolve("src/main/assets/runtime")
        assertTrue(
            "contract JS should be renamed to {alias}-contract.js",
            runtimeDir.resolve("penalty-contract.js").exists(),
        )
        assertEquals(
            "stub contract JS content should round-trip verbatim",
            CONTRACT_JS_STUB,
            runtimeDir.resolve("penalty-contract.js").readText(),
        )
    }

    @Test
    fun `task syncContractAssets copies prover + verifier + bzkir files`() {
        writeCanonicalContractTree("penalty")
        writeBuildScript(source = "contract/src/managed/penalty")

        run("syncContractAssets")

        val keysDir = projectDir.resolve("src/main/assets/keys")
        assertTrue("circuit1.prover should be copied", keysDir.resolve("circuit1.prover").exists())
        assertTrue("circuit1.verifier should be copied", keysDir.resolve("circuit1.verifier").exists())
        assertTrue("circuit1.bzkir should be copied", keysDir.resolve("circuit1.bzkir").exists())
    }

    @Test
    fun `alias defaults to the source dirname`() {
        writeCanonicalContractTree("snake")
        // No explicit alias — should be derived from dirname.
        writeBuildScript(source = "contract/src/managed/snake")

        run("syncContractAssets")

        assertTrue(
            projectDir.resolve("src/main/assets/runtime/snake-contract.js").exists(),
        )
    }

    @Test
    fun `explicit alias overrides the dirname default`() {
        writeCanonicalContractTree("penalty")
        writeBuildScript(
            source = "contract/src/managed/penalty",
            alias = "custom",
        )

        run("syncContractAssets")

        assertTrue(
            "explicit alias should win over the dirname default",
            projectDir.resolve("src/main/assets/runtime/custom-contract.js").exists(),
        )
    }

    @Test
    fun `failure when source path does not exist surfaces a helpful message`() {
        // Don't write any contract files — source path is missing.
        writeBuildScript(source = "contract/src/managed/nonexistent")

        val result = runExpectingFailure("syncContractAssets")

        val output = result.output
        assertTrue(
            "error should name the missing path: $output",
            output.contains("nonexistent"),
        )
        assertTrue(
            "error should hint at how to fix it: $output",
            output.contains("compile your contract") || output.contains("npm run compact"),
        )
    }

    @Test
    fun `syncContractAssets is wired into the preBuild lifecycle`() {
        writeCanonicalContractTree("penalty")
        writeBuildScript(source = "contract/src/managed/penalty")

        val result = run("preBuild")

        // preBuild itself should run, AND it should have triggered our task.
        assertNotNull(
            "syncContractAssets should be in the preBuild graph",
            result.task(":syncContractAssets"),
        )
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    /**
     * Writes the canonical compactc output layout that every Kuira dApp
     * has under `contract/src/managed/<name>`:
     *
     *   contract/index.js
     *   keys/<circuit>.prover
     *   keys/<circuit>.verifier
     *   zkir/<circuit>.bzkir
     */
    private fun writeCanonicalContractTree(name: String) {
        val managed = projectDir.resolve("contract/src/managed/$name")
        managed.resolve("contract").mkdirs()
        managed.resolve("keys").mkdirs()
        managed.resolve("zkir").mkdirs()

        managed.resolve("contract/index.js").writeText(CONTRACT_JS_STUB)
        managed.resolve("keys/circuit1.prover").writeText(STUB_PROVER)
        managed.resolve("keys/circuit1.verifier").writeText(STUB_VERIFIER)
        managed.resolve("zkir/circuit1.bzkir").writeText(STUB_BZKIR)
    }

    private fun writeBuildScript(source: String, alias: String? = null) {
        val aliasLine = alias?.let { "    alias.set(\"$it\")" } ?: ""
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.midnight.kuira.contract")
            }

            // The plugin needs a `preBuild` task to wire into — in a real
            // consumer project Android plugins register it. Here we just
            // declare an empty stand-in so we can verify the dependency
            // chain without bringing the Android plugin into TestKit.
            tasks.register("preBuild")

            kuiraContract {
                source.set("$source")
                $aliasLine
            }
            """.trimIndent(),
        )
        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "kuira-contract-test"
            """.trimIndent(),
        )
    }

    private fun run(vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()
            .build()

    private fun runExpectingFailure(vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()
            .buildAndFail()

    companion object {
        private const val CONTRACT_JS_STUB = "// stub contract\nexports.foo = 42;\n"
        private const val STUB_PROVER = "stub-prover-bytes"
        private const val STUB_VERIFIER = "stub-verifier-bytes"
        private const val STUB_BZKIR = "stub-bzkir-bytes"
    }
}
