// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BiometricGate] exception types and [AuthenticatedCipher].
 *
 * Note: The actual BiometricPrompt flow requires a FragmentActivity and
 * real biometric hardware — those are tested via instrumentation tests
 * and manual device testing.
 *
 * These tests verify the exception contract and data class behavior.
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
        val exception = AuthenticationFailedException("[7] Too many attempts")
        assertEquals("[7] Too many attempts", exception.message)
        assertTrue(exception is Exception)
    }

    @Test
    fun `AuthenticationCancelledException is distinct from AuthenticationFailedException`() {
        val cancelled = AuthenticationCancelledException("cancelled")
        val failed = AuthenticationFailedException("failed")

        // These should be catchable independently
        assertTrue(cancelled !is AuthenticationFailedException)
        assertTrue(failed !is AuthenticationCancelledException)
    }

    @Test
    fun `AuthenticatedCipher is a data class with cipher property`() {
        // Verify the data class contract — cipher is accessible
        // Can't create a real Cipher in unit tests, but verify the type exists
        assertNotNull(AuthenticatedCipher::class.java)
        assertEquals(1, AuthenticatedCipher::class.java.declaredFields.count { !it.isSynthetic })
    }
}
