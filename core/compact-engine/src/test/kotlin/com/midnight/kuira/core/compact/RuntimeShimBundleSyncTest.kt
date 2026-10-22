package com.midnight.kuira.core.compact

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `onchain-runtime-shim.js` and `compact-runtime-iife.js` hold the SAME code twice, and only the
 * second one runs.
 *
 * The shim is ours — it replaces Midnight's WASM onchain-runtime with calls into our Rust FFI. The
 * bundle is vendored upstream compact-runtime with the shim's code welded into it, and
 * [LedgerEvaluator] and [CircuitExecutor] load only the bundle. So a change made to the shim alone
 * has no effect at runtime while looking entirely applied: the build is green, the install
 * succeeds, and the code simply is not there.
 *
 * Keeping the two in step has always been manual — every commit that touched the shim edited the
 * bundle in the same commit — and it has held, at v0.1.0-alpha05 and since. This test is the part
 * that was missing: discipline that nothing checks eventually lapses, and the symptom of a lapse
 * appears far from its cause, as a circuit failing for a reason the source says is impossible.
 *
 * **What this proves:** every class, function, native hook and error message the shim declares is
 * also present in the bundle. That is what a forgotten re-mirror looks like.
 *
 * **What it does not prove:** semantic equality. Reordered logic or a changed numeric constant with
 * no new symbol would pass. Catching that needs the bundle to be a generated artifact rather than a
 * second hand-maintained copy, which is a larger change than this check.
 */
class RuntimeShimBundleSyncTest {

    private val shim: String by lazy { runtimeAsset("onchain-runtime-shim.js") }
    private val bundle: String by lazy { runtimeAsset("compact-runtime-iife.js") }

    @Test
    fun everyExportedClassReachedTheBundle() =
        assertMirrored("exported class", Regex("""export class (\w+)""").findAll(shim).symbols())

    @Test
    fun everyExportedFunctionReachedTheBundle() =
        assertMirrored("exported function", Regex("""export function (\w+)""").findAll(shim).symbols())

    /**
     * The `__native_*` hooks are the Kotlin/Rust boundary. A hook named in the shim but absent from
     * the bundle is a binding the running code never calls, and one named in neither is a
     * [CircuitExecutor] registration with no consumer.
     */
    @Test
    fun everyNativeHookReachedTheBundle() =
        assertMirrored("__native_ hook", Regex("""(__native_\w+)""").findAll(shim).symbols())

    /**
     * Error and guard messages are the most useful tripwire available: they are long, unique, and a
     * new one is exactly what a shim-only edit introduces. esbuild rewrites the quoting and escapes
     * non-ASCII, so each is compared against those forms too — the bundle carries the em-dash in
     * "runProgram: not yet implemented — ..." as an escape, not as the character.
     */
    @Test
    fun everyLongStringLiteralReachedTheBundle() {
        // Comments are stripped first: esbuild drops them, and prose apostrophes in them ("the
        // tree's height") otherwise read as string delimiters and produce phantom literals.
        val code = shim
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(?m)^[ \t]*//.*$"""), "")
            .replace(Regex("""(?m)[ \t]{2,}//.*$"""), "")
        val literals = Regex("""'((?:[^'\\\n]|\\.){14,})'""").findAll(code).symbols()
        assertMirrored("string literal", literals)
    }

    @Test
    fun theShimIsNotEmptyAndTheBundleContainsIt() {
        // A guard on the guards: if either file failed to load, every assertion above would pass
        // vacuously on an empty symbol set and report sync that was never checked.
        assertTrue("shim looks truncated: ${shim.length} bytes", shim.length > 10_000)
        assertTrue("bundle looks truncated: ${bundle.length} bytes", bundle.length > 50_000)
        assertTrue(
            "the bundle does not contain the shim at all — it should carry StateValue and the " +
                "__native_ hooks that replace the WASM onchain-runtime",
            bundle.contains("StateValue") && bundle.contains("__native_"),
        )
    }

    private fun Sequence<MatchResult>.symbols(): List<String> =
        map { it.groupValues[1] }.distinct().toList()

    private fun assertMirrored(kind: String, names: List<String>) {
        assertTrue("found no $kind in the shim — the extraction is broken, not the sync", names.isNotEmpty())
        val missing = names.filterNot { name -> esbuildForms(name).any(bundle::contains) }
        assertTrue(
            "${missing.size} $kind(s) exist in onchain-runtime-shim.js but NOT in " +
                "compact-runtime-iife.js, which is the file that actually runs. Mirror the change " +
                "into the bundle in the same commit, in the bundle's own style (double quotes, the " +
                "`_StateValue` internal name), then re-run this test. Missing: $missing",
            missing.isEmpty(),
        )
    }

    /** The literal plus the forms esbuild may have written it as (non-ASCII escaped, either case). */
    private fun esbuildForms(literal: String): List<String> = buildList {
        add(literal)
        for (fmt in listOf("\\u%04x", "\\u%04X")) {
            add(literal.map { c -> if (c.code > 0x7F) fmt.format(c.code) else c.toString() }.joinToString(""))
        }
    }.distinct()

    private fun runtimeAsset(name: String): String {
        val file = File(runtimeDir, name)
        // Fails rather than skips: a sync check that opts out when it cannot find its inputs
        // reports the same green as one that verified them.
        assertTrue("missing runtime asset ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val runtimeDir: File by lazy {
        // Unit tests may be invoked with the module directory or the repo root as the working
        // directory, so try both shapes while walking up rather than pinning one relative depth.
        val shapes = listOf("src/main/assets/runtime", "core/compact-engine/src/main/assets/runtime")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            shapes.map { File(dir, it) }.firstOrNull { it.isDirectory }?.let { return@lazy it }
            dir = dir.parentFile
        }
        throw AssertionError(
            "could not locate the runtime assets from ${System.getProperty("user.dir")} " +
                "(tried $shapes while walking up). This check must RUN, not be skipped."
        )
    }
}
