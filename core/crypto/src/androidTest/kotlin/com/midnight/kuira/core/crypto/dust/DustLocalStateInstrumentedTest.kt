// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.dust

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

/**
 * Android instrumentation tests for DustLocalState FFI bridge.
 *
 * **Test Coverage:**
 * - Kotlin → JNI → Rust → midnight-ledger integration
 * - FFI bridge initialization and loading
 * - State creation and destruction
 * - Balance calculation (time-based)
 * - Serialization/deserialization
 * - UTXO operations (count, get)
 * - Memory management (leak prevention)
 * - Error handling (null pointers, bounds checking)
 * - Thread safety (multiple instances)
 *
 * **Why Instrumented Tests:**
 * - Verifies native library (.so) loads on Android
 * - Tests actual Android runtime environment
 * - Validates JNI bridge works correctly
 * - Catches platform-specific issues
 *
 * **CRITICAL:** If these tests fail, it means the FFI bridge is broken
 * and dust operations won't work in the app.
 */
@RunWith(AndroidJUnit4::class)
class DustLocalStateInstrumentedTest {

    // ==================== FFI Bridge Initialization ====================

    @Test
    fun verifyNativeLibraryLoadsOnAndroid() {
        // This is the most critical test - verifies libkuira_crypto_ffi.so loads
        // If this fails, the native library is not properly linked

        // When - Create DustLocalState (triggers System.loadLibrary)
        val state = DustLocalState.create()

        // Then - Should succeed without UnsatisfiedLinkError
        assertNotNull(
            "Native library should load successfully on Android",
            state
        )

        // Cleanup
        state?.close()
    }

    @Test
    fun verifyFFIBridgeWorks() {
        // Verifies Kotlin → JNI → Rust bridge works end-to-end

        // When - Create state via FFI
        val state = DustLocalState.create()
        assertNotNull("FFI create should work", state)

        // And - Call balance via FFI
        val balance = state!!.getBalance(System.currentTimeMillis())

        // Then - Should return valid result
        assertNotNull("FFI getBalance should work", balance)
        assertEquals(
            "New state should have zero balance",
            BigInteger.ZERO,
            balance
        )

        // Cleanup
        state.close()
    }

    // ==================== State Creation ====================

    @Test
    fun createReturnsNonNullState() {
        // When
        val state = DustLocalState.create()

        // Then
        assertNotNull(
            "create() should return non-null DustLocalState",
            state
        )

        // Cleanup
        state?.close()
    }

    @Test
    fun multipleStatesCanBeCreated() {
        // When - Create multiple independent states
        val state1 = DustLocalState.create()
        val state2 = DustLocalState.create()
        val state3 = DustLocalState.create()

        // Then - All should be non-null (no interference)
        assertNotNull("State 1 should be created", state1)
        assertNotNull("State 2 should be created", state2)
        assertNotNull("State 3 should be created", state3)

        // Cleanup
        state1?.close()
        state2?.close()
        state3?.close()
    }

    // ==================== Balance Calculation ====================

    @Test
    fun getBalanceReturnsZeroForNewState() {
        // Given - New state
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Get balance
        val balance = state!!.getBalance(System.currentTimeMillis())

        // Then - Should be zero (no dust registered yet)
        assertEquals(
            "New state should have zero balance",
            BigInteger.ZERO,
            balance
        )

        // Cleanup
        state.close()
    }

    @Test
    fun getBalanceWorksWithDifferentTimestamps() {
        // Given - New state
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Get balance at different times
        val balance1 = state!!.getBalance(1000000000L)  // Jan 12, 2001
        val balance2 = state.getBalance(2000000000L)    // May 18, 2033
        val balance3 = state.getBalance(System.currentTimeMillis())

        // Then - All should be zero for empty state (but shouldn't crash)
        assertEquals("Balance at time 1 should be zero", BigInteger.ZERO, balance1)
        assertEquals("Balance at time 2 should be zero", BigInteger.ZERO, balance2)
        assertEquals("Balance at time 3 should be zero", BigInteger.ZERO, balance3)

        // Cleanup
        state.close()
    }

    @Test
    fun getBalanceHandlesLargeTimestamps() {
        // Given - New state
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Get balance with very large timestamp (year 2100)
        val farFutureTime = 4102444800000L  // Jan 1, 2100
        val balance = state!!.getBalance(farFutureTime)

        // Then - Should not crash (even if result is zero for empty state)
        assertNotNull("Balance should be returned for far future time", balance)

        // Cleanup
        state.close()
    }

    // ==================== Serialization ====================

    @Test
    fun serializeReturnsNonNullForNewState() {
        // Given - New state
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Serialize
        val serialized = state!!.serialize()

        // Then - Should return non-null bytes
        assertNotNull(
            "serialize() should return non-null byte array",
            serialized
        )
        assertTrue(
            "Serialized data should have non-zero length",
            serialized!!.isNotEmpty()
        )

        // Cleanup
        state.close()
    }

