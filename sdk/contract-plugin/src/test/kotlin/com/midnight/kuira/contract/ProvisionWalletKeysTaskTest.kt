package com.midnight.kuira.contract

import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

/**
 * Coverage for [ProvisionWalletKeysTask] — the build-time half of the wallet-key
 * offline bundle. The end-to-end test serves fake keys from an in-process
 * [HttpServer] (no real network), so it proves the download → cache → stage →
 * version.txt pipeline hermetically.
 */
class ProvisionWalletKeysTaskTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var projectDir: File
    private var server: HttpServer? = null

    @Before
    fun setup() {
        projectDir = tempDir.root
    }

    @After
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `walletKeyFiles maps versioned zswap-dust and flat bls`() {
        val files = ProvisionWalletKeysTask.walletKeyFiles(9)

        assertEquals("12 zswap/dust keys + 11 BLS params", 23, files.size)
        // zswap/dust keys live under a version dir remotely, flat locally.
        assertTrue(files.contains("zswap/9/spend.prover" to "zswap/spend.prover"))
        assertTrue(files.contains("dust/9/spend.bzkir" to "dust/spend.bzkir"))
        // BLS params are universal SRS — version-independent + top-level.
        assertTrue(files.contains("bls_midnight_2p5" to "bls_midnight_2p5"))
        assertTrue(files.contains("bls_midnight_2p15" to "bls_midnight_2p15"))
        // The large sizes are opt-in — absent unless asked for by name.
        assertFalse(files.any { it.first == "bls_midnight_2p16" })
    }

    @Test
    fun `walletKeyFiles extends the bls ladder to the requested size`() {
        val files = ProvisionWalletKeysTask.walletKeyFiles(9, maxCircuitK = 17)

        assertEquals("12 zswap/dust keys + 13 BLS params", 25, files.size)
        assertTrue(files.contains("bls_midnight_2p16" to "bls_midnight_2p16"))
        assertTrue(files.contains("bls_midnight_2p17" to "bls_midnight_2p17"))
        // Only up to what was asked for: the next size is 48MB nobody requested.
        assertFalse(files.any { it.first == "bls_midnight_2p18" })
        // The baseline ladder is still complete underneath it.
        assertTrue(files.contains("bls_midnight_2p5" to "bls_midnight_2p5"))
    }

    @Test
    fun `downloads and stages the full bundle when enabled`() {
        val port = startKeyServer()
        writeBuildScript(bundleWalletKeys = true, baseUrl = "http://127.0.0.1:$port", version = 9)

        val result = run("provisionWalletKeys")

        assertEquals(TaskOutcome.SUCCESS, result.task(":provisionWalletKeys")?.outcome)
        val bundle = projectDir.resolve("src/main/assets/wallet-keys")
        assertTrue("versioned zswap key staged flat", bundle.resolve("zswap/spend.prover").readText().isNotEmpty())
        assertTrue("dust key staged", bundle.resolve("dust/spend.prover").exists())
        assertTrue("BLS param staged", bundle.resolve("bls_midnight_2p13").exists())
        assertEquals("9", bundle.resolve("version.txt").readText().trim())
        val staged = bundle.walkTopDown().filter { it.isFile && it.name != "version.txt" }.count()
        assertEquals("all 23 keys staged", 23, staged)
    }

    @Test
    fun `is skipped when bundling is off (the default)`() {
        // baseUrl deliberately unreachable — onlyIf must skip BEFORE any download.
        writeBuildScript(bundleWalletKeys = false, baseUrl = "http://127.0.0.1:1", version = 9)

        val result = run("provisionWalletKeys")

        assertEquals(TaskOutcome.SKIPPED, result.task(":provisionWalletKeys")?.outcome)
        assertFalse(projectDir.resolve("src/main/assets/wallet-keys").exists())
    }

    @Test
    fun `stages the large-circuit params when a consumer opts in`() {
        val port = startKeyServer()
        writeBuildScript(
            bundleWalletKeys = true,
            baseUrl = "http://127.0.0.1:$port",
            version = 9,
            maxCircuitK = 17,
        )

        val result = run("provisionWalletKeys")

        assertEquals(TaskOutcome.SUCCESS, result.task(":provisionWalletKeys")?.outcome)
        val bundle = projectDir.resolve("src/main/assets/wallet-keys")
        assertTrue("k=16 param staged", bundle.resolve("bls_midnight_2p16").exists())
        assertTrue("k=17 param staged", bundle.resolve("bls_midnight_2p17").exists())
        assertFalse("k=18 was not asked for", bundle.resolve("bls_midnight_2p18").exists())
        assertTrue("baseline still staged", bundle.resolve("bls_midnight_2p13").exists())
    }

    /**
     * The large sizes only ever reach a device through the APK bundle, so asking for them
     * without bundling produces an app that fails at prove time on every device. That has
     * to be a build failure, not a silently ignored property.
     */
    @Test
    fun `asking for large circuits without the bundle fails the build`() {
        writeBuildScript(
            bundleWalletKeys = false,
            baseUrl = "http://127.0.0.1:1",
            version = 9,
            maxCircuitK = 17,
        )

        val failure = runAndFail("provisionWalletKeys")

        assertTrue(
            "the error must name the setting that fixes it, got: ${failure.output}",
            failure.output.contains("bundleWalletKeys"),
        )
    }

    /**
     * Past k=18 the native prover refuses the proof before it opens the parameter file, so
     * provisioning further would add tens of megabytes toward a proof that cannot run.
     */
    @Test
    fun `refuses a size beyond what the prover accepts`() {
        val port = startKeyServer()
        writeBuildScript(
            bundleWalletKeys = true,
            baseUrl = "http://127.0.0.1:$port",
            version = 9,
            maxCircuitK = 19,
        )

        val failure = runAndFail("provisionWalletKeys")

        assertTrue(
            "the error must state the permitted range, got: ${failure.output}",
            failure.output.contains("maxCircuitK must be between"),
        )
        assertFalse(
            "nothing may be staged for a size the prover refuses",
            projectDir.resolve("src/main/assets/wallet-keys/bls_midnight_2p19").exists(),
        )
    }

    // ── fixtures ──

    /** Serves any GET with a body equal to its path, so each key has distinct, non-empty bytes. */
    private fun startKeyServer(): Int {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server = it }
        srv.createContext("/") { exchange ->
            val body = exchange.requestURI.path.trimStart('/').toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        srv.start()
        return srv.address.port
    }

    private fun writeBuildScript(
        bundleWalletKeys: Boolean,
        baseUrl: String,
        version: Int,
        maxCircuitK: Int? = null,
    ) {
        // Omitted entirely when null, so the default-behaviour tests exercise the
        // plugin's own convention rather than a value the test restated.
        val maxCircuitKLine = maxCircuitK?.let { "maxCircuitK.set($it)" } ?: ""
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.kuiralabs.contract")
            }

            // The plugin's afterEvaluate wires into preBuild; stub it (TestKit has
            // no Android plugin). provisionWalletKeys itself has no dependencies.
            tasks.register("preBuild")

            kuiraContract {
                source.set("contract/src/managed/x")
                bundleWalletKeys.set($bundleWalletKeys)
                walletKeysVersion.set($version)
                walletKeysBaseUrl.set("$baseUrl")
                $maxCircuitKLine
            }
            """.trimIndent(),
        )
        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "kuira-walletkeys-test""""
        )
    }

    private fun run(vararg args: String) = runner(*args).build()

    private fun runAndFail(vararg args: String) = runner(*args).buildAndFail()

    private fun runner(vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()
}
