// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the wallet-gate check.
 *
 * Simple three-state machine:
 * - [Loading]: checking disk for encrypted seed (very brief, ~1 frame)
 * - [NoWallet]: no seed stored — onboarding must run
 * - [Ready]: seed exists — the app can proceed normally
 */
sealed interface WalletGateState {
    data object Loading : WalletGateState
    data object NoWallet : WalletGateState
    data object Ready : WalletGateState
}

/**
 * ViewModel behind [WalletGate]. Checks `SeedVault.hasSeed()` on creation and
 * exposes the result as a [StateFlow].
 *
 * Exposes [onWalletCreated] so the onboarding flow can notify the gate that
 * the seed is now present — we flip to [WalletGateState.Ready] without
 * needing to re-read the disk.
 */
@HiltViewModel
class WalletGateViewModel @Inject constructor(
    private val seedVault: SeedVault,
) : ViewModel() {

    private val _state = MutableStateFlow<WalletGateState>(WalletGateState.Loading)
    val state: StateFlow<WalletGateState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = if (seedVault.hasSeed()) {
                WalletGateState.Ready
            } else {
                WalletGateState.NoWallet
            }
        }
    }

    /**
     * Called by the onboarding flow once the wallet has been created or restored.
     */
    fun onWalletCreated() {
        _state.value = WalletGateState.Ready
    }

    /**
     * Called after a wallet wipe completes. Flips the gate back to [WalletGateState.NoWallet]
     * so the onboarding flow re-appears. The NavHost inside AppNavigation exits composition
     * and recomposes fresh when the user forges a new sigil.
     */
    fun onWalletWiped() {
        _state.value = WalletGateState.NoWallet
    }
}

/**
 * Gates the main app content behind the onboarding flow.
 *
 * On first launch (or after a wallet reset), shows [OnboardingScreen]. Once
 * onboarding completes successfully, the [content] slot is rendered and
 * acts as the normal app entry point.
 *
 * **Usage (from MainActivity):**
 * ```kotlin
 * setContent {
 *     KuiraTheme {
 *         WalletGate(activity = this) { onWalletWiped ->
 *             AppNavigation(onWalletWiped = onWalletWiped)
 *         }
 *     }
 * }
 * ```
 *
 * @param activity The hosting FragmentActivity — required by BiometricPrompt
 *   inside the onboarding flow.
 * @param content The actual app content, shown only after a wallet exists.
 *   Receives an `onWalletWiped` callback that flips the gate back to
 *   [WalletGateState.NoWallet] after a wallet wipe.
 */
@Composable
fun WalletGate(
    activity: FragmentActivity,
    viewModel: WalletGateViewModel = hiltViewModel(),
    content: @Composable (onWalletWiped: () -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    when (state) {
        WalletGateState.Loading -> {
            // Brief void flash while we check the disk. No spinner — the
            // disk read is fast enough that a spinner would flash annoyingly.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MidnightColors.Void),
            )
        }
        WalletGateState.NoWallet -> {
            OnboardingScreen(
                activity = activity,
                onWalletReady = { viewModel.onWalletCreated() },
            )
        }
        WalletGateState.Ready -> {
            content { viewModel.onWalletWiped() }
        }
    }
}
