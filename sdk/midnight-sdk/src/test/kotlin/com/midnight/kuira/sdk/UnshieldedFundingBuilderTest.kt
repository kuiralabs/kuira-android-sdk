package com.midnight.kuira.sdk

import com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity
import com.midnight.kuira.core.indexer.database.UtxoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Deterministic tests for the funding-UTXO selection behind a contract's receiveUnshielded
 * (the fix for node error 138). Targets the pure [UnshieldedFundingBuilder.select] — no SDK,
 * no UtxoManager, no org.json — so the coverage + input-count logic is verified in isolation.
 * The trivial JSON field-mapping in build() is exercised by the on-device deposit e2e.
 */
class UnshieldedFundingBuilderTest {

    private val native = "00".repeat(32)

    private fun utxo(id: String, value: String, outputNo: Int = 0) =
        UnshieldedUtxoEntity(
            id = id,
            transactionHash = id,
            intentHash = "11".repeat(32),
            outputIndex = outputNo,
            owner = "mn_addr_test",
            ownerPublicKey = "aa".repeat(32),
            tokenType = native,
            value = value,
            ctime = 0L,
            registeredForDustGeneration = true,
            state = UtxoState.AVAILABLE,
        )

    private fun select(candidates: List<UnshieldedUtxoEntity>, amount: Long) =
        UnshieldedFundingBuilder.select(candidates, BigInteger.valueOf(amount))

    @Test
    fun `selects largest-first minimal set and reports change`() {
        // 6M + 4M + 1M available; amount 7M → largest-first picks 6M then 4M (2 inputs, not the 1M),
        // change = 10M − 7M = 3M.
        val sel = select(
            listOf(utxo("a", "1000000", outputNo = 2), utxo("b", "6000000"), utxo("c", "4000000", outputNo = 1)),
            7_000_000,
        )
        assertEquals("minimal input set covering the amount", 2, sel.inputs.size)
        assertEquals(setOf("6000000", "4000000"), sel.inputs.map { it.value }.toSet())
        assertEquals(BigInteger.valueOf(3_000_000), sel.change)
    }

    @Test
    fun `single covering utxo yields one input and its change`() {
        val sel = select(listOf(utxo("a", "5000000"), utxo("b", "3000000")), 4_000_000)
        assertEquals(1, sel.inputs.size)
        assertEquals("5000000", sel.inputs[0].value)
        assertEquals(BigInteger.valueOf(1_000_000), sel.change)
    }

    @Test
    fun `exact cover leaves zero change`() {
        val sel = select(listOf(utxo("a", "5000000")), 5_000_000)
        assertEquals(1, sel.inputs.size)
        assertEquals(BigInteger.ZERO, sel.change)
    }

    @Test
    fun `insufficient funds throws`() {
        val ex = assertThrows(InsufficientFundsException::class.java) {
            select(listOf(utxo("a", "3000000")), 9_000_000)
        }
        assertTrue(ex.message!!.contains("Insufficient NIGHT"))
    }

    @Test
    fun `zero amount rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            select(listOf(utxo("a", "5000000")), 0)
        }
    }
}
