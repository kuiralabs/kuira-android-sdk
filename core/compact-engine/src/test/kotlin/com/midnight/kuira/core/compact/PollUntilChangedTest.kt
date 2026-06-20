// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.compact

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `IntentAlreadyExists` (custom error 182) fix: `MidnightContract.call` waits until the
 * indexer reflects a call (the contract state changes) before returning, so a rapid follow-up call
 * reads fresh state instead of rebuilding the identical, replay-rejected intent.
 */
class PollUntilChangedTest {

    @Test
    fun `returns true once the value changes`() = runTest {
        var n = 0
        val changed = pollUntilChanged(before = "A", timeoutMs = 30_000, pollMs = 1_000) {
            n++
            if (n < 3) "A" else "B" // changes on the 3rd poll
        }
        assertTrue(changed)
    }

    @Test
    fun `returns false on timeout when the value never changes`() = runTest {
        val changed = pollUntilChanged(before = "A", timeoutMs = 5_000, pollMs = 1_000) { "A" }
        assertFalse(changed)
    }

    @Test
    fun `keeps polling through a transient fetch error`() = runTest {
        var n = 0
        val changed = pollUntilChanged(before = "A", timeoutMs = 30_000, pollMs = 1_000) {
            n++
            when {
                n == 1 -> throw RuntimeException("transient indexer hiccup")
                n < 3 -> "A"
                else -> "B"
            }
        }
        assertTrue(changed)
    }
}