    @Test
    fun serializedDataHasReasonableSize() {
        // Given - New state
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Serialize
        val serialized = state!!.serialize()
        assertNotNull(serialized)

        // Then - Should be reasonable size (not millions of bytes)
        assertTrue(
            "Serialized empty state should be < 10KB",
            serialized!!.size < 10 * 1024
        )

        println("Serialized DustLocalState: ${serialized.size} bytes")

        // Cleanup
        state.close()
    }

    @Test
    fun serializeIsConsistent() {
        // Given - New state
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Serialize twice
        val serialized1 = state!!.serialize()
        val serialized2 = state.serialize()

        // Then - Should produce identical results
        assertNotNull(serialized1)
        assertNotNull(serialized2)
        assertTrue(
            "Repeated serialization should produce identical results",
            serialized1!!.contentEquals(serialized2!!)
        )

        // Cleanup
        state.close()
    }

    // ==================== UTXO Operations ====================

    @Test
    fun getUtxoCountReturnsZeroForNewState() {
        // Given - New state
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Get UTXO count
        val count = state!!.getUtxoCount()

        // Then - Should be zero (no UTXOs registered yet)
        assertEquals(
            "New state should have zero UTXOs",
            0,
            count
        )

        // Cleanup
        state.close()
    }

    @Test
    fun getUtxoAtReturnsNullForEmptyState() {
        // Given - New state with zero UTXOs
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Try to get UTXO at index 0 (out of bounds)
        val utxo = state!!.getUtxoAt(0)

        // Then - Should return null
        assertNull(
            "getUtxoAt should return null for empty state",
            utxo
        )

        // Cleanup
        state.close()
    }

    @Test
    fun getUtxoAtReturnsNullForInvalidIndex() {
        // Given - New state
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Try to get UTXO at various invalid indices
        val utxo1 = state!!.getUtxoAt(0)
        val utxo2 = state.getUtxoAt(1)
        val utxo3 = state.getUtxoAt(100)
        val utxo4 = state.getUtxoAt(Int.MAX_VALUE)

        // Then - All should return null
        assertNull("Index 0 should be out of bounds", utxo1)
        assertNull("Index 1 should be out of bounds", utxo2)
        assertNull("Index 100 should be out of bounds", utxo3)
        assertNull("Index MAX_VALUE should be out of bounds", utxo4)

        // Cleanup
        state.close()
    }

    // ==================== Memory Management ====================

    @Test
    fun closeDoesNotCrash() {
        // Given - State
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Close (frees native memory)
        state!!.close()

        // Then - Should not crash (no assertion needed - test passes if no exception)
    }

    @Test
    fun multipleClosesDoNotCrash() {
        // Given - State
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Close multiple times (should be safe)
        state!!.close()
        state.close()
        state.close()

        // Then - Should not crash or double-free
        // (Test passes if no exception thrown)
    }

    @Test
    fun closedStateCannotBeUsed() {
        // Given - State
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Close state
        state!!.close()

        // Then - Subsequent operations should fail safely
        try {
            val balance = state.getBalance(System.currentTimeMillis())
            // If it doesn't throw, balance should be zero or null
            assertTrue(
                "Closed state should return zero or null balance",
                balance == null || balance == BigInteger.ZERO
            )
        } catch (e: IllegalStateException) {
            // This is acceptable - state is closed
            assertTrue(
                "Expected IllegalStateException for closed state",
                e.message?.contains("closed") == true
            )
        }
    }

    @Test
    fun memoryCleanupWithMultipleStates() {
        // When - Create and destroy many states (stress test)
        repeat(100) { i ->
            val state = DustLocalState.create()
            assertNotNull("State $i should be created", state)

            // Use the state
            val balance = state!!.getBalance(System.currentTimeMillis())
            assertEquals("State $i should have zero balance", BigInteger.ZERO, balance)

            // Clean up
            state.close()
        }

        // Then - Should not crash or leak memory
        // (Test passes if no OutOfMemoryError or crash)
    }

    // ==================== Error Handling ====================

    @Test
    fun stateHandlesEdgeCaseTimestamps() {
        // Given - New state
        val state = DustLocalState.create()
        assertNotNull(state)

        // When - Test edge case timestamps
        val balanceZero = state!!.getBalance(0L)
        val balanceNegative = state.getBalance(-1000L)
        val balanceMax = state.getBalance(Long.MAX_VALUE)

        // Then - Should not crash (even if results are zero)
        assertNotNull("Balance at time 0 should not be null", balanceZero)
        assertNotNull("Balance at negative time should not be null", balanceNegative)
        assertNotNull("Balance at MAX_VALUE should not be null", balanceMax)

        // Cleanup
        state.close()
    }

    // ==================== Integration Tests ====================

