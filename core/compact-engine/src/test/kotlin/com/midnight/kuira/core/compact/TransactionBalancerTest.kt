package com.midnight.kuira.core.compact

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TransactionBalancerTest {

    // ─��� Interface contract ──

    @Test
    fun `DAppConnectorClient implements TransactionBalancer`() {
        val client = DAppConnectorClient("ws://localhost:9932")
        assertTrue(
            "DAppConnectorClient should implement TransactionBalancer",
            client is TransactionBalancer,
        )
    }

    @Test
    fun `custom TransactionBalancer implementation works`() = runTest {
        val balancer = object : TransactionBalancer {
            var lastBalanced: String? = null
            var lastSubmitted: String? = null

            override suspend fun balanceTransaction(provenTxHex: String): String {
                lastBalanced = provenTxHex
                return "balanced_$provenTxHex"
            }

            override suspend fun submitTransaction(balancedTxHex: String) {
                lastSubmitted = balancedTxHex
            }
        }

        val balanced = balancer.balanceTransaction("abc123")
        assertEquals("balanced_abc123", balanced)
        assertEquals("abc123", balancer.lastBalanced)

        balancer.submitTransaction(balanced)
        assertEquals("balanced_abc123", balancer.lastSubmitted)
    }

    // ── DAppConnectorClient preserves payFees overload ──

    @Test
    fun `DAppConnectorClient still exposes two-param balanceTransaction`() {
        // Verify the two-param overload exists at compile time.
        // We can't call it without a live WebSocket, but we can verify the method is accessible.
        val client = DAppConnectorClient("ws://localhost:9932")
        val method = client::class.java.declaredMethods.find {
            it.name.contains("balanceTransaction") && it.parameterCount > 2 // suspend adds Continuation
        }
        assertNotNull(
            "Two-param balanceTransaction(String, Boolean) should exist",
            method,
        )
    }

    // ── MidnightConfig.Builder validation ──

    @Test(expected = IllegalArgumentException::class)
    fun `builder requires either transactionBalancer or walletUrl`() {
        // context.applicationContext returns null with isReturnDefaultValues = true,
        // but the require check fires before that. Builder.build() validates inputs.
        MidnightConfig.Builder(android.content.ContextWrapper(null))
            .indexerUrl("http://localhost:8088/api/v3")
            .networkId("preview")
            .build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `builder rejects walletUrl without ws scheme`() {
        MidnightConfig.Builder(android.content.ContextWrapper(null))
            .indexerUrl("http://localhost:8088/api/v3")
            .walletUrl("http://localhost:9932")
            .networkId("preview")
            .build()
    }

    @Test
    fun `builder accepts transactionBalancer without walletUrl`() {
        val balancer = object : TransactionBalancer {
            override suspend fun balanceTransaction(provenTxHex: String) = provenTxHex
            override suspend fun submitTransaction(balancedTxHex: String) {}
        }

        // This should not throw — walletUrl is not required when balancer is set.
        // Note: build() will call context.applicationContext which returns null
        // with isReturnDefaultValues = true, causing MidnightConfig constructor to
        // receive null. That's fine for validating the builder logic.
        try {
            MidnightConfig.Builder(android.content.ContextWrapper(null))
                .indexerUrl("http://localhost:8088/api/v3")
                .transactionBalancer(balancer)
                .networkId("preview")
                .build()
        } catch (e: IllegalArgumentException) {
            fail("Should not throw when transactionBalancer is provided: ${e.message}")
        } catch (_: Exception) {
            // Other exceptions (NullPointerException from null context) are OK —
            // we're testing builder validation, not full config construction.
        }
    }
}
