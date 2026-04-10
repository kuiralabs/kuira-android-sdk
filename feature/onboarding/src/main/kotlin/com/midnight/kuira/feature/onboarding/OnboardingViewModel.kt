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
 *   1. Generate BIP-39 mnemonic
 *   2. Derive 64-byte BIP-39 seed (PBKDF2)
 *   3. Generate Keystore master key (StrongBox → TEE fallback)
 *   4. Store encrypted seed via [SeedVault] (triggers biometric prompt)
 *   5. Wipe plaintext from memory
 * - Expose a [StateFlow] of [OnboardingUiState] for the UI
 *
 * **Security notes:**
 * - The plaintext mnemonic only exists as a local `String` inside
 *   [createWallet] for the time it takes to derive entropy + seed.
 *   String immutability makes wiping impossible, so we minimize its lifetime.
 * - Entropy and seed byte arrays are wrapped in [PlaintextSeed] which has
 *   a [PlaintextSeed.wipe] method — called in the `finally` block.
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
     * Whether a wallet already exists on this device.
     * Callers use this to skip onboarding for returning users.
     */
    fun hasExistingWallet(): Boolean = seedVault.hasSeed()

    /**
     * Orchestrates the full wallet creation flow.
     *
     * Caller must pass a live [FragmentActivity] so we can show the
     * biometric prompt. Navigation to the home screen happens via the
     * [OnboardingUiState.Success] state emission.
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

            try {
                if (!walletKeyManager.hasKey()) {
                    walletKeyManager.generateKey()
                }

                val mnemonic = BIP39.generateMnemonic(wordCount = 24)
                val entropy = BIP39.mnemonicToEntropy(mnemonic)
                val seed = BIP39.mnemonicToSeed(mnemonic)
                val plaintextSeed = PlaintextSeed(entropy, seed)

                try {
                    seedVault.storeSeed(activity, plaintextSeed)
                    _uiState.value = OnboardingUiState.Success
                } finally {
                    // Wipe the plaintext as soon as it's persisted.
                    plaintextSeed.wipe()
                }
            } catch (e: AuthenticationCancelledException) {
                _uiState.value = OnboardingUiState.Error("Authentication cancelled")
            } catch (e: AuthenticationLockedOutException) {
                _uiState.value = OnboardingUiState.Error("Too many attempts. Try again in a moment.")
            } catch (e: AuthenticationPermanentlyLockedOutException) {
                _uiState.value = OnboardingUiState.Error("Biometric locked. Use PIN to unlock.")
            } catch (e: AuthenticationFailedException) {
                _uiState.value = OnboardingUiState.Error("Authentication failed: ${e.message}")
            } catch (e: Exception) {
                _uiState.value = OnboardingUiState.Error("Could not create wallet: ${e.message}")
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
