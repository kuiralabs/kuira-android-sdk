package com.midnight.kuira.core.compact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MidnightContract.Companion.normalizeContractJs].
 *
 * The function bridges `compactc`'s ES module output to the
 * script-only QuickJs engine the SDK ships. Any regression here
 * surfaces as `SyntaxError: expecting '('` at contract deploy time
 * — the exact failure that motivated this normalizer (Kicks Phase 4,
 * 2026-05-18).
 */
class NormalizeContractJsTest {

    @Test
    fun `strips top-level import star line emitted by compactc`() {
        val input = "import * as __compactRuntime from '@midnight-ntwrk/compact-runtime';\n" +
            "const x = 1;"
        val normalized = MidnightContract.normalizeContractJs(input)
        assertFalse("import line must be gone", normalized.contains("import"))
        assertTrue("declarations after the import must survive", normalized.contains("const x = 1;"))
    }

    @Test
    fun `strips export keyword from var let const class function declarations`() {
        val input = """
            export var Phase;
            export let counter = 0;
            export const MAGIC = 42;
            export class Contract { }
            export function helper() { }
            export async function asyncHelper() { }
        """.trimIndent()
        val normalized = MidnightContract.normalizeContractJs(input)
        assertEquals(
            """
                var Phase;
                let counter = 0;
                const MAGIC = 42;
                class Contract { }
                function helper() { }
                async function asyncHelper() { }
            """.trimIndent(),
            normalized,
        )
    }

    @Test
    fun `is idempotent — preprocessed IIFE round-trips unchanged`() {
        // BBoard ships a manually-stripped IIFE; running it through the
        // normalizer again must produce identical bytes so the build
        // step stays safe to apply unconditionally.
        val input = """
            // compact-runtime loaded as global __compactRuntime
            __compactRuntime.checkRuntimeVersion('0.15.0');

            var State;
            (function (State) {
              State[State['VACANT'] = 0] = 'VACANT';
            })(State || (State = {}));

            class Contract { }
        """.trimIndent()
        assertEquals(input, MidnightContract.normalizeContractJs(input))
    }

    @Test
    fun `preserves indentation when stripping export keyword`() {
        // Nested decls (e.g. inside a top-level block — uncommon but
        // possible) must keep their indent so the surrounding scope
        // still parses.
        val input = "    export class Foo { }"
        assertEquals("    class Foo { }", MidnightContract.normalizeContractJs(input))
    }

    @Test
    fun `leaves the word 'export' alone when it is not a top-level keyword`() {
        // We only strip the `export ` prefix when it's the leading token.
        // Inside a string literal, comment, or identifier ('exporter'),
        // the substring must survive untouched.
        val input = """
            const phrase = "export var Foo;";
            // export keyword discussed in this comment must stay
            const exporter = makeExporter();
        """.trimIndent()
        assertEquals(input, MidnightContract.normalizeContractJs(input))
    }

    @Test
    fun `handles compactc penalty contract preamble end-to-end`() {
        // Mini reproduction of the actual penalty.compact V3 output
        // that triggered the bug. The first two lines are what
        // QuickJs choked on; after normalization the IIFE runtime
        // (already-loaded as a global var) supplies __compactRuntime.
        val compactcOutput = """
            import * as __compactRuntime from '@midnight-ntwrk/compact-runtime';
            __compactRuntime.checkRuntimeVersion('0.15.0');

            export var Phase;
            (function (Phase) {
              Phase[Phase['WAITING'] = 0] = 'WAITING';
            })(Phase || (Phase = {}));

            export class Contract { }
        """.trimIndent()
        val normalized = MidnightContract.normalizeContractJs(compactcOutput)
        // No module declarations remain — the file is now plain script.
        assertFalse(normalized.contains("import "))
        assertFalse(normalized.contains("export "))
        // The actual contract body survives.
        assertTrue(normalized.contains("__compactRuntime.checkRuntimeVersion('0.15.0');"))
        assertTrue(normalized.contains("var Phase;"))
        assertTrue(normalized.contains("class Contract { }"))
    }
}
