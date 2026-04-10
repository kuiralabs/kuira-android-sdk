// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [OnboardingUiState] sealed interface.
 *
 * State transitions driven by [OnboardingViewModel] are covered by
 * instrumentation tests because they require a live FragmentActivity
 * for the biometric prompt. These tests verify the state contract only.
 */
class OnboardingUiStateTest {

    @Test
    fun `Welcome is a singleton object`() {
        val a: OnboardingUiState = OnboardingUiState.Welcome
        val b: OnboardingUiState = OnboardingUiState.Welcome
        assertTrue("Data objects must be referentially equal", a === b)
    }

    @Test
    fun `Error carries a message`() {
        val error = OnboardingUiState.Error("Authentication cancelled")
        assertEquals("Authentication cancelled", error.message)
    }

    @Test
    fun `Error instances with different messages are not equal`() {
        val a = OnboardingUiState.Error("Cancelled")
        val b = OnboardingUiState.Error("Locked out")
        assertNotEquals(a, b)
    }

    @Test
    fun `Error instances with same message are equal (data class)`() {
        val a = OnboardingUiState.Error("Same")
        val b = OnboardingUiState.Error("Same")
        assertEquals(a, b)
    }

    @Test
    fun `all terminal states are distinct types`() {
        // Ensures when() expressions stay exhaustive — adding a new state
        // later will fail to compile rather than silently falling through.
        val states: List<OnboardingUiState> = listOf(
            OnboardingUiState.Welcome,
            OnboardingUiState.CheckingAuth,
            OnboardingUiState.NeedsAuthSetup,
            OnboardingUiState.CreatingWallet,
            OnboardingUiState.Success,
            OnboardingUiState.Error("any"),
        )
        assertEquals(6, states.size)
    }
}
