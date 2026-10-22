package com.midnight.kuira.contract

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the circuit-size report.
 *
 * Every test builds its own fake Compact toolchain in a temp directory and never reads the
 * machine's real `~/.compact`: these run in CI, where no toolchain is installed, and a test that
 * quietly passes because it found the developer's own install would be measuring the wrong thing.
 * The stand-in `zkir` is a shell script printing captured `mock-compile-many` output, so the
 * parsing, version-selection and ceiling logic are all exercised for real.
 */
class ReportCircuitSizesTaskTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    /** Verbatim `zkir mock-compile-many` output, as captured from toolchain 0.31.1. */
    private val realOutput = """
        Mock compiling 2 circuits:
          circuit "post" (k=13, rows=4569)
          circuit "takeDown" (k=13, rows=4561)
    """.trimIndent()

    // ─── parsing ───

    @Test
    fun `parses every circuit from real mock-compile-many output`() {
        val circuits = ReportCircuitSizesTask.parseCircuits(realOutput)
        assertEquals(2, circuits.size)
        assertEquals(ReportCircuitSizesTask.Circuit("post", 13, 4569), circuits[0])
        assertEquals(ReportCircuitSizesTask.Circuit("takeDown", 13, 4561), circuits[1])
    }

    @Test
    fun `parses the single-file mock-compile form too`() {
        // `mock-compile <file>` names the path rather than the circuit; the k/rows still parse,
        // which is what the ceiling check needs.
        val out = """Mock compiling circuit "/x/y/post.zkir" (k=17, rows=83600)"""
        val circuits = ReportCircuitSizesTask.parseCircuits(out)
        assertEquals(1, circuits.size)
        assertEquals(17, circuits[0].k)
        assertEquals(83600, circuits[0].rows)
    }

    @Test
    fun `yields nothing for output with no circuit lines`() {
        assertTrue(ReportCircuitSizesTask.parseCircuits("error: no such file").isEmpty())
        assertTrue(ReportCircuitSizesTask.parseCircuits("").isEmpty())
    }

    // ─── version selection ───

    @Test
    fun `orders versions numerically, not lexicographically`() {
        val sorted = listOf("0.9.0", "0.31.1", "0.10.0", "0.24.0")
            .sortedWith(ReportCircuitSizesTask.VERSION_ORDER)
        assertEquals(listOf("0.9.0", "0.10.0", "0.24.0", "0.31.1"), sorted)
    }

    @Test
    fun `prefers the toolchain version the contract was compiled with`() {
        val compact = fakeToolchain("0.31.1", "0.35.0")
        val (zkir, version) = ReportCircuitSizesTask.findZkir(compact, requested = "0.31.1")!!
        assertEquals("0.31.1", version)
        assertTrue(zkir.canExecute())
    }

    @Test
    fun `falls back to the newest installed version when the requested one is absent`() {
        // 0.9.0 present so a lexicographic max would wrongly win over 0.35.0.
        val compact = fakeToolchain("0.9.0", "0.34.0", "0.35.0")
        val (_, version) = ReportCircuitSizesTask.findZkir(compact, requested = "0.31.1")!!
        assertEquals("0.35.0", version)
    }

    @Test
    fun `finds nothing when no toolchain is installed`() {
        assertNull(ReportCircuitSizesTask.findZkir(tempDir.newFolder(), requested = null))
    }

    @Test
    fun `ignores a version directory holding no executable zkir`() {
        val compact = tempDir.newFolder()
        File(compact, "${ReportCircuitSizesTask.VERSIONS_SUBDIR}/0.31.1/aarch64-darwin").mkdirs()
        assertNull(ReportCircuitSizesTask.findZkir(compact, requested = "0.31.1"))
    }

    // ─── contract-info.json ───

    @Test
    fun `reads compiler-version from contract-info json`() {
        val source = contractTree(compilerVersion = "0.31.1")
        assertEquals("0.31.1", ReportCircuitSizesTask.readCompilerVersion(source))
    }

    @Test
    fun `returns no compiler-version when contract-info json is absent`() {
        assertNull(ReportCircuitSizesTask.readCompilerVersion(contractTree(compilerVersion = null)))
    }

    // ─── the task ───

    @Test
    fun `reports each circuit with its k and rows`() {
        val report = runTask(
            source = contractTree(compilerVersion = "0.31.1"),
            compact = fakeToolchain("0.31.1", output = realOutput),
            maxK = 15,
        )
        assertTrue(report, report.contains("post k=13 rows=4569"))
        assertTrue(report, report.contains("takeDown k=13 rows=4561"))
        assertTrue(report, report.contains("zkir 0.31.1"))
    }

    @Test
    fun `flags a circuit above the ceiling and leaves the others unflagged`() {
        val out = """
            Mock compiling 2 circuits:
              circuit "small" (k=13, rows=4569)
              circuit "heavyHash" (k=17, rows=83600)
        """.trimIndent()
        val report = runTask(
            source = contractTree(compilerVersion = "0.31.1"),
            compact = fakeToolchain("0.31.1", output = out),
            maxK = 15,
        )
        val heavy = report.lines().single { it.contains("heavyHash") }
        val small = report.lines().single { it.contains("small") }
        assertTrue(heavy, heavy.contains("ABOVE maxCircuitK=15"))
        assertTrue(small, !small.contains("ABOVE"))
    }

    @Test
    fun `does not flag a circuit exactly at the ceiling`() {
        // zswap/spend measures k=15 and the default ceiling is 15, so an off-by-one here would
        // warn on every stock build.
        val out = """Mock compiling circuit "spend.bzkir" (k=15, rows=17540)"""
        val report = runTask(
            source = contractTree(compilerVersion = "0.31.1"),
            compact = fakeToolchain("0.31.1", output = out),
            maxK = 15,
        )
        assertTrue(report, !report.contains("ABOVE"))
    }

    @Test
    fun `says so, rather than failing, when no toolchain is installed`() {
        val report = runTask(
            source = contractTree(compilerVersion = "0.31.1"),
            compact = tempDir.newFolder(),
            maxK = 15,
        )
        assertTrue(report, report.startsWith("circuit sizes unavailable"))
        assertTrue(report, report.contains("no zkir found"))
    }

    @Test
    fun `says so, rather than failing, when the contract has no compiled IR`() {
        val report = runTask(
            source = contractTree(compilerVersion = "0.31.1", withIr = false),
            compact = fakeToolchain("0.31.1", output = realOutput),
            maxK = 15,
        )
        assertTrue(report, report.startsWith("circuit sizes unavailable"))
        assertTrue(report, report.contains("no .bzkir"))
    }

    @Test
    fun `says so, rather than failing, when zkir exits non-zero`() {
        val report = runTask(
            source = contractTree(compilerVersion = "0.31.1"),
            compact = fakeToolchain("0.31.1", output = "boom", exitCode = 2),
            maxK = 15,
        )
        assertTrue(report, report.startsWith("circuit sizes unavailable"))
        assertTrue(report, report.contains("exit 2"))
    }

    @Test
    fun `records the substituted version when the recorded one is not installed`() {
        val report = runTask(
            source = contractTree(compilerVersion = "0.31.1"),
            compact = fakeToolchain("0.35.0", output = realOutput),
            maxK = 15,
        )
        // Row counts are version-dependent, so a report that silently used another compiler would
        // be quoting numbers that do not describe the shipped keys.
        assertTrue(report, report.contains("zkir 0.35.0"))
        assertTrue(report, report.contains("compiled with 0.31.1"))
    }

    // ─── fixtures ───

    private fun runTask(source: File, compact: File, maxK: Int): String {
        val project = ProjectBuilder.builder().withProjectDir(tempDir.newFolder()).build()
        val task = project.tasks
            .register("reportCircuitSizes", ReportCircuitSizesTask::class.java)
            .get()
        task.sourceDirectory.set(source)
        task.circuitIr.from(
            project.fileTree(File(source, KuiraContractPlugin.ZKIR_SUBDIR)) {
                it.include("*.${KuiraContractPlugin.BZKIR_EXTENSION}")
            },
        )
        task.maxCircuitK.set(maxK)
        task.compactDirectory.set(compact.absolutePath)
        val report = File(tempDir.newFolder(), "circuit-sizes.txt")
        task.reportFile.set(report)
        task.report()
        return report.readText()
    }

    /** A compiled-contract layout: `compiler/contract-info.json` plus one `.bzkir` per circuit. */
    private fun contractTree(compilerVersion: String?, withIr: Boolean = true): File {
        val source = tempDir.newFolder()
        if (compilerVersion != null) {
            File(source, KuiraContractPlugin.COMPILER_SUBDIR).mkdirs()
            File(source, "${KuiraContractPlugin.COMPILER_SUBDIR}/${KuiraContractPlugin.CONTRACT_INFO_FILE}")
                .writeText(
                    """
                    {
                      "compiler-version": "$compilerVersion",
                      "language-version": "0.23.0",
                      "runtime-version": "0.16.0",
                      "circuits": []
                    }
                    """.trimIndent(),
                )
        }
        if (withIr) {
            val zkir = File(source, KuiraContractPlugin.ZKIR_SUBDIR)
            zkir.mkdirs()
            File(zkir, "post.${KuiraContractPlugin.BZKIR_EXTENSION}").writeText("ir")
            File(zkir, "takeDown.${KuiraContractPlugin.BZKIR_EXTENSION}").writeText("ir")
        }
        return source
    }

    /**
     * A toolchain root holding a stand-in `zkir` per version, under a platform subdirectory —
     * mirroring `~/.compact/versions/<version>/<platform>/zkir`, which is what the lookup walks.
     */
    private fun fakeToolchain(
        vararg versions: String,
        output: String = "",
        exitCode: Int = 0,
    ): File {
        val compact = tempDir.newFolder()
        for (v in versions) {
            val platform = File(compact, "${ReportCircuitSizesTask.VERSIONS_SUBDIR}/$v/aarch64-darwin")
            platform.mkdirs()
            val zkir = File(platform, "zkir")
            zkir.writeText("#!/bin/sh\ncat <<'ZKIREOF'\n$output\nZKIREOF\nexit $exitCode\n")
            zkir.setExecutable(true)
        }
        return compact
    }
}
