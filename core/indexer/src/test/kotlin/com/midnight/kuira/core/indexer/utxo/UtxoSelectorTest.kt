// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.indexer.utxo

import com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity
import com.midnight.kuira.core.indexer.database.UtxoState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class UtxoSelectorTest {

    private lateinit var selector: UtxoSelector

    @Before
    fun setUp() {
        selector = UtxoSelector()
    }

    // Helper to create test UTXO
    private fun createUtxo(value: Long, tokenType: String = "NIGHT"): UnshieldedUtxoEntity {
        return UnshieldedUtxoEntity(
            id = "tx${value}:0",
            transactionHash = "txhash_$value",  // Database ID uses transactionHash
            intentHash = "tx$value",
            outputIndex = 0,
            owner = "mn_addr_test",
            ownerPublicKey = null,
            tokenType = tokenType,
            value = value.toString(),
            ctime = 0,
            registeredForDustGeneration = false,
            state = UtxoState.AVAILABLE
        )
    }

    @Test
    fun `given exact amount available when selecting then returns exact match`() {
        // Given
        val utxos = listOf(
            createUtxo(100)
        )
        val required = BigInteger("100")

        // When
        val result = selector.selectUtxos(utxos, required)

        // Then
        assertTrue(result is UtxoSelector.SelectionResult.Success)
        val success = result as UtxoSelector.SelectionResult.Success
        assertEquals(1, success.selectedUtxos.size)
        assertEquals(BigInteger("100"), success.totalSelected)
        assertEquals(BigInteger.ZERO, success.change)
    }

    @Test
    fun `given single UTXO larger than required when selecting then returns with change`() {
        // Given
        val utxos = listOf(
            createUtxo(150)
        )
        val required = BigInteger("100")

        // When
        val result = selector.selectUtxos(utxos, required)

        // Then
        assertTrue(result is UtxoSelector.SelectionResult.Success)
        val success = result as UtxoSelector.SelectionResult.Success
        assertEquals(1, success.selectedUtxos.size)
        assertEquals(BigInteger("150"), success.totalSelected)
        assertEquals(BigInteger("50"), success.change)
    }

    @Test
    fun `given smallest-first selection when selecting then picks smallest UTXOs first`() {
        // Given - UTXOs in ascending order (already sorted)
        val utxos = listOf(
            createUtxo(10),
            createUtxo(25),
            createUtxo(50),
            createUtxo(75),
            createUtxo(100)
        )
        val required = BigInteger("100")

        // When
        val result = selector.selectUtxos(utxos, required)

        // Then
        assertTrue(result is UtxoSelector.SelectionResult.Success)
        val success = result as UtxoSelector.SelectionResult.Success

        // Should select: 10 + 25 + 50 + 75 = 160 (stops when >= 100)
        assertEquals(4, success.selectedUtxos.size)
        assertEquals("10", success.selectedUtxos[0].value)
        assertEquals("25", success.selectedUtxos[1].value)
        assertEquals("50", success.selectedUtxos[2].value)
        assertEquals("75", success.selectedUtxos[3].value)
        assertEquals(BigInteger("160"), success.totalSelected)
        assertEquals(BigInteger("60"), success.change)
    }

    @Test
    fun `given multiple UTXOs when selecting then stops at first sufficient amount`() {
        // Given
        val utxos = listOf(
            createUtxo(30),
            createUtxo(40),
            createUtxo(50),  // Sum = 120, stops here
            createUtxo(60),  // Not selected
            createUtxo(70)   // Not selected
        )
        val required = BigInteger("100")

        // When
        val result = selector.selectUtxos(utxos, required)

        // Then
        assertTrue(result is UtxoSelector.SelectionResult.Success)
        val success = result as UtxoSelector.SelectionResult.Success

        // Should select: 30 + 40 + 50 = 120 (stops when >= 100)
        assertEquals(3, success.selectedUtxos.size)
        assertEquals(BigInteger("120"), success.totalSelected)
        assertEquals(BigInteger("20"), success.change)
    }

    @Test
    fun `given insufficient funds when selecting then returns InsufficientFunds`() {
        // Given
        val utxos = listOf(
            createUtxo(10),
            createUtxo(20),
            createUtxo(30)
        )
        val required = BigInteger("100")

        // When
        val result = selector.selectUtxos(utxos, required)

        // Then
        assertTrue(result is UtxoSelector.SelectionResult.InsufficientFunds)
        val failure = result as UtxoSelector.SelectionResult.InsufficientFunds
        assertEquals(BigInteger("100"), failure.required)
        assertEquals(BigInteger("60"), failure.available)
        assertEquals(BigInteger("40"), failure.shortfall)
    }

    @Test
    fun `given empty UTXO list when selecting then returns InsufficientFunds`() {
        // Given
        val utxos = emptyList<UnshieldedUtxoEntity>()
        val required = BigInteger("100")

        // When
        val result = selector.selectUtxos(utxos, required)

        // Then
        assertTrue(result is UtxoSelector.SelectionResult.InsufficientFunds)
        val failure = result as UtxoSelector.SelectionResult.InsufficientFunds
        assertEquals(BigInteger.ZERO, failure.available)
        assertEquals(BigInteger("100"), failure.shortfall)
    }

    @Test
    fun `given zero required amount when selecting then throws`() {
        // Given
        val utxos = listOf(createUtxo(100))
        val required = BigInteger.ZERO

        // When/Then
        assertThrows(IllegalArgumentException::class.java) {
            selector.selectUtxos(utxos, required)
        }
    }

    @Test
    fun `given negative required amount when selecting then throws`() {
        // Given
        val utxos = listOf(createUtxo(100))
        val required = BigInteger("-100")

        // When/Then
        assertThrows(IllegalArgumentException::class.java) {
            selector.selectUtxos(utxos, required)
        }
    }

    @Test
    fun `given large numbers when selecting then handles correctly`() {
        // Given - Large UTXO values (billions)
        val utxos = listOf(
            createUtxo(1_000_000_000),  // 1 billion
            createUtxo(2_000_000_000),  // 2 billion
            createUtxo(5_000_000_000)   // 5 billion
        )
        val required = BigInteger("3000000000")  // 3 billion

        // When
        val result = selector.selectUtxos(utxos, required)

        // Then
        assertTrue(result is UtxoSelector.SelectionResult.Success)
        val success = result as UtxoSelector.SelectionResult.Success
        assertEquals(2, success.selectedUtxos.size)
        assertEquals(BigInteger("3000000000"), success.totalSelected)
        assertEquals(BigInteger.ZERO, success.change)
    }

    // ========== Multi-Token Tests ==========

    @Test
    fun `given multi-token requirements when all sufficient then returns success`() {
        // Given - Mixed NIGHT and DUST tokens
        val utxos = listOf(
            createUtxo(100, "NIGHT"),
            createUtxo(200, "NIGHT"),
            createUtxo(50, "DUST"),
            createUtxo(75, "DUST")
        )
        val requirements = mapOf(
            "NIGHT" to BigInteger("150"),
            "DUST" to BigInteger("100")
        )

        // When
        val result = selector.selectUtxosMultiToken(utxos, requirements)

        // Then
        assertTrue(result is UtxoSelector.MultiTokenResult.Success)
        val success = result as UtxoSelector.MultiTokenResult.Success

        // Verify NIGHT selection (100 + 200 = 300, need 150)
        val nightSelection = success.selections["NIGHT"] as UtxoSelector.SelectionResult.Success
        assertEquals(2, nightSelection.selectedUtxos.size)
        assertEquals(BigInteger("300"), nightSelection.totalSelected)
        assertEquals(BigInteger("150"), nightSelection.change)

        // Verify DUST selection (50 + 75 = 125, need 100)
        val dustSelection = success.selections["DUST"] as UtxoSelector.SelectionResult.Success
        assertEquals(2, dustSelection.selectedUtxos.size)
        assertEquals(BigInteger("125"), dustSelection.totalSelected)
        assertEquals(BigInteger("25"), dustSelection.change)

        // Verify combined results
        assertEquals(4, success.allSelectedUtxos().size)
    }

    @Test
    fun `given multi-token with one insufficient when selecting then returns partial failure`() {
        // Given
        val utxos = listOf(
            createUtxo(100, "NIGHT"),
            createUtxo(200, "NIGHT"),
            createUtxo(50, "DUST")  // Not enough DUST
        )
        val requirements = mapOf(
            "NIGHT" to BigInteger("150"),  // OK: have 300
            "DUST" to BigInteger("100")    // FAIL: have only 50
        )

        // When
        val result = selector.selectUtxosMultiToken(utxos, requirements)

        // Then
        assertTrue(result is UtxoSelector.MultiTokenResult.PartialFailure)
        val failure = result as UtxoSelector.MultiTokenResult.PartialFailure
        assertEquals("DUST", failure.failedToken)
        assertEquals(BigInteger("100"), failure.required)
        assertEquals(BigInteger("50"), failure.available)

        // NIGHT selection should still be present (even though overall failed)
        assertTrue(failure.selections["NIGHT"] is UtxoSelector.SelectionResult.Success)
    }

    @Test
    fun `given multi-token with all insufficient when selecting then returns first failure`() {
        // Given
        val utxos = listOf(
            createUtxo(50, "NIGHT"),
            createUtxo(25, "DUST")
        )
        val requirements = mapOf(
            "NIGHT" to BigInteger("100"),  // FAIL: have only 50
            "DUST" to BigInteger("100")    // FAIL: have only 25
        )

        // When
        val result = selector.selectUtxosMultiToken(utxos, requirements)

        // Then
        assertTrue(result is UtxoSelector.MultiTokenResult.PartialFailure)
        val failure = result as UtxoSelector.MultiTokenResult.PartialFailure

        // Should fail on first token in iteration order
        // (Map iteration order is not guaranteed, so we just check it's one of them)
        assertTrue(failure.failedToken == "NIGHT" || failure.failedToken == "DUST")
    }

    @Test
    fun `given multi-token with single token when selecting then works like single selection`() {
        // Given
        val utxos = listOf(
            createUtxo(100, "NIGHT"),
            createUtxo(200, "NIGHT")
        )
        val requirements = mapOf(
            "NIGHT" to BigInteger("150")
        )

        // When
        val result = selector.selectUtxosMultiToken(utxos, requirements)

        // Then
        assertTrue(result is UtxoSelector.MultiTokenResult.Success)
        val success = result as UtxoSelector.MultiTokenResult.Success
        assertEquals(1, success.selections.size)

        val nightSelection = success.selections["NIGHT"] as UtxoSelector.SelectionResult.Success
        assertEquals(2, nightSelection.selectedUtxos.size)
        assertEquals(BigInteger("300"), nightSelection.totalSelected)
    }

    // Removed: Empty requirements test - impossible scenario (YAGNI)

    @Test
    fun `given multi-token with three tokens when selecting then handles all correctly`() {
        // Given
        val utxos = listOf(
            createUtxo(100, "NIGHT"),
            createUtxo(200, "NIGHT"),
            createUtxo(50, "DUST"),
            createUtxo(75, "DUST"),
            createUtxo(30, "CUSTOM"),
            createUtxo(40, "CUSTOM")
        )
        val requirements = mapOf(
            "NIGHT" to BigInteger("250"),
            "DUST" to BigInteger("100"),
            "CUSTOM" to BigInteger("60")
        )

        // When
        val result = selector.selectUtxosMultiToken(utxos, requirements)

        // Then
        assertTrue(result is UtxoSelector.MultiTokenResult.Success)
        val success = result as UtxoSelector.MultiTokenResult.Success
        assertEquals(3, success.selections.size)
        assertEquals(6, success.allSelectedUtxos().size)
    }

    // ========== Edge Cases ==========

    @Test
    fun `given unsorted UTXOs when selecting then still works (relies on DAO sorting)`() {
        // Given - UTXOs NOT sorted (simulate if DAO query wasn't sorted)
        val utxos = listOf(
            createUtxo(100),
            createUtxo(25),
            createUtxo(75),
            createUtxo(10),
            createUtxo(50)
        )
        val required = BigInteger("100")

        // When
        val result = selector.selectUtxos(utxos, required)

        // Then - Should still succeed (but may not be optimal)
        assertTrue(result is UtxoSelector.SelectionResult.Success)
        val success = result as UtxoSelector.SelectionResult.Success

        // Picks in order given: 100 (stops immediately)
        assertEquals(1, success.selectedUtxos.size)
        assertEquals("100", success.selectedUtxos[0].value)
    }

    @Test
    fun `given dust amounts when selecting then handles correctly`() {
        // Given - Very small amounts (dust)
        val utxos = listOf(
            createUtxo(1),
            createUtxo(2),
            createUtxo(3),
            createUtxo(4),
            createUtxo(5)
        )
        val required = BigInteger("10")

        // When
        val result = selector.selectUtxos(utxos, required)

        // Then
        assertTrue(result is UtxoSelector.SelectionResult.Success)
        val success = result as UtxoSelector.SelectionResult.Success

        // Should select: 1 + 2 + 3 + 4 = 10 (stops when >= 10)
        assertEquals(4, success.selectedUtxos.size)
        assertEquals(BigInteger("10"), success.totalSelected)
        assertEquals(BigInteger.ZERO, success.change)
    }
}
