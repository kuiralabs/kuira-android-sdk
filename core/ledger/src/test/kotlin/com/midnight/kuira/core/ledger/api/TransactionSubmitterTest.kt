package com.midnight.kuira.core.ledger.api

import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.ledger.model.Intent
import com.midnight.kuira.core.ledger.model.UnshieldedOffer
import com.midnight.kuira.core.ledger.model.UtxoOutput
import com.midnight.kuira.core.ledger.model.UtxoSpend
import com.midnight.kuira.core.crypto.proving.ProvingMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class TransactionSubmitterTest {

    @Test
    fun `submitAndWait returns Success when transaction is confirmed`() = runTest {
        // Given: Mock node client that returns finalized result via WebSocket
        val nodeClient = mockk<NodeRpcClient>()
        val expectedTxHash = "a".repeat(64)  // 64 hex chars
        coEvery { nodeClient.submitAndWaitForFinalization(any(), any()) } returns
            TransactionFinalizationResult.Finalized(
                txHash = expectedTxHash,
                blockHash = "b".repeat(64),
                blockHeight = 12345L
            )

        // Given: Mock proof server client that returns proven tx
        val proofServerClient = mockk<ProofServerClient>()
        coEvery { proofServerClient.proveTransaction(any()) } returns "proven_tx_hex"

        // Given: Mock indexer client (not called in WebSocket-based flow)
        val indexerClient = mockk<IndexerClient>(relaxed = true)

        // Given: Transaction serializer that returns sealed tx
        val serializer = mockk<TransactionSerializer>()
        every { serializer.serialize(any()) } returns "unproven_tx_hex"
        every { serializer.sealProvenTransaction(any()) } returns "sealed_tx_hex"

        // Given: Mock UTXO manager for marking UTXOs as spent after finalization
        val utxoManager = mockk<UtxoManager>()
        coEvery { utxoManager.markUtxosAsSpentByIntent(any()) } returns 1

        // Given: Transaction submitter
        val submitter = TransactionSubmitter(
            nodeRpcClient = nodeClient,
            proofServerClient = proofServerClient,
            indexerClient = indexerClient,
            serializer = serializer,
            utxoManager = utxoManager,
            provingMode = ProvingMode.REMOTE,
        )

        // Given: Signed intent
        val signedIntent = createTestIntent()

        // When: Submit and wait
        val result = submitter.submitAndWait(
            signedIntent = signedIntent,
            fromAddress = "mn_addr_test"
        )

        // Then: Should succeed
        assertTrue(result is TransactionSubmitter.SubmissionResult.Success)
        val success = result as TransactionSubmitter.SubmissionResult.Success
        assertEquals(expectedTxHash, success.txHash)
        assertEquals(12345L, success.blockHeight)

        // Verify: Node client was called with WebSocket-based finalization
        coVerify { nodeClient.submitAndWaitForFinalization(any(), any()) }
    }

    @Test
    fun `submitAndWait returns Failed when node rejects transaction`() = runTest {
        // Given: Mock node client that rejects transaction via WebSocket
        val nodeClient = mockk<NodeRpcClient>()
        coEvery { nodeClient.submitAndWaitForFinalization(any(), any()) } throws TransactionRejected(
            reason = "Invalid signature",
            txHash = null
        )

        // Given: Mock proof server client that returns proven tx
        val proofServerClient = mockk<ProofServerClient>()
        coEvery { proofServerClient.proveTransaction(any()) } returns "proven_tx_hex"

        // Given: Mock indexer (won't be called)
        val indexerClient = mockk<IndexerClient>(relaxed = true)

        // Given: Transaction serializer that returns sealed tx
        val serializer = mockk<TransactionSerializer>()
        every { serializer.serialize(any()) } returns "unproven_tx_hex"
        every { serializer.sealProvenTransaction(any()) } returns "sealed_tx_hex"

        // Given: Mock UTXO manager (won't be called due to node rejection)
        val utxoManager = mockk<UtxoManager>(relaxed = true)

        // Given: Transaction submitter
        val submitter = TransactionSubmitter(
            nodeRpcClient = nodeClient,
            proofServerClient = proofServerClient,
            indexerClient = indexerClient,
            serializer = serializer,
            utxoManager = utxoManager,
            provingMode = ProvingMode.REMOTE,
        )

        // Given: Signed intent
        val signedIntent = createTestIntent()

        // When: Submit and wait
        val result = submitter.submitAndWait(
            signedIntent = signedIntent,
            fromAddress = "mn_addr_test"
        )

        // Then: Should fail
        assertTrue(result is TransactionSubmitter.SubmissionResult.Failed)
        val failed = result as TransactionSubmitter.SubmissionResult.Failed
        assertEquals(null, failed.txHash)
        assertTrue(failed.reason.contains("rejected"))
    }

    // #287: error 170 (stale dust spend root) must be detectable so the send path can
    // re-sync dust and retry, rather than surfacing a dead "Invalid Transaction".
    @Test
    fun `TransactionRejected isDustSpendProof matches node error 170 only`() {
        assertEquals(170, TransactionRejected.ERROR_INVALID_DUST_SPEND_PROOF)
        assertTrue(TransactionRejected("Invalid Transaction", customErrorCode = 170).isDustSpendProof)
        assertFalse(TransactionRejected("Invalid Transaction", customErrorCode = 115).isDustSpendProof)
        assertFalse(TransactionRejected("Invalid Transaction", customErrorCode = null).isDustSpendProof)
    }

    @Test
    fun `submitAndWait surfaces node error 170 as customErrorCode for dust-root recovery`() = runTest {
        val nodeClient = mockk<NodeRpcClient>()
        coEvery { nodeClient.submitAndWaitForFinalization(any(), any()) } throws TransactionRejected(
            reason = "Invalid Transaction",
            txHash = null,
            customErrorCode = 170,
        )
        val proofServerClient = mockk<ProofServerClient>()
        coEvery { proofServerClient.proveTransaction(any()) } returns "proven_tx_hex"
        val indexerClient = mockk<IndexerClient>(relaxed = true)
        val serializer = mockk<TransactionSerializer>()
        every { serializer.serialize(any()) } returns "unproven_tx_hex"
        every { serializer.sealProvenTransaction(any()) } returns "sealed_tx_hex"
        val utxoManager = mockk<UtxoManager>(relaxed = true)
        val submitter = TransactionSubmitter(
            nodeRpcClient = nodeClient,
            proofServerClient = proofServerClient,
            indexerClient = indexerClient,
            serializer = serializer,
            utxoManager = utxoManager,
            provingMode = ProvingMode.REMOTE,
        )

        val result = submitter.submitAndWait(
            signedIntent = createTestIntent(),
            fromAddress = "mn_addr_test",
        )

        assertTrue(result is TransactionSubmitter.SubmissionResult.Failed)
        assertEquals(170, (result as TransactionSubmitter.SubmissionResult.Failed).customErrorCode)
    }

    @Test
    fun `submitOnly returns transaction hash without waiting`() = runTest {
        // Given: Mock node client
        val nodeClient = mockk<NodeRpcClient>()
        val expectedTxHash = "b".repeat(64)
        coEvery { nodeClient.submitTransaction(any()) } returns expectedTxHash

        // Given: Mock proof server client that returns proven tx
        val proofServerClient = mockk<ProofServerClient>()
        coEvery { proofServerClient.proveTransaction(any()) } returns "proven_tx_hex"

        // Given: Mock indexer (won't be called)
        val indexerClient = mockk<IndexerClient>(relaxed = true)

        // Given: Transaction serializer that returns sealed tx
        val serializer = mockk<TransactionSerializer>()
        every { serializer.serialize(any()) } returns "unproven_tx_hex"
        every { serializer.sealProvenTransaction(any()) } returns "sealed_tx_hex"

        // Given: Mock UTXO manager (won't be called for submitOnly)
        val utxoManager = mockk<UtxoManager>(relaxed = true)

        // Given: Transaction submitter
        val submitter = TransactionSubmitter(
            nodeRpcClient = nodeClient,
            proofServerClient = proofServerClient,
            indexerClient = indexerClient,
            serializer = serializer,
            utxoManager = utxoManager,
            provingMode = ProvingMode.REMOTE,
        )

        // Given: Signed intent
        val signedIntent = createTestIntent()

        // When: Submit only (no wait)
        val txHash = submitter.submitOnly(signedIntent)

        // Then: Should return hash immediately
        assertEquals(expectedTxHash, txHash)

        // Verify: Node client was called
        coVerify { nodeClient.submitTransaction(any()) }
    }

    // ==================== Test Helpers ====================

    private fun createTestIntent(): Intent {
        val input = UtxoSpend(
            intentHash = "1".repeat(64),
            outputNo = 0,
            value = BigInteger("100000000000"),  // 100 NIGHT
            owner = "2".repeat(64),
            ownerPublicKey = UtxoSpend.TEST_PUBLIC_KEY,
            tokenType = "0".repeat(64)
        )

        val output = UtxoOutput(
            value = BigInteger("100000000000"),
            owner = "3".repeat(64),
            tokenType = "0".repeat(64)
        )

        val signature = ByteArray(64) { 4 }  // Mock signature

        val offer = UnshieldedOffer(
            inputs = listOf(input),
            outputs = listOf(output),
            signatures = listOf(signature)
        )

        return Intent(
            guaranteedUnshieldedOffer = offer,
            fallibleUnshieldedOffer = null,
            ttl = 1704067200000  // 2024-01-01
        )
    }
}
