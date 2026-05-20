package com.midnight.kuira.core.compact

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Structural test that pins the witness JS template to a single
 * location in [CircuitExecutor].
 *
 * **Why this exists:** the witness template defines a wire format
 * between Kotlin (`WitnessResult.toJsArrayString`) and the JS
 * runtime (parsing inside `quickJs { … }`). It was historically
 * duplicated in two places — `executeConstructor` and
 * `buildCircuitJs` — and a format change to one without the other
 * caused a `RangeError: cannot convert NaN or Infinity to BigInt`
 * at deploy time (commit 36c9e77). Both have been collapsed to a
 * single `buildWitnessEntriesJs(...)` helper.
 *
 * **What this test asserts:** the witness function template literal
 * (anchored by the distinctive `__witness_$name` Kotlin
 * interpolation marker) appears exactly once in `CircuitExecutor.kt`.
 * If a future refactor re-inlines the template at a second call
 * site, this test fails fast — long before the runtime
 * NaN/BigInt error reaches a user.
 *
 * **Why this isn't a perfect test:** if someone copies the body
 * out of `buildWitnessEntriesJs` without the distinctive marker,
 * the test won't catch it. Pairing it with the
 * JS-runtime round-trip test gives full coverage; this one is
 * cheap insurance against the specific re-duplication regression
 * we just hit.
 */
class WitnessTemplateDryTest {

    @Test
    fun `__witness_$name template appears exactly once in CircuitExecutor`() {
        val source = locateCircuitExecutorSource()
        // The distinctive marker — Kotlin string interpolation of the
        // witness name into the JS function body. Any future re-
        // inline of the template would either keep this marker (and
        // get caught here) or rename it (and fail the runtime
        // contract). Either way, the redirect-to-helper invariant
        // is enforced at compile-or-test time, not deploy-time.
        val marker = "\$name: function(witnessContext)"
        val occurrences = source.findAll(marker)
        assertEquals(
            "Witness JS template marker `$marker` must appear exactly once in CircuitExecutor.kt " +
                "(only inside buildWitnessEntriesJs). Found $occurrences. " +
                "If you re-inlined the template at a call site, route it through buildWitnessEntriesJs() instead.",
            1,
            occurrences,
        )
    }

    private fun locateCircuitExecutorSource(): String {
        // The test JVM's working dir is `core/compact-engine/`. The
        // sibling main-source tree is at `src/main/kotlin/…`. If a
        // future Gradle reorganization moves the file, this throws
        // a clear "source not found" error rather than silently
        // skipping the assertion.
        val path = "src/main/kotlin/com/midnight/kuira/core/compact/CircuitExecutor.kt"
        val file = File(path)
        check(file.exists()) {
            "Cannot read $path from test cwd ${File(".").absolutePath} — " +
                "Gradle module layout may have changed; update the path or rewrite this test."
        }
        return file.readText()
    }

    private fun String.findAll(needle: String): Int {
        var idx = 0
        var count = 0
        while (true) {
            val next = indexOf(needle, idx)
            if (next < 0) return count
            count++
            idx = next + 1
        }
    }
}
