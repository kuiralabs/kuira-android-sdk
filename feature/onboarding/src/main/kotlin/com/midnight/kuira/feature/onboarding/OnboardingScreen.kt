// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.feature.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.midnight.kuira.core.designsystem.component.DuskBulletLine
import com.midnight.kuira.core.designsystem.component.DuskButtonRow
import com.midnight.kuira.core.designsystem.component.DuskPrimaryButton
import com.midnight.kuira.core.designsystem.component.DuskSecondaryButton
import com.midnight.kuira.core.designsystem.component.DuskScaffold
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * Entry point for the wallet onboarding flow.
 *
 * Uses [DuskScaffold] + reusable Dusk components so it stays visually
 * consistent with the approval UI and future auth screens.
 *
 * @param activity The hosting FragmentActivity, required by BiometricPrompt.
 *   Passed explicitly (rather than dug out of LocalContext) to make the
 *   host contract visible at the call site.
 * @param onWalletReady Invoked when the wallet is created and the user
 *   should be taken to the home screen.
 */
@Composable
fun OnboardingScreen(
    activity: FragmentActivity,
    onWalletReady: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Skip onboarding if a wallet already exists (suspend to avoid main-thread I/O)
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
                            onCreate = { viewModel.createWallet(activity) },
                            onRestore = viewModel::startRestore,
                        )

                    is OnboardingUiState.RestoreInput ->
                        RestoreContent(
                            state = state,
                            onInputChange = viewModel::updateRestoreInput,
                            onConfirm = { viewModel.confirmRestore(activity) },
                            onCancel = viewModel::reset,
                        )

                    is OnboardingUiState.CheckingAuth ->
                        StatusContent(
                            label = stringResource(R.string.onboarding_checking_label),
                            headline = stringResource(R.string.onboarding_checking_headline),
                            detail = stringResource(R.string.onboarding_checking_detail),
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
                            label = stringResource(R.string.onboarding_creating_label),
                            headline = stringResource(R.string.onboarding_creating_headline),
                            detail = stringResource(R.string.onboarding_creating_detail),
                        )

                    is OnboardingUiState.Success ->
                        StatusContent(
                            label = stringResource(R.string.onboarding_success_label),
                            headline = stringResource(R.string.onboarding_success_headline),
                            detail = stringResource(R.string.onboarding_success_detail),
                        )

                    is OnboardingUiState.Error ->
                        ErrorContent(
                            errorMessage = state.message,
                            onRetry = viewModel::reset,
                        )
                }
            }
        },
    )
}

@Composable
private fun WelcomeContent(
    onCreate: () -> Unit,
    onRestore: () -> Unit,
) {
    Text(
        text = stringResource(R.string.onboarding_welcome_label),
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        letterSpacing = 3.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = stringResource(R.string.onboarding_welcome_headline),
        color = MidnightColors.Light,
        fontSize = 22.sp,
        fontWeight = FontWeight.W300,
        lineHeight = 28.sp,
    )
    Spacer(modifier = Modifier.height(24.dp))

    DuskBulletLine(stringResource(R.string.onboarding_feature_keys))
    DuskBulletLine(stringResource(R.string.onboarding_feature_biometric))
    DuskBulletLine(stringResource(R.string.onboarding_feature_no_phrase))

    Spacer(modifier = Modifier.height(48.dp))

    DuskPrimaryButton(
        text = stringResource(R.string.onboarding_create_wallet),
        onClick = onCreate,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    DuskSecondaryButton(
        text = stringResource(R.string.onboarding_restore_wallet),
        onClick = onRestore,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RestoreContent(
    state: OnboardingUiState.RestoreInput,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = stringResource(R.string.onboarding_restore_label),
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        letterSpacing = 3.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = stringResource(R.string.onboarding_restore_headline),
        color = MidnightColors.Light,
        fontSize = 18.sp,
        fontWeight = FontWeight.W300,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.onboarding_restore_detail),
        color = MidnightColors.LightMuted,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    Spacer(modifier = Modifier.height(24.dp))

    BasicTextField(
        value = state.input,
        onValueChange = onInputChange,
        textStyle = TextStyle(
            color = MidnightColors.Light,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.W300,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MidnightColors.Light),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        minLines = 3,
        maxLines = 5,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MidnightColors.LightBarely)
            .padding(16.dp),
        decorationBox = { innerTextField ->
            if (state.input.isEmpty()) {
                Text(
                    text = stringResource(R.string.onboarding_restore_placeholder),
                    color = MidnightColors.LightFaint,
                    fontSize = 14.sp,
                )
            }
            innerTextField()
        },
    )

    if (state.invalid) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_restore_invalid),
            color = MidnightColors.LightMuted,
            fontSize = 12.sp,
        )
    }

    Spacer(modifier = Modifier.height(32.dp))

    DuskButtonRow(
        secondaryText = stringResource(R.string.onboarding_restore_cancel),
        primaryText = stringResource(R.string.onboarding_restore_confirm),
        onSecondary = onCancel,
        onPrimary = onConfirm,
        primaryEnabled = state.input.isNotBlank(),
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
        text = stringResource(R.string.onboarding_setup_label),
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        letterSpacing = 3.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = stringResource(R.string.onboarding_setup_headline),
        color = MidnightColors.Light,
        fontSize = 18.sp,
        fontWeight = FontWeight.W300,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.onboarding_setup_detail),
        color = MidnightColors.LightMuted,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    Spacer(modifier = Modifier.height(48.dp))

    DuskButtonRow(
        secondaryText = stringResource(R.string.onboarding_setup_back),
        primaryText = stringResource(R.string.onboarding_setup_open_settings),
        onSecondary = onBack,
        onPrimary = onOpenSettings,
    )
}

@Composable
private fun ErrorContent(errorMessage: OnboardingErrorMessage, onRetry: () -> Unit) {
    Text(
        text = stringResource(R.string.onboarding_error_label),
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        letterSpacing = 3.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = stringResource(errorMessage.resId, *errorMessage.formatArgs.toTypedArray()),
        color = MidnightColors.Light,
        fontSize = 16.sp,
        fontWeight = FontWeight.W300,
        lineHeight = 22.sp,
    )

    Spacer(modifier = Modifier.height(48.dp))

    DuskPrimaryButton(
        text = stringResource(R.string.onboarding_error_retry),
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
    )
}
