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
 * offline bundle (#256). The end-to-end test serves fake keys from an in-process
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

    private fun writeBuildScript(bundleWalletKeys: Boolean, baseUrl: String, version: Int) {
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
            }
            """.trimIndent(),
        )
        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "kuira-walletkeys-test""""
        )
    }

    private fun run(vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()
            .build()
}
