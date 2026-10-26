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
    fun `balanceAndSubmit submits the balanced transaction and returns what the submit reported`() = runTest {
        // Given: a balancer whose submit reports a finalized transaction
        val reported = SubmittedTransaction(txHash = "ab".repeat(32), txIdentifier = null, status = TransactionStatus.FINALIZED, blockHeight = 42L)
        val submitted = mutableListOf<String>()
        val balancer = object : TransactionBalancer {
            override suspend fun balanceTransaction(provenTxHex: String) = "balanced_$provenTxHex"
            override suspend fun submitTransaction(balancedTxHex: String): SubmittedTransaction {
                submitted += balancedTxHex
                return reported
            }
        }
        val stages = mutableListOf<BalanceProgress>()

        // When
        val result = balancer.balanceAndSubmit("abc123") { stages += it }

        // Then
        assertEquals("the BALANCED transaction is what gets submitted", listOf("balanced_abc123"), submitted)
        assertEquals("the caller gets the submit's report unchanged", reported, result)
        assertEquals(listOf<BalanceProgress>(BalanceProgress.Submitting), stages)
    }

    @Test
    fun `balanceAndSubmit lets a not-accepted submission propagate`() = runTest {
        // Given
        val balancer = object : TransactionBalancer {
            override suspend fun balanceTransaction(provenTxHex: String) = provenTxHex
            override suspend fun submitTransaction(balancedTxHex: String): SubmittedTransaction =
                throw SubmissionNotAcceptedException("Transaction dropped from pool")
        }

        // When
        val thrown = runCatching { balancer.balanceAndSubmit("abc123") }.exceptionOrNull()

        // Then
        assertTrue("a dropped transaction must not come back as a result: $thrown", thrown is SubmissionNotAcceptedException)
        assertEquals("Transaction dropped from pool", (thrown as SubmissionNotAcceptedException).reason)
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
            .indexerGraphqlUrl(TEST_INDEXER_URL)
            .networkId("preview")
            .build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `builder rejects walletUrl without ws scheme`() {
        MidnightConfig.Builder(android.content.ContextWrapper(null))
            .indexerGraphqlUrl(TEST_INDEXER_URL)
            .walletUrl("http://localhost:9932")
            .networkId("preview")
            .build()
    }

    @Test
    fun `builder accepts transactionBalancer without walletUrl`() {
        val balancer = object : TransactionBalancer {
            override suspend fun balanceTransaction(provenTxHex: String) = provenTxHex
            override suspend fun submitTransaction(balancedTxHex: String): SubmittedTransaction =
                error("not submitted by a builder-validation test")
        }

        // This should not throw — walletUrl is not required when balancer is set.
        // Note: build() will call context.applicationContext which returns null
        // with isReturnDefaultValues = true, causing MidnightConfig constructor to
        // receive null. That's fine for validating the builder logic.
        try {
            MidnightConfig.Builder(android.content.ContextWrapper(null))
                .indexerGraphqlUrl(TEST_INDEXER_URL)
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

    private companion object {
        // Composed from NetworkConfig so we don't bake the indexer API version
        // into a test string. `localhost` here (rather than UNDEPLOYED's host
        // resolution via DeviceType) because this is a JVM unit test, not
        // androidTest — Build markers are mocked to null.
        private val TEST_INDEXER_URL =
            "http://localhost:${com.midnight.kuira.core.network.NetworkConfig.LOCALNET_INDEXER_PORT}" +
                com.midnight.kuira.core.network.NetworkConfig.INDEXER_API_PATH
    }
}
