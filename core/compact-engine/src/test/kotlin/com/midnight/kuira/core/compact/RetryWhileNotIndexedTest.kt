// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.compact

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A contract the node has finalized is not in the indexer the same instant, so a read or a
 * call issued right after a deploy asks for something that does not exist yet. The window is
 * not a fixed length — PreProd outlasts the three-to-five seconds the recipe suggested, which
 * is why kuira-android-sdk#4 had to write its own backoff instead of trusting a sleep.
 */
class RetryWhileNotIndexedTest {

    private fun notIndexed() =
        ContractCallException.StateFetchFailed("contract not found in indexer")

    @Test
    fun `returns the state once the indexer catches up`() = runTest {
        var attempts = 0
        val state = retryWhileNotIndexed(timeoutMs = 30_000, pollMs = 2_000) {
            attempts++
            if (attempts < 4) throw notIndexed() else "deadbeef"
        }
        assertEquals("deadbeef", state)
        assertEquals("should have retried until it appeared", 4, attempts)
    }

    @Test
    fun `returns immediately when the contract is already there`() = runTest {
        var attempts = 0
        val state = retryWhileNotIndexed(timeoutMs = 30_000, pollMs = 2_000) {
            attempts++
            "cafe"
        }
        assertEquals("cafe", state)
        assertEquals("no retry when the first fetch succeeds", 1, attempts)
    }

    @Test
    fun `surfaces the indexer's own failure when the window runs out`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            retryWhileNotIndexed(timeoutMs = 10_000, pollMs = 2_000) {
                attempts++
                throw notIndexed()
            }
        }.exceptionOrNull()

        assertTrue(
            "the caller should see the indexer's error, not a generic timeout",
            thrown is ContractCallException.StateFetchFailed,
        )
        assertEquals("contract not found in indexer", thrown?.message)
        assertTrue("should have retried more than once", attempts > 1)
    }

    @Test
    fun `a zero budget fetches exactly once and fails fast`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            retryWhileNotIndexed(timeoutMs = 0, pollMs = 2_000) {
                attempts++
                throw notIndexed()
            }
        }.exceptionOrNull()

        assertTrue(thrown is ContractCallException.StateFetchFailed)
        assertEquals("zero budget must not retry", 1, attempts)
    }

    @Test
    fun `an unrelated failure is not retried`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            retryWhileNotIndexed(timeoutMs = 30_000, pollMs = 2_000) {
                attempts++
                throw ContractCallException.CircuitExecutionFailed("bad witness")
            }
        }.exceptionOrNull()

        assertTrue(thrown is ContractCallException.CircuitExecutionFailed)
        assertEquals("only not-yet-indexed is worth waiting on", 1, attempts)
    }
}
