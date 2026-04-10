// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BiometricGate] exception types.
 *
 * Note: The actual BiometricPrompt flow requires a FragmentActivity and
 * real biometric hardware — those are tested via instrumentation tests
 * and manual device testing.
 *
 * These tests verify the exception contract — each error type is distinct
 * and carries the system error message.
 */
class BiometricGateTest {

    @Test
    fun `AuthenticationCancelledException carries message`() {
        val exception = AuthenticationCancelledException("User cancelled")
        assertEquals("User cancelled", exception.message)
        assertTrue(exception is Exception)
    }

    @Test
    fun `AuthenticationFailedException carries message`() {
        val exception = AuthenticationFailedException("[7] Hardware error")
        assertEquals("[7] Hardware error", exception.message)
        assertTrue(exception is Exception)
    }

    @Test
    fun `AuthenticationLockedOutException carries message`() {
        val exception = AuthenticationLockedOutException("Too many attempts")
        assertEquals("Too many attempts", exception.message)
        assertTrue(exception is Exception)
    }

    @Test
    fun `AuthenticationPermanentlyLockedOutException carries message`() {
        val exception = AuthenticationPermanentlyLockedOutException("Permanently locked out")
        assertEquals("Permanently locked out", exception.message)
        assertTrue(exception is Exception)
    }

    @Test
    fun `all authentication exceptions are distinct types`() {
        // Each exception type should be catchable independently so the UI
        // can show appropriate messages (cancellation vs lockout vs failure).
        val cancelled: Exception = AuthenticationCancelledException("c")
        val lockedOut: Exception = AuthenticationLockedOutException("l")
        val permLockedOut: Exception = AuthenticationPermanentlyLockedOutException("p")
        val failed: Exception = AuthenticationFailedException("f")

        assertTrue(cancelled is AuthenticationCancelledException)
        assertTrue(cancelled !is AuthenticationLockedOutException)
        assertTrue(cancelled !is AuthenticationFailedException)

        assertTrue(lockedOut is AuthenticationLockedOutException)
        assertTrue(lockedOut !is AuthenticationPermanentlyLockedOutException)
        assertTrue(lockedOut !is AuthenticationFailedException)

        assertTrue(permLockedOut is AuthenticationPermanentlyLockedOutException)
        assertTrue(permLockedOut !is AuthenticationLockedOutException)

        assertTrue(failed is AuthenticationFailedException)
        assertTrue(failed !is AuthenticationCancelledException)
    }

}