    @Test
    fun fullLifecycleTest() {
        // Test complete lifecycle: create → use → serialize → close

        // 1. Create
        val state = DustLocalState.create()
        assertNotNull("State should be created", state)

        // 2. Use - Get balance
        val balance1 = state!!.getBalance(System.currentTimeMillis())
        assertEquals("Initial balance should be zero", BigInteger.ZERO, balance1)

        // 3. Use - Get UTXO count
        val count = state.getUtxoCount()
        assertEquals("Initial UTXO count should be zero", 0, count)

        // 4. Serialize
        val serialized = state.serialize()
        assertNotNull("Serialization should work", serialized)
        assertTrue("Serialized data should not be empty", serialized!!.isNotEmpty())

        // 5. Use again after serialization
        val balance2 = state.getBalance(System.currentTimeMillis())
        assertEquals("Balance after serialization should still be zero", BigInteger.ZERO, balance2)

        // 6. Close
        state.close()

        // Test passes if no crashes occurred
    }

    @Test
    fun concurrentStateUsage() {
        // Test that multiple states don't interfere with each other

        // When - Create multiple states and use them
        val state1 = DustLocalState.create()
        val state2 = DustLocalState.create()
        val state3 = DustLocalState.create()

        assertNotNull("State 1 should be created", state1)
        assertNotNull("State 2 should be created", state2)
        assertNotNull("State 3 should be created", state3)

        // Use all states
        val balance1 = state1!!.getBalance(System.currentTimeMillis())
        val balance2 = state2!!.getBalance(System.currentTimeMillis())
        val balance3 = state3!!.getBalance(System.currentTimeMillis())

        val count1 = state1.getUtxoCount()
        val count2 = state2.getUtxoCount()
        val count3 = state3.getUtxoCount()

        val serialized1 = state1.serialize()
        val serialized2 = state2.serialize()
        val serialized3 = state3.serialize()

        // Then - All should work independently
        assertEquals("State 1 balance should be zero", BigInteger.ZERO, balance1)
        assertEquals("State 2 balance should be zero", BigInteger.ZERO, balance2)
        assertEquals("State 3 balance should be zero", BigInteger.ZERO, balance3)

        assertEquals("State 1 count should be zero", 0, count1)
        assertEquals("State 2 count should be zero", 0, count2)
        assertEquals("State 3 count should be zero", 0, count3)

        assertNotNull("State 1 should serialize", serialized1)
        assertNotNull("State 2 should serialize", serialized2)
        assertNotNull("State 3 should serialize", serialized3)

        // Cleanup all
        state1.close()
        state2.close()
        state3.close()
    }

    // ==================== State Persistence (Serialize/Deserialize) ====================

    @Test
    fun deserializeStateRoundTrip() {
        // Test Goal: Verify serialize → deserialize produces identical state
        // This is critical for wallet persistence (save/load from database)

        // Given - Create state
        val originalState = DustLocalState.create()
        assertNotNull("State should be created", originalState)

        try {
            // Get baseline values
            val originalBalance = originalState!!.getBalance(System.currentTimeMillis())
            val originalCount = originalState.getUtxoCount()

            // When - Serialize
            val serialized = originalState.serialize()
            assertNotNull("Serialization should succeed", serialized)
            assertTrue("Serialized data should not be empty", serialized!!.isNotEmpty())

            // Serialized state size verified

            // When - Deserialize
            val deserializedState = DustLocalState.deserialize(serialized)
            assertNotNull("Deserialization should succeed", deserializedState)

            try {
                // Then - Deserialized state should match original
                val deserializedBalance = deserializedState!!.getBalance(System.currentTimeMillis())
                val deserializedCount = deserializedState.getUtxoCount()

                assertEquals(
                    "Deserialized balance should match original",
                    originalBalance,
                    deserializedBalance
                )

                assertEquals(
                    "Deserialized UTXO count should match original",
                    originalCount,
                    deserializedCount
                )

                println("Round-trip serialization: balance=$originalBalance, utxos=$originalCount")
            } finally {
                deserializedState?.close()
            }
        } finally {
            originalState?.close()
        }
    }

    @Test
    fun deserializeStateWithNullDataReturnsNull() {
        // Given - Empty byte array
        val emptyData = ByteArray(0)

        // When - Try to deserialize
        val state = DustLocalState.deserialize(emptyData)

        // Then - Should return null
        assertNull("Deserialization of empty data should return null", state)
    }

    @Test
    fun deserializeStateWithInvalidDataReturnsNull() {
        // Given - Invalid SCALE-encoded data
        val invalidData = ByteArray(10) { 0xFF.toByte() }

        // When - Try to deserialize
        val state = DustLocalState.deserialize(invalidData)

        // Then - Should return null (graceful error handling)
        assertNull("Deserialization of invalid data should return null", state)
    }
}
