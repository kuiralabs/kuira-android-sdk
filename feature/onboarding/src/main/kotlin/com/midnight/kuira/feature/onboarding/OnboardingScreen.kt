// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.feature.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.midnight.kuira.core.designsystem.component.DuskBulletLine
import com.midnight.kuira.core.designsystem.component.DuskButtonRow
import com.midnight.kuira.core.designsystem.component.DuskPrimaryButton
import com.midnight.kuira.core.designsystem.component.DuskScaffold
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * Entry point for the wallet onboarding flow.
 *
 * Uses [DuskScaffold] + reusable Dusk components so it stays visually
 * consistent with the approval UI and future auth screens.
 *
 * @param onWalletReady Invoked when the wallet is created and the user
 *   should be taken to the home screen.
 */
@Composable
fun OnboardingScreen(
    onWalletReady: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Skip onboarding if a wallet already exists
    LaunchedEffect(Unit) {
        if (viewModel.hasExistingWallet()) {
            onWalletReady()
        }
    }

    // Navigate on success
    LaunchedEffect(uiState) {
        if (uiState is OnboardingUiState.Success) {
            onWalletReady()
        }
    }

    DuskScaffold(
        sheet = { contentAlpha ->
            Column(modifier = Modifier.alpha(contentAlpha)) {
                when (val state = uiState) {
                    is OnboardingUiState.Welcome ->
                        WelcomeContent(
                            onCreate = {
                                val activity = context as? FragmentActivity
                                    ?: error("OnboardingScreen must be hosted in a FragmentActivity")
                                viewModel.createWallet(activity)
                            },
                        )

                    is OnboardingUiState.CheckingAuth ->
                        StatusContent(
                            label = "preparing",
                            headline = "checking device",
                            detail = "verifying secure hardware is available",
                        )

                    is OnboardingUiState.NeedsAuthSetup ->
                        NeedsAuthSetupContent(
                            onOpenSettings = {
                                val intent = Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                                    putExtra(
                                        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                    )
                                }
                                context.startActivity(intent)
                            },
                            onBack = viewModel::reset,
                        )

                    is OnboardingUiState.CreatingWallet ->
                        StatusContent(
                            label = "securing",
                            headline = "creating wallet",
                            detail = "your keys are being encrypted with hardware-backed security",
                        )

                    is OnboardingUiState.Success ->
                        StatusContent(
                            label = "ready",
                            headline = "wallet created",
                            detail = "opening your wallet…",
                        )

                    is OnboardingUiState.Error ->
                        ErrorContent(
                            message = state.message,
                            onRetry = viewModel::reset,
                        )
                }
            }
        },
    )
}

@Composable
private fun WelcomeContent(onCreate: () -> Unit) {
    Text(
        text = "welcome",
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        letterSpacing = 3.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = "your phone is\nyour hardware wallet",
        color = MidnightColors.Light,
        fontSize = 22.sp,
        fontWeight = FontWeight.W300,
        lineHeight = 28.sp,
    )
    Spacer(modifier = Modifier.height(24.dp))

    DuskBulletLine("keys encrypted in hardware")
    DuskBulletLine("biometric-gated every signature")
    DuskBulletLine("no seed phrase to write down")

    Spacer(modifier = Modifier.height(48.dp))

    DuskPrimaryButton(
        text = "create wallet",
        onClick = onCreate,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatusContent(label: String, headline: String, detail: String) {
    Text(
        text = label,
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        letterSpacing = 3.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = headline,
        color = MidnightColors.Light,
        fontSize = 18.sp,
        fontWeight = FontWeight.W300,
    )
    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = detail,
        color = MidnightColors.LightMuted,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    Spacer(modifier = Modifier.height(48.dp))
}

@Composable
private fun NeedsAuthSetupContent(
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        text = "setup required",
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        letterSpacing = 3.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = "enable device security",
        color = MidnightColors.Light,
        fontSize = 18.sp,
        fontWeight = FontWeight.W300,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "kuira needs a biometric or screen lock to encrypt your wallet keys",
        color = MidnightColors.LightMuted,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    Spacer(modifier = Modifier.height(48.dp))

    DuskButtonRow(
        secondaryText = "back",
        primaryText = "open settings",
        onSecondary = onBack,
        onPrimary = onOpenSettings,
    )
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Text(
        text = "something went wrong",
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        letterSpacing = 3.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = message,
        color = MidnightColors.Light,
        fontSize = 16.sp,
        fontWeight = FontWeight.W300,
        lineHeight = 22.sp,
    )

    Spacer(modifier = Modifier.height(48.dp))

    DuskPrimaryButton(
        text = "try again",
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
    )
}
