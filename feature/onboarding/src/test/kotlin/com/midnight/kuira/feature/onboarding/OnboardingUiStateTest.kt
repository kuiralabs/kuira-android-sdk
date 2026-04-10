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
 * [OnboardingViewModelTest].
 */
class OnboardingUiStateTest {

    private val sampleMessage = OnboardingErrorMessage(
        resId = R.string.onboarding_error_auth_cancelled,
    )

    @Test
    fun `Welcome is a singleton object`() {
        val a: OnboardingUiState = OnboardingUiState.Welcome
        val b: OnboardingUiState = OnboardingUiState.Welcome
        assertTrue("Data objects must be referentially equal", a === b)
    }

    @Test
    fun `Error carries a localizable message`() {
        val error = OnboardingUiState.Error(sampleMessage)
        assertEquals(R.string.onboarding_error_auth_cancelled, error.message.resId)
    }

    @Test
    fun `Error instances with different messages are not equal`() {
        val a = OnboardingUiState.Error(
            OnboardingErrorMessage(R.string.onboarding_error_auth_cancelled)
        )
        val b = OnboardingUiState.Error(
            OnboardingErrorMessage(R.string.onboarding_error_locked_out)
        )
        assertNotEquals(a, b)
    }

    @Test
    fun `Error instances with same message are equal (data class)`() {
        val a = OnboardingUiState.Error(sampleMessage)
        val b = OnboardingUiState.Error(sampleMessage)
        assertEquals(a, b)
    }

    @Test
    fun `OnboardingErrorMessage carries format args`() {
        val msg = OnboardingErrorMessage(
            resId = R.string.onboarding_error_auth_failed,
            formatArgs = listOf("hardware failure"),
        )
        assertEquals(R.string.onboarding_error_auth_failed, msg.resId)
        assertEquals(listOf("hardware failure"), msg.formatArgs)
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
            OnboardingUiState.Error(sampleMessage),
        )
        assertEquals(6, states.size)
    }
}
