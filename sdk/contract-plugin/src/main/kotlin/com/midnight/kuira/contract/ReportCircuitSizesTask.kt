package com.midnight.kuira.contract

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Reports every circuit's size — `k` and row count — for a compiled contract, and warns when one
 * exceeds [KuiraContractExtension.maxCircuitK].
 *
 * Without this, a developer learns their circuit is too large the first time proving fails on a
 * phone, and the error they get there names a missing BLS parameter file rather than the circuit.
 * `k` selects the `bls_midnight_2p<k>` the prover needs, so a circuit above the provisioned
 * ceiling cannot prove on any device however capable, and proving cost roughly doubles per step of
 * `k`. Both facts are knowable at build time from the compiled IR.
 *
 * The numbers come from `zkir`, which ships INSIDE the Compact toolchain — compiling a contract at
 * all requires it, so this adds no dependency a consumer does not already have: no Rust
 * toolchain, no bundled native helper, no checked-in table to go stale. Reimplementing the cost
 * model on the JVM was the alternative and was rejected: `k` and rows are derived from the IR's
 * instruction list by the ledger's own model, so a second implementation would drift from it on
 * every ledger bump.
 *
 * Which `zkir` matters. Row counts are version-dependent — the same bboard circuit measures
 * rows=4569 under 0.31.1 and rows=4550 under 0.34.0/0.35.0 — so this prefers the version the
 * contract records as `compiler-version`, which is the one that produced its keys. Falling back to
 * the newest installed version is still useful (`k` agreed across every version measured) but it
 * says so, because the row counts then describe a different compiler than the one that built the
 * artifacts.
 *
 * Missing tooling is reported, never fatal: a consumer who compiled elsewhere, or builds on a
 * machine without the toolchain, still gets a working build plus one line naming what is
 * unavailable.
 *
 * A typed task, so it captures only its declared properties and never the Gradle
 * [org.gradle.api.Project] — see [ValidateKuiraContractSourceTask] for why that matters to the
 * configuration cache. The report is a declared output and the `.bzkir` files are declared inputs,
 * so the task is genuinely up-to-date when neither the circuits nor the ceiling changed rather
 * than shelling out on every build.
 */
abstract class ReportCircuitSizesTask : DefaultTask() {

    /**
     * The compiled contract directory. `@Internal`, because what this task is actually sensitive
     * to is the IR declared in [circuitIr]; this only supplies the paths handed to `zkir` and the
     * `compiler/contract-info.json` read for the toolchain version.
     */
    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    /**
     * The contract's `.bzkir` files — the real input, since recompiling a circuit changes them,
     * which is exactly when sizes need reporting again.
     *
     * A file COLLECTION rather than an `@InputDirectory` on `zkir/`: a contract that has not been
     * compiled yet has no such directory, and Gradle fails a missing `@InputDirectory` before the
     * task action can run, which would turn this diagnostic into a build-breaking error on the
     * exact setup it is meant to help. An absent collection is simply empty.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val circuitIr: ConfigurableFileCollection

    /** Ceiling to flag against — [KuiraContractExtension.maxCircuitK]. */
    @get:Input
    abstract val maxCircuitK: Property<Int>

    /**
     * Root of the Compact toolchain install, `$COMPACT_DIRECTORY` or `~/.compact`. Resolved at
     * configuration time and passed in, so the task action reads no ambient environment.
     */
    @get:Input
    abstract val compactDirectory: Property<String>

    /** The report, left on disk so the numbers can be read without re-running the build. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    init {
        group = "verification"
        description = "Report each Compact circuit's size (k, rows) and warn when one exceeds " +
            "kuiraContract.maxCircuitK."
    }

    @TaskAction
    fun report() {
        val ceiling = maxCircuitK.get()
        if (circuitIr.isEmpty) {
            note("no .bzkir files under ${sourceDirectory.get().asFile}/${KuiraContractPlugin.ZKIR_SUBDIR}")
            return
        }

        val requested = readCompilerVersion(sourceDirectory.get().asFile)
        val resolved = findZkir(File(compactDirectory.get()), requested)
        if (resolved == null) {
            note(
                "no zkir found under ${compactDirectory.get()}/versions. Install the Compact " +
                    "toolchain (it ships zkir) to see each circuit's k and row count, and to be " +
                    "warned before a circuit grows past kuiraContract.maxCircuitK=$ceiling",
            )
            return
        }

        val (zkir, version) = resolved
        val output = runCatching { runZkir(zkir, zkirDir()) }.getOrElse { e ->
            note("zkir ($zkir) failed: ${e.message}")
            return
        }

        val circuits = parseCircuits(output)
        if (circuits.isEmpty()) {
            note("zkir reported no circuits in ${zkirDir()}; its output was:\n$output")
            return
        }

        // Name the compiler whenever it is NOT the one the contract was built with, because the
        // row counts then belong to a different compiler than the keys being shipped.
        val mismatched = requested != null && requested != version
        val provenance = if (mismatched) {
            "zkir $version — contract was compiled with $requested, and row counts are " +
                "version-dependent, so these describe a different compiler"
        } else {
            "zkir $version"
        }

        logger.lifecycle("[$PLUGIN_NAME] Circuit sizes ($provenance):")
        val report = StringBuilder("circuit sizes from $provenance; maxCircuitK=$ceiling\n")
        for (c in circuits) {
            val flag = if (c.k > ceiling) "   <-- ABOVE maxCircuitK=$ceiling" else ""
            logger.lifecycle("    %-24s k=%-3d rows=%d%s".format(c.name, c.k, c.rows, flag))
            report.append("  ${c.name} k=${c.k} rows=${c.rows}$flag\n")
        }
        write(report.toString())

        if (mismatched) {
            logger.warn(
                "[$PLUGIN_NAME] zkir $requested is not installed, so circuit sizes were measured " +
                    "with $version instead. k has matched across every version measured, but row " +
                    "counts have not — install the matching toolchain version for figures that " +
                    "describe the keys this contract actually ships.",
            )
        }

        val over = circuits.filter { it.k > ceiling }
        if (over.isNotEmpty()) {
            val needed = over.maxOf { it.k }
            logger.warn(
                "[$PLUGIN_NAME] ${over.size} circuit(s) exceed kuiraContract.maxCircuitK=$ceiling: " +
                    over.joinToString(", ") { "${it.name} (k=${it.k})" } +
                    ". Proving these needs bls_midnight_2p$needed on the device, which this app " +
                    "does not provision, so proving will FAIL at runtime however capable the " +
                    "hardware is. Either raise kuiraContract.maxCircuitK to $needed — each step " +
                    "roughly doubles both the parameter file size and the proving cost — or " +
                    "reduce the circuit.",
            )
        }
    }

    private fun zkirDir(): File =
        sourceDirectory.get().asFile.resolve(KuiraContractPlugin.ZKIR_SUBDIR)

    /** Reported, never fatal: the build is fine, only this diagnostic is unavailable. */
    private fun note(why: String) {
        logger.lifecycle("[$PLUGIN_NAME] Circuit sizes unavailable: $why.")
        write("circuit sizes unavailable: $why\n")
    }

