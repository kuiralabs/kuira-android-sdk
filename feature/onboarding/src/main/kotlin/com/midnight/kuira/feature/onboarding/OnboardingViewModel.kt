// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.feature.onboarding

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.auth.AuthenticationCancelledException
import com.midnight.kuira.core.auth.AuthenticationFailedException
import com.midnight.kuira.core.auth.AuthenticationLockedOutException
import com.midnight.kuira.core.auth.AuthenticationPermanentlyLockedOutException
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SecurityCapabilities
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.crypto.bip39.BIP39
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the wallet onboarding flow.
 *
 * **Responsibilities:**
 * - Check device auth capabilities before creating a wallet
 * - Orchestrate the wallet creation sequence:
 *   1. Generate Keystore master key (StrongBox → TEE fallback)
 *   2. Trigger biometric prompt via [SeedVault.storeSeed]
 *   3. After auth succeeds: generate BIP-39 mnemonic + derive entropy + seed
 *   4. Store encrypted seed; plaintext is wiped immediately
 * - Expose a [StateFlow] of [OnboardingUiState] for the UI
 *
 * **Lifecycle note:**
 * [createWallet] takes a live [FragmentActivity] captured in the coroutine
 * closure for the duration of the biometric prompt. Configuration changes
 * (rotation, backgrounding) during the 1–2 seconds the prompt is visible
 * will result in the operation failing when the activity is torn down.
 * This is acceptable for the onboarding flow because it's short-lived and
 * the user can retry from a clean state via [reset].
 *
 * **Security notes:**
 * - The plaintext mnemonic is generated LAZILY inside [SeedVault.storeSeed],
 *   only after biometric auth succeeds. This minimizes memory exposure.
 * - Plaintext is wiped automatically inside SeedVault's finally block.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val walletKeyManager: WalletKeyManager,
    private val seedVault: SeedVault,
    private val securityCapabilities: SecurityCapabilities,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Welcome)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /**
     * Suspend check for whether a wallet already exists on this device.
     * Callers use this to skip onboarding for returning users.
     */
    suspend fun hasExistingWallet(): Boolean = seedVault.hasSeed()

    /**
     * Orchestrates the full wallet creation flow.
     *
     * Navigation to the home screen happens via the [OnboardingUiState.Success]
     * state emission.
     */
    fun createWallet(activity: FragmentActivity) {
        viewModelScope.launch {
            // Phase 1: verify the device can authenticate
            _uiState.value = OnboardingUiState.CheckingAuth

            if (!securityCapabilities.hasAnyAuthenticator) {
                _uiState.value = OnboardingUiState.NeedsAuthSetup
                return@launch
            }

            // Phase 2: create the wallet
            _uiState.value = OnboardingUiState.CreatingWallet

            // Track whether we created the Keystore key this attempt so we can
            // clean up if storeSeed fails. Prevents orphaning a Keystore key
            // with no corresponding seed file.
            val wasKeyCreatedThisAttempt: Boolean = if (!walletKeyManager.hasKey()) {
                try {
                    walletKeyManager.generateKey()
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _uiState.value = OnboardingUiState.Error(
                        OnboardingErrorMessage(
                            resId = R.string.onboarding_error_generic,
                            formatArgs = listOf(e.message ?: "key generation failed"),
                        )
                    )
                    return@launch
                }
            } else {
                false
            }

            try {
                seedVault.storeSeed(activity) {
                    // Produced ONLY after biometric auth succeeds
                    val mnemonic = BIP39.generateMnemonic(wordCount = 24)
                    val entropy = BIP39.mnemonicToEntropy(mnemonic)
                    val seed = BIP39.mnemonicToSeed(mnemonic)
                    PlaintextSeed(entropy, seed)
                }
                _uiState.value = OnboardingUiState.Success
            } catch (e: CancellationException) {
                // Coroutine cancelled (e.g., ViewModel cleared, scope cancelled).
                // Must rethrow to preserve structured concurrency — do NOT treat
                // as an error state.
                if (wasKeyCreatedThisAttempt) walletKeyManager.deleteKey()
                throw e
            } catch (e: AuthenticationCancelledException) {
                if (wasKeyCreatedThisAttempt) walletKeyManager.deleteKey()
                _uiState.value = OnboardingUiState.Error(
                    OnboardingErrorMessage(R.string.onboarding_error_auth_cancelled)
                )
            } catch (e: AuthenticationLockedOutException) {
                if (wasKeyCreatedThisAttempt) walletKeyManager.deleteKey()
                _uiState.value = OnboardingUiState.Error(
                    OnboardingErrorMessage(R.string.onboarding_error_locked_out)
                )
            } catch (e: AuthenticationPermanentlyLockedOutException) {
                if (wasKeyCreatedThisAttempt) walletKeyManager.deleteKey()
                _uiState.value = OnboardingUiState.Error(
                    OnboardingErrorMessage(R.string.onboarding_error_permanently_locked)
                )
            } catch (e: AuthenticationFailedException) {
                if (wasKeyCreatedThisAttempt) walletKeyManager.deleteKey()
                _uiState.value = OnboardingUiState.Error(
                    OnboardingErrorMessage(
                        resId = R.string.onboarding_error_auth_failed,
                        formatArgs = listOf(e.message.orEmpty()),
                    )
                )
            } catch (e: Exception) {
                if (wasKeyCreatedThisAttempt) walletKeyManager.deleteKey()
                _uiState.value = OnboardingUiState.Error(
                    OnboardingErrorMessage(
                        resId = R.string.onboarding_error_generic,
                        formatArgs = listOf(e.message ?: "unknown error"),
                    )
                )
            }
        }
    }

    /**
     * Return to the welcome screen after an error. Resets the state machine
     * so the user can retry.
     */
    fun reset() {
        _uiState.value = OnboardingUiState.Welcome
    }
}
