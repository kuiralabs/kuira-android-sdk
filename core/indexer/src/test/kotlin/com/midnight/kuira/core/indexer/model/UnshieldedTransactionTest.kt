package com.midnight.kuira.core.indexer.model

import org.junit.Assert.*
import org.junit.Test

class UnshieldedTransactionTest {

    @Test
    fun `given valid regular transaction when created then succeeds`() {
        val tx = UnshieldedTransaction(
            id = 123,
            hash = "0xabc123def456",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            identifiers = listOf("id1", "id2"),
            block = UnshieldedTransaction.BlockInfo(timestamp = 1704067200000),
            fees = UnshieldedTransaction.FeeInfo(
                paidFees = "1000",
                estimatedFees = "1000"
            ),
            transactionResult = UnshieldedTransaction.TransactionResult(
                status = TransactionStatus.SUCCESS,
                segments = listOf(
                    UnshieldedTransaction.TransactionResult.SegmentResult(id = 0, success = true)
                )
            )
        )

        assertEquals(123, tx.id)
        assertEquals("0xabc123def456", tx.hash)
        assertEquals(TransactionType.RegularTransaction, tx.type)
        assertEquals(1, tx.protocolVersion)
        assertEquals(listOf("id1", "id2"), tx.identifiers)
        assertEquals(1704067200000L, tx.block!!.timestamp)
        assertNotNull(tx.fees)
        assertNotNull(tx.transactionResult)
    }

    @Test
    fun `given system transaction when created then succeeds`() {
        val tx = UnshieldedTransaction(
            id = 456,
            hash = "0xsystem123",
            type = TransactionType.SystemTransaction,
            protocolVersion = 1,
            identifiers = null,
            block = UnshieldedTransaction.BlockInfo(timestamp = 1704067200000),
            fees = null,
            transactionResult = null
        )

        assertEquals(TransactionType.SystemTransaction, tx.type)
        assertNull(tx.identifiers)
        assertNull(tx.fees)
        assertNull(tx.transactionResult)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given negative id when creating transaction then throws exception`() {
        UnshieldedTransaction(
            id = -1,
            hash = "0xabc",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            identifiers = null,
            block = UnshieldedTransaction.BlockInfo(timestamp = 0),
            fees = null,
            transactionResult = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given blank hash when creating transaction then throws exception`() {
        UnshieldedTransaction(
            id = 1,
            hash = "",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            identifiers = null,
            block = UnshieldedTransaction.BlockInfo(timestamp = 0),
            fees = null,
            transactionResult = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given negative protocol version when creating transaction then throws exception`() {
        UnshieldedTransaction(
            id = 1,
            hash = "0xabc",
            type = TransactionType.RegularTransaction,
            protocolVersion = -1,
            identifiers = null,
            block = UnshieldedTransaction.BlockInfo(timestamp = 0),
            fees = null,
            transactionResult = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given negative timestamp when creating BlockInfo then throws exception`() {
        UnshieldedTransaction.BlockInfo(timestamp = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given blank paidFees when creating FeeInfo then throws exception`() {
        UnshieldedTransaction.FeeInfo(
            paidFees = "",
            estimatedFees = "1000"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given blank estimatedFees when creating FeeInfo then throws exception`() {
        UnshieldedTransaction.FeeInfo(
            paidFees = "1000",
            estimatedFees = ""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given negative segment id when creating SegmentResult then throws exception`() {
        UnshieldedTransaction.TransactionResult.SegmentResult(
            id = -1,
            success = true
        )
    }

    @Test
    fun `given partial success transaction when created then succeeds`() {
        val result = UnshieldedTransaction.TransactionResult(
            status = TransactionStatus.PARTIAL_SUCCESS,
            segments = listOf(
                UnshieldedTransaction.TransactionResult.SegmentResult(id = 0, success = true),
                UnshieldedTransaction.TransactionResult.SegmentResult(id = 1, success = false)
            )
        )

        assertEquals(TransactionStatus.PARTIAL_SUCCESS, result.status)
        assertEquals(2, result.segments?.size)
        assertTrue(result.segments?.get(0)?.success == true)
        assertFalse(result.segments?.get(1)?.success == true)
    }

    @Test
    fun `given failure transaction when created then succeeds`() {
        val result = UnshieldedTransaction.TransactionResult(
            status = TransactionStatus.FAILURE,
            segments = null
        )

        assertEquals(TransactionStatus.FAILURE, result.status)
        assertNull(result.segments)
    }
}
