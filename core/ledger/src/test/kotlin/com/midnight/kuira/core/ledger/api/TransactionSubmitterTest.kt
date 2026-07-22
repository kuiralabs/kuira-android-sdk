package com.midnight.kuira.core.ledger.api

import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.ledger.fee.DustActionsBuilder
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
        val expectedTxHash = "a".repeat(64) // 64 hex chars
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

    // : error 170 (stale dust spend root) must be detectable so the send path can
    // re-sync dust and retry, rather than surfacing a dead "Invalid Transaction".
    @Test
    fun `TransactionRejected isDustSpendProof matches node error 170 only`() {
        assertEquals(170, TransactionRejected.ERROR_INVALID_DUST_SPEND_PROOF)
        assertTrue(TransactionRejected("Invalid Transaction", customErrorCode = 170).isDustSpendProof)
        assertFalse(TransactionRejected("Invalid Transaction", customErrorCode = 115).isDustSpendProof)
        assertFalse(TransactionRejected("Invalid Transaction", customErrorCode = null).isDustSpendProof)
    }

    // 171 (OutOfDustValidityWindow) is the dust-window sibling of 170 — an idle dust
    // state whose ctime drifted out of [tblock-grace, tblock]. Must be detectable and
    // distinct from 170 so the contract-call path can re-sync dust and retry.
    @Test
    fun `TransactionRejected isOutOfDustValidityWindow matches node error 171 only`() {
        assertEquals(171, TransactionRejected.ERROR_OUT_OF_DUST_VALIDITY_WINDOW)
        assertTrue(TransactionRejected("Invalid Transaction", customErrorCode = 171).isOutOfDustValidityWindow)
        assertFalse(TransactionRejected("Invalid Transaction", customErrorCode = 170).isOutOfDustValidityWindow)
        assertFalse(TransactionRejected("Invalid Transaction", customErrorCode = null).isOutOfDustValidityWindow)
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

    /**
     * : the dust-fee send must load the dust state ONCE, not twice. Pre-#291,
     * `buildDustActions` deserialized the multi-MB dust state internally AND `submitWithFees`
     * deserialized it again — ~2s/~350MB each, back-to-back, starving the UI (the PreProd
     * send-screen freeze). Now `submitWithFees` loads it once, BEFORE `buildDustActions`, and
     * passes it in.
     *
     * Driven via the no-state path (loadState → null): the send fails fast having called
     * loadState exactly once and WITHOUT calling buildDustActions — which only holds for the
     * new ordering. On the old code buildDustActions ran first (and loaded internally), so the
     * `exactly = 0` buildDustActions check would fail. (FfiTransactionSerializer is mocked;
     * mockk bypasses its `init { loadLibrary }` via Objenesis, so no native lib is needed.)
     */
    @Test
    fun `submitWithFees loads dust state once and before buildDustActions`() = runTest {
        val nodeClient = mockk<NodeRpcClient>(relaxed = true)
        val proofServerClient = mockk<ProofServerClient>()
        coEvery { proofServerClient.proveTransaction(any()) } returns "base_proven_hex"
        val indexerClient = mockk<IndexerClient>(relaxed = true)

        val serializer = mockk<FfiTransactionSerializer>()
        every { serializer.serialize(any()) } returns "base_unproven_hex"
        every { serializer.sealProvenTransaction(any()) } returns "base_sealed_hex"

        val utxoManager = mockk<UtxoManager>(relaxed = true)
        val dustActionsBuilder = mockk<DustActionsBuilder>(relaxed = true)
        val dustRepository = mockk<DustRepository>(relaxed = true)
        // No dust state available → submitWithFees must fail fast after a single load.
        coEvery { dustRepository.loadState(any()) } returns null

        val submitter = TransactionSubmitter(
            nodeRpcClient = nodeClient,
            proofServerClient = proofServerClient,
            indexerClient = indexerClient,
            serializer = serializer,
            utxoManager = utxoManager,
            dustActionsBuilder = dustActionsBuilder,
            dustRepository = dustRepository,
            provingMode = ProvingMode.REMOTE,
        )

        val result = submitter.submitWithFees(
            signedIntent = createTestIntent(),
            ledgerParamsHex = "00",
            fromAddress = "mn_addr_test",
            seed = ByteArray(32),
        )

        assertTrue(
            "Absent dust state must fail fast",
            result is TransactionSubmitter.SubmissionResult.Failed,
        )
        // Loaded exactly once (was twice pre-#291).
        coVerify(exactly = 1) { dustRepository.loadState(any()) }
        // The load now happens BEFORE buildDustActions, so the fail-fast skips it entirely
        // (pre-#291 buildDustActions ran first and loaded internally → this would be >= 1).
        coVerify(exactly = 0) {
            dustActionsBuilder.buildDustActions(any(), any(), any(), any(), any(), any())
        }
    }

    private fun createTestIntent(): Intent {
        val input = UtxoSpend(
            intentHash = "1".repeat(64),
            outputNo = 0,
            value = BigInteger("100000000000"), // 100 NIGHT
            owner = "2".repeat(64),
            ownerPublicKey = UtxoSpend.TEST_PUBLIC_KEY,
            tokenType = "0".repeat(64)
        )

        val output = UtxoOutput(
            value = BigInteger("100000000000"),
            owner = "3".repeat(64),
            tokenType = "0".repeat(64)
        )

        val signature = ByteArray(64) { 4 } // Mock signature

        val offer = UnshieldedOffer(
            inputs = listOf(input),
            outputs = listOf(output),
            signatures = listOf(signature)
        )

        return Intent(
            guaranteedUnshieldedOffer = offer,
            fallibleUnshieldedOffer = null,
            ttl = 1704067200000 // 2024-01-01
        )
    }
}