    private fun write(text: String) {
        val out = reportFile.get().asFile
        out.parentFile?.mkdirs()
        out.writeText(text)
    }

    private fun runZkir(zkir: File, dir: File): String {
        val proc = ProcessBuilder(zkir.absolutePath, ZKIR_MOCK_COMPILE_MANY, dir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        val exit = proc.waitFor()
        if (exit != 0) {
            throw IllegalStateException("exit $exit: ${output.lines().lastOrNull().orEmpty()}")
        }
        return output
    }

    internal data class Circuit(val name: String, val k: Int, val rows: Int)

    internal companion object {
        internal const val TASK_NAME = "reportCircuitSizes"
        private const val PLUGIN_NAME = "kuiraContract"
        private const val ZKIR_EXECUTABLE = "zkir"
        private const val ZKIR_MOCK_COMPILE_MANY = "mock-compile-many"
        internal const val VERSIONS_SUBDIR = "versions"

        /** `  circuit "post" (k=13, rows=4569)` — `mock-compile-many`'s per-circuit line. */
        private val CIRCUIT_LINE = Regex("""circuit\s+"([^"]+)"\s*\(k=(\d+),\s*rows=(\d+)\)""")
        private val COMPILER_VERSION_REGEX = Regex(""""compiler-version"\s*:\s*"([^"]+)"""")

        internal fun parseCircuits(output: String): List<Circuit> =
            CIRCUIT_LINE.findAll(output).map { m ->
                Circuit(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.toList()

        /** Absent for older compactc output that does not emit `contract-info.json`. */
        internal fun readCompilerVersion(sourceDir: File): String? {
            val info = sourceDir.resolve(
                "${KuiraContractPlugin.COMPILER_SUBDIR}/${KuiraContractPlugin.CONTRACT_INFO_FILE}",
            )
            if (!info.isFile) return null
            val text = runCatching { info.readText() }.getOrNull() ?: return null
            return COMPILER_VERSION_REGEX.find(text)?.groupValues?.get(1)
        }

        /**
         * Locates a `zkir` under `<compactDir>/versions/<version>/<platform>/`, preferring
         * [requested] and otherwise taking the newest installed.
         *
         * The platform directory is discovered rather than derived: its name varies by host
         * (`aarch64-darwin`, …) and an install holds only the one it needs, so looking for the
         * executable beats maintaining a host-triple table that silently fails on whichever hosts
         * nobody tested.
         */
        internal fun findZkir(compactDir: File, requested: String?): Pair<File, String>? {
            val versions = File(compactDir, VERSIONS_SUBDIR).listFiles()
                ?.filter { it.isDirectory }
                ?: return null
            requested?.let { want ->
                versions.firstOrNull { it.name == want }?.let { dir ->
                    zkirIn(dir)?.let { return it to dir.name }
                }
            }
            return versions.sortedWith(compareBy(VERSION_ORDER) { it.name })
                .asReversed()
                .firstNotNullOfOrNull { dir -> zkirIn(dir)?.let { it to dir.name } }
        }

        private fun zkirIn(versionDir: File): File? =
            versionDir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { File(it, ZKIR_EXECUTABLE) }
                ?.firstOrNull { it.canExecute() }

        /** Orders `0.9.0` below `0.31.1`, which a lexicographic sort gets backwards. */
        internal val VERSION_ORDER = Comparator<String> { a, b ->
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return@Comparator c
            }
            0
        }
    }
}
