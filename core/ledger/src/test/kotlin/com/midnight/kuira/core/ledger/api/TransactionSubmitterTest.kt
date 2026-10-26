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
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * What [TransactionSubmitter] reports for every way a submission can end.
 *
 * The node is faked, so each test drives one outcome the real node can produce. The fake node
 * reports what the real one does: block hashes and heights, never the transaction's own hash,
 * which the submitter derives from the sealed transaction.
 */
class TransactionSubmitterTest {

    // ── submitAndWait: every node outcome ──

    @Test
    fun `a finalized transaction is a success carrying the Midnight hash and block height`() = runTest {
        // Given
        val fixture = Fixture(TransactionFinalizationResult.Finalized(blockHash = BLOCK, blockHeight = 12_345L))

        // When
        val result = fixture.submitter.submitAndWait(signedIntent = createTestIntent(), fromAddress = ADDRESS)

        // Then
        assertEquals(TransactionSubmitter.SubmissionResult.Success(txHash = MIDNIGHT_TX_HASH, blockHeight = 12_345L), result)
        assertEquals("the node is handed the sealed transaction", "sealed_tx_hex", fixture.submittedTx.captured)
        coVerify(exactly = 1) { fixture.utxoManager.markUtxosAsSpentByIntent(SPENT_INPUTS) }
    }

    @Test
    fun `a finalized transaction whose height could not be read reports no height, not zero`() = runTest {
        // Given
        val fixture = Fixture(TransactionFinalizationResult.Finalized(blockHash = BLOCK, blockHeight = null))

        // When
        val result = fixture.submitter.submitAndWait(signedIntent = createTestIntent(), fromAddress = ADDRESS)

        // Then
        assertEquals(TransactionSubmitter.SubmissionResult.Success(txHash = MIDNIGHT_TX_HASH, blockHeight = null), result)
    }

    @Test
    fun `a timeout after the transaction reached a block is pending and stops offering its inputs`() = runTest {
        // Given
        val fixture = Fixture(TransactionFinalizationResult.Timeout(inBlockHash = BLOCK))

        // When
        val result = fixture.submitter.submitAndWait(signedIntent = createTestIntent(), fromAddress = ADDRESS)

        // Then
        val pending = result as TransactionSubmitter.SubmissionResult.Pending
        assertEquals(MIDNIGHT_TX_HASH, pending.txHash)
        assertTrue("the reason names the block: ${pending.reason}", pending.reason.contains(BLOCK))
        coVerify(exactly = 1) { fixture.utxoManager.markUtxosAsSpentByIntent(SPENT_INPUTS) }
    }

    @Test
    fun `a timeout before the transaction reached any block is pending and keeps its inputs`() = runTest {
        // Given
        val fixture = Fixture(TransactionFinalizationResult.Timeout(inBlockHash = null))

        // When
        val result = fixture.submitter.submitAndWait(signedIntent = createTestIntent(), fromAddress = ADDRESS)

        // Then
        assertEquals(MIDNIGHT_TX_HASH, (result as TransactionSubmitter.SubmissionResult.Pending).txHash)
        coVerify(exactly = 0) { fixture.utxoManager.markUtxosAsSpentByIntent(any()) }
    }

    @Test
    fun `each outcome that will not land is a failure carrying the hash, and keeps the inputs`() = runTest {
        val notLanding = listOf(
            TransactionFinalizationResult.Dropped("Transaction dropped from pool"),
            TransactionFinalizationResult.Invalid("Transaction is invalid"),
            TransactionFinalizationResult.Usurped(replacedBy = "e".repeat(64)),
        )
        for (outcome in notLanding) {
            // Given
            val fixture = Fixture(outcome)

            // When
            val result = fixture.submitter.submitAndWait(signedIntent = createTestIntent(), fromAddress = ADDRESS)

            // Then
            val failed = result as? TransactionSubmitter.SubmissionResult.Failed
            assertEquals("$outcome must fail under the transaction's hash", MIDNIGHT_TX_HASH, failed?.txHash)
            assertTrue("$outcome: reason ${failed?.reason}", failed!!.reason.isNotBlank())
            coVerify(exactly = 0) { fixture.utxoManager.markUtxosAsSpentByIntent(any()) }
        }
    }

    @Test
    fun `a node rejection is a failure carrying the hash and the node's error code`() = runTest {
        // Given
        val fixture = Fixture(rejection = TransactionRejected(reason = "Invalid Transaction", customErrorCode = 170))

        // When
        val result = fixture.submitter.submitAndWait(signedIntent = createTestIntent(), fromAddress = ADDRESS)

        // Then
        val failed = result as TransactionSubmitter.SubmissionResult.Failed
        assertEquals("a rejected transaction was still submitted, under this hash", MIDNIGHT_TX_HASH, failed.txHash)
        assertEquals(TransactionRejected.ERROR_INVALID_DUST_SPEND_PROOF, failed.customErrorCode)
        assertTrue(failed.reason.contains("rejected"))
    }

