// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.ledger.fee

import com.midnight.kuira.core.indexer.database.DustTokenEntity
import com.midnight.kuira.core.indexer.database.UtxoState
import com.midnight.kuira.core.indexer.repository.DustRepository
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Unit tests for DustActionsBuilder API contract and data classes.
 *
 * **IMPORTANT: JNI Testing Limitation**
 * DustActionsBuilder depends on FeeCalculator which uses JNI. Full integration tests
 * require native libraries to be loaded, so they must run as instrumented tests.
 *
 * **Testing Strategy:**
 * - Unit tests (this file): Data classes, rollback/confirm logic
 * - Instrumented tests: Full integration with fee calculation and state management
 *
 * **See:** `/core/ledger/src/androidTest/.../fee/DustActionsBuilderInstrumentedTest.kt`
 * for full end-to-end integration tests.
 */
class DustActionsBuilderTest {

    private lateinit var dustRepository: DustRepository

    @Before
    fun setup() {
        dustRepository = mockk(relaxed = true)
    }

    // ==================== Helper Functions ====================

    private fun createDustToken(
        nullifier: String,
        valueSpecks: Long,
        state: UtxoState = UtxoState.AVAILABLE
    ): DustTokenEntity {
        return DustTokenEntity(
            nullifier = nullifier,
            address = "mn_addr_test1...",
            initialValue = valueSpecks.toString(),
            creationTimeMillis = System.currentTimeMillis(),
            nightUtxoId = "test_utxo",
            nightValueStars = "1000000",
            dustCapacitySpecks = "5000000",
            generationRatePerSecond = "8267",
            state = state
        )
    }

    // ==================== Data Class Tests ====================

    @Test
    fun `given DustActions with utxoIndices when isSuccess then returns true`() {
        // Given: Actions with utxoIndices
        val mockSpend = DustSpendCreator.DustSpend(
            vFee = BigInteger.valueOf(100),
            oldNullifier = "null1",
            newCommitment = "commit1",
            proof = "proof1"
        )

        val actions = DustActionsBuilder.DustActions(
            spends = listOf(mockSpend),
            selectedCoins = emptyList(),
            totalFee = BigInteger.valueOf(100),
            change = BigInteger.ZERO,
            utxoIndices = listOf(0)  // Non-empty = success
        )

        // When/Then
        assertTrue("Should be success with utxoIndices", actions.isSuccess())
    }

    @Test
    fun `given DustActions without utxoIndices when isSuccess then returns false`() {
        // Given: Actions without utxoIndices
        val actions = DustActionsBuilder.DustActions(
            spends = emptyList(),
            selectedCoins = emptyList(),
            totalFee = BigInteger.ZERO,
            change = BigInteger.ZERO,
            utxoIndices = emptyList()  // Empty = not success
        )

        // When/Then
        assertFalse("Should not be success without utxoIndices", actions.isSuccess())
    }

    @Test
    fun `given DustActions when getNullifiers then returns nullifier list`() {
        // Given: Actions with selected coins
        val coins = listOf(
            createDustToken("null1", 100),
            createDustToken("null2", 200)
        )

        val actions = DustActionsBuilder.DustActions(
            spends = emptyList(),
            selectedCoins = coins,
            totalFee = BigInteger.ZERO,
            change = BigInteger.ZERO,
            utxoIndices = listOf(0, 1)
        )

        // When
        val nullifiers = actions.getNullifiers()

        // Then
        assertEquals("Should have 2 nullifiers", 2, nullifiers.size)
        assertTrue("Should contain null1", nullifiers.contains("null1"))
        assertTrue("Should contain null2", nullifiers.contains("null2"))
    }

    // ==================== Rollback and Confirm Tests ====================

    /**
     * Note: Rollback/confirm tests require instrumented testing due to JNI dependencies.
     * See DustActionsBuilderInstrumentedTest.kt for full integration tests.
     */
}
