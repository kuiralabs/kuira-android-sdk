package com.midnight.kuira.sdk

import com.midnight.kuira.core.ledger.api.RuntimeVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [VersionCoherence.evaluate] — the pure warn-vs-fail decision point of the
 * ledger-version pre-flight (#16). The matrix is known to LAG the live node, so the contract is:
 * a known-compatible specVersion is Coherent; anything else is a non-blocking Warning (under the
 * default policy) whose message names BOTH versions + the remedy. No I/O here — the SDK does the
 * RPC/native read and passes the values in.
 */
class VersionCoherenceTest {

    private fun node(spec: Long, name: String = "midnight") =
        RuntimeVersion(specName = name, specVersion = spec)

    @Test
    fun `known-compatible specVersion is coherent`() {
        val compatible = VersionCoherence.KNOWN_COMPATIBLE_SPEC_VERSIONS.first()
        val result = VersionCoherence.evaluate(node(compatible), "8.0.3")
        assertTrue(result is VersionCoherence.Result.Coherent)
        result as VersionCoherence.Result.Coherent
        assertEquals("8.0.3", result.clientLedgerVersion)
        assertEquals(compatible, result.nodeSpecVersion)
    }

    @Test
    fun `unknown specVersion does NOT hard-block under the default warn policy`() {
        // The default policy is HARD_FAIL_ON_SKEW = false: a skew must surface as a Warning, never
        // an Incompatible (which would block a working node the lagging matrix hasn't caught up to).
        assertTrue(
            "default policy must be non-blocking",
            !VersionCoherence.HARD_FAIL_ON_SKEW,
        )
        val skewed = (VersionCoherence.KNOWN_COMPATIBLE_SPEC_VERSIONS.maxOrNull() ?: 0L) + 999L
        val result = VersionCoherence.evaluate(node(skewed), "8.0.3")
        assertTrue(
            "an unknown specVersion under the warn policy must be a Warning, got $result",
            result is VersionCoherence.Result.Warning,
        )
    }

    @Test
    fun `warning message names both versions and the remedy`() {
        val skewed = (VersionCoherence.KNOWN_COMPATIBLE_SPEC_VERSIONS.maxOrNull() ?: 0L) + 999L
        val result = VersionCoherence.evaluate(node(skewed, name = "midnight"), "8.0.3")
        val message = (result as VersionCoherence.Result.Warning).message
        assertTrue("must name the client version", message.contains("8.0.3"))
        assertTrue("must name the node specVersion", message.contains(skewed.toString()))
        assertTrue("must name the node specName", message.contains("midnight"))
        assertTrue("must state the remedy", message.contains("update your localnet/node"))
    }
}