    @Test
    fun `a sealed transaction with no derivable hash is never submitted`() = runTest {
        // Given: the native hash cannot read the sealed transaction
        val fixture = Fixture(TransactionFinalizationResult.Finalized(blockHash = BLOCK, blockHeight = 1L), midnightHash = null)

        // When
        val result = fixture.submitter.submitAndWait(signedIntent = createTestIntent(), fromAddress = ADDRESS)

        // Then: failing before submission is safe; nothing was spent
        assertNull((result as TransactionSubmitter.SubmissionResult.Failed).txHash)
        coVerify(exactly = 0) { fixture.node.submitAndWaitForFinalization(any(), any(), any()) }
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

    // ── submitPrebuiltTransaction (the shielded and dust-registration path) ──

    @Test
    fun `a finalized prebuilt transaction is a success carrying the Midnight hash and block height`() = runTest {
        // Given
        val fixture = Fixture(TransactionFinalizationResult.Finalized(blockHash = BLOCK, blockHeight = 9L))

        // When
        val result = fixture.submitter.submitPrebuiltTransaction("unproven_tx_hex")

        // Then
        assertEquals(TransactionSubmitter.SubmissionResult.Success(txHash = MIDNIGHT_TX_HASH, blockHeight = 9L), result)
    }

    @Test
    fun `a prebuilt transaction the node drops is a failure carrying its hash`() = runTest {
        // Given
        val fixture = Fixture(TransactionFinalizationResult.Dropped("Transaction dropped from pool"))

        // When
        val result = fixture.submitter.submitPrebuiltTransaction("unproven_tx_hex")

        // Then
        assertEquals(
            TransactionSubmitter.SubmissionResult.Failed(txHash = MIDNIGHT_TX_HASH, reason = "Transaction dropped from pool"),
            result,
        )
    }

    // ── submitOnly (fire-and-forget) ──

    @Test
    fun `submitOnly returns the Midnight hash, not the node's extrinsic hash`() = runTest {
        // Given: the node answers with its extrinsic hash, which is a different value
        val fixture = Fixture(extrinsicHash = "b".repeat(64))

        // When
        val txHash = fixture.submitter.submitOnly(createTestIntent())

        // Then
        assertEquals(MIDNIGHT_TX_HASH, txHash)
        coVerify(exactly = 1) { fixture.node.submitTransaction("sealed_tx_hex") }
    }

    // ── submitWithFees ──

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

    // ==================== Test Helpers ====================

    /** A submitter wired to a node that ends every watched submission with [outcome]. */
    private class Fixture(
        outcome: TransactionFinalizationResult? = null,
        rejection: TransactionRejected? = null,
        extrinsicHash: String = "b".repeat(64),
        midnightHash: String? = MIDNIGHT_TX_HASH,
    ) {
        val submittedTx = slot<String>()
        val node = mockk<NodeRpcClient>().also { node ->
            coEvery { node.submitAndWaitForFinalization(capture(submittedTx), any(), any()) } answers {
                rejection?.let { throw it }
                outcome ?: error("no watched outcome configured")
            }
            coEvery { node.submitTransaction(any()) } returns extrinsicHash
        }
        val utxoManager = mockk<UtxoManager>().also {
            coEvery { it.markUtxosAsSpentByIntent(any()) } returns 1
        }
        private val serializer = mockk<TransactionSerializer>().also {
            every { it.serialize(any()) } returns "unproven_tx_hex"
            every { it.sealProvenTransaction(any()) } returns "sealed_tx_hex"
            every { it.getTransactionHash("sealed_tx_hex") } returns midnightHash
        }
        private val proofServer = mockk<ProofServerClient>().also {
            coEvery { it.proveTransaction(any()) } returns "proven_tx_hex"
        }
        val submitter = TransactionSubmitter(
            nodeRpcClient = node,
            proofServerClient = proofServer,
            indexerClient = mockk(relaxed = true),
            serializer = serializer,
            utxoManager = utxoManager,
            provingMode = ProvingMode.REMOTE,
        )
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

    private companion object {
        /** Distinct from the node's extrinsic hash and every block hash, so a test can tell which surfaced. */
        val MIDNIGHT_TX_HASH = "c".repeat(64)
        val BLOCK = "d".repeat(64)
        const val ADDRESS = "mn_addr_test"

        /** The (intentHash, outputNo) pairs of [createTestIntent]'s one input. */
        val SPENT_INPUTS = listOf("1".repeat(64) to 0)
    }
}
