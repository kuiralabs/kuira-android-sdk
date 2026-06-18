package com.midnight.kuira.core.indexer.sync

import com.midnight.kuira.core.indexer.model.TransactionStatus
import com.midnight.kuira.core.indexer.model.TransactionType
import com.midnight.kuira.core.indexer.model.UnshieldedTransaction
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import com.midnight.kuira.core.indexer.model.Utxo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Pure tests for the #284 per-transaction receipt classifier in [SubscriptionManager].
 *
 * The headline guarantee these pin: a user is NEVER told they "received" their own NIGHT. A
 * transaction that spends any of our UTXOs is our own send, and the NIGHT it returns to us is
 * change — [SubscriptionManager.classifyReceipt] returns null for it. A genuine receipt (a tx
 * that spent none of ours and created NIGHT to us) yields the EXACT amount of that one
 * transaction — not a balance delta.
 */
class SubscriptionManagerReceiptTest {

    private val me = "mn_addr_me"
    private val other = "mn_addr_other"
    private val nightHex = "0".repeat(64)
    private val customTokenHex = "1".repeat(64)

    private fun utxo(owner: String, value: Long, token: String = nightHex, idx: Int = 0) = Utxo(
        value = value.toString(),
        owner = owner,
        tokenType = token,
        intentHash = "intent-$owner-$idx",
        outputIndex = idx,
        ctime = 0L,
        registeredForDustGeneration = false,
    )

    private fun tx(
        id: Int,
        created: List<Utxo>,
        spent: List<Utxo>,
        status: TransactionStatus = TransactionStatus.SUCCESS,
    ) = UnshieldedTransactionUpdate.Transaction(
        transaction = UnshieldedTransaction(
            id = id,
            hash = "hash-$id",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            transactionResult = UnshieldedTransaction.TransactionResult(status = status, segments = null),
        ),
        createdUtxos = created,
        spentUtxos = spent,
    )

    @Test
    fun `genuine receipt — spent none of ours, created NIGHT to us — reports the exact amount`() {
        val receipt = SubscriptionManager.classifyReceipt(
            me,
            tx(id = 7, created = listOf(utxo(me, 100)), spent = emptyList()),
        )
        assertEquals(BigInteger.valueOf(100), receipt?.amount)
        assertEquals(7, receipt?.transactionId)
        assertEquals("hash-7", receipt?.transactionHash)
    }

    @Test
    fun `our own send is NOT a receipt — change returning to us is never announced`() {
        // We spent our 170 input; 40 went out, 130 came back as change. The user must NOT be
        // told they "received 130" — this is the bug #284 exists to kill.
        val receipt = SubscriptionManager.classifyReceipt(
            me,
            tx(
                id = 8,
                created = listOf(utxo(other, 40, idx = 0), utxo(me, 130, idx = 1)),
                spent = listOf(utxo(me, 170)),
            ),
        )
        assertNull(receipt)
    }

    @Test
    fun `a self-send (spend ours, create back to us) is not a receipt`() {
        val receipt = SubscriptionManager.classifyReceipt(
            me,
            tx(id = 9, created = listOf(utxo(me, 100)), spent = listOf(utxo(me, 100))),
        )
        assertNull(receipt)
    }

    @Test
    fun `a multi-output receipt sums only the outputs to us`() {
        val receipt = SubscriptionManager.classifyReceipt(
            me,
            tx(
                id = 10,
                created = listOf(utxo(me, 60, idx = 0), utxo(other, 999, idx = 1), utxo(me, 40, idx = 2)),
                spent = emptyList(),
            ),
        )
        assertEquals(BigInteger.valueOf(100), receipt?.amount)
    }

    @Test
    fun `the sender's spent inputs do not make a receive look like our own send`() {
        // Robustness if the indexer returns the WHOLE transaction's UTXOs: the spent input is
        // the SENDER's (not ours), so we still spent nothing → it's a genuine receipt to us.
        val receipt = SubscriptionManager.classifyReceipt(
            me,
            tx(id = 11, created = listOf(utxo(me, 50)), spent = listOf(utxo(other, 200))),
        )
        assertEquals(BigInteger.valueOf(50), receipt?.amount)
    }

    @Test
    fun `created output to someone else is not our receipt`() {
        val receipt = SubscriptionManager.classifyReceipt(
            me,
            tx(id = 12, created = listOf(utxo(other, 100)), spent = emptyList()),
        )
        assertNull(receipt)
    }

    @Test
    fun `a non-NIGHT token to us is not a NIGHT receipt`() {
        val receipt = SubscriptionManager.classifyReceipt(
            me,
            tx(id = 13, created = listOf(utxo(me, 100, token = customTokenHex)), spent = emptyList()),
        )
        assertNull(receipt)
    }

    @Test
    fun `a failed transaction is never a receipt`() {
        val receipt = SubscriptionManager.classifyReceipt(
            me,
            tx(id = 14, created = listOf(utxo(me, 100)), spent = emptyList(), status = TransactionStatus.FAILURE),
        )
        assertNull(receipt)
    }

    @Test
    fun `isNewReceipt suppresses until a baseline exists, then announces past it`() {
        // Null baseline = first sync before reaching tip → suppress (don't re-announce history).
        assertFalse(SubscriptionManager.isNewReceipt(baseline = null, txId = 5))
        // Past the baseline → announce.
        assertTrue(SubscriptionManager.isNewReceipt(baseline = 5, txId = 6))
        // At or below the baseline (a replay) → suppress.
        assertFalse(SubscriptionManager.isNewReceipt(baseline = 5, txId = 5))
        assertFalse(SubscriptionManager.isNewReceipt(baseline = 5, txId = 4))
    }
}
