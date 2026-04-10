// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.feature.onboarding

import androidx.annotation.StringRes

/**
 * Error message that carries a string resource ID (for localization) plus
 * optional format args. The UI resolves it via `stringResource(id, *args)`.
 *
 * This keeps user-facing copy out of the ViewModel entirely — the ViewModel
 * references resources by ID only, the UI layer handles the actual text.
 */
data class OnboardingErrorMessage(
    @param:StringRes val resId: Int,
    val formatArgs: List<String> = emptyList(),
)

/**
 * State machine for the wallet onboarding flow.
 *
 * Transitions (happy path for Create Wallet):
 * ```
 *   Welcome → CheckingAuth → CreatingWallet → Success
 * ```
 *
 * Transitions (error paths):
 * ```
 *   CheckingAuth → NeedsAuthSetup (user must enroll biometric/PIN)
 *   CreatingWallet → Error (biometric cancelled, Keystore failure, etc.)
 * ```
 */
sealed interface OnboardingUiState {

    /**
     * Initial screen: welcome message, "Create Wallet" button.
     */
    data object Welcome : OnboardingUiState

    /**
     * Transient state while we query BiometricManager to see if the device
     * is ready for wallet creation.
     */
    data object CheckingAuth : OnboardingUiState

    /**
     * The user has no biometric or screen lock configured. They need to
     * enroll one via system settings before we can encrypt their seed.
     */
    data object NeedsAuthSetup : OnboardingUiState

    /**
     * Wallet creation is in progress. The biometric prompt is showing
     * (or about to show) and the user is authenticating.
     */
    data object CreatingWallet : OnboardingUiState

    /**
     * Wallet created successfully. Caller should navigate to the home screen.
     */
    data object Success : OnboardingUiState

    /**
     * Creation failed. The user should be able to retry.
     * The [message] references a string resource for localization.
     */
    data class Error(val message: OnboardingErrorMessage) : OnboardingUiState
}
