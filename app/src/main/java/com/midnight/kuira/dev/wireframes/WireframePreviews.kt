package com.midnight.kuira.dev.wireframes

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.midnight.kuira.dev.wireframes.dust.DustWireframe
import com.midnight.kuira.dev.wireframes.dust.DustWireframeState
import com.midnight.kuira.dev.wireframes.history.TxHistoryWireframe
import com.midnight.kuira.dev.wireframes.history.TxHistoryScreen
import com.midnight.kuira.dev.wireframes.history.TxHistoryState
import com.midnight.kuira.dev.wireframes.onboarding.OnboardingWireframe
import com.midnight.kuira.dev.wireframes.onboarding.OnboardingStep
import com.midnight.kuira.dev.wireframes.receive.ReceiveWireframe
import com.midnight.kuira.dev.wireframes.recovery.RecoveryPhraseWireframe
import com.midnight.kuira.dev.wireframes.send.ConfirmationState
import com.midnight.kuira.dev.wireframes.send.SendConfirmationWireframe
import com.midnight.kuira.dev.wireframes.send.SendScreen
import com.midnight.kuira.dev.wireframes.send.SendWireframe
import com.midnight.kuira.dev.wireframes.send.SendWireframeState
import com.midnight.kuira.dev.wireframes.settings.SettingsWireframe
import com.midnight.kuira.feature.balance.redesign.DuskPalette

// ═══════════════════════════════════════════════════════════════════
// Onboarding
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, name = "09 · Onboarding — Welcome")
@Composable
private fun OnboardingWelcomePreview() {
    OnboardingWireframe(step = OnboardingStep.WELCOME)
}

@Preview(showBackground = true, name = "09 · Onboarding — Passcode")
@Composable
private fun OnboardingPasscodePreview() {
    OnboardingWireframe(step = OnboardingStep.ENTER_PASSCODE)
}

@Preview(showBackground = true, name = "09 · Onboarding — Biometric")
@Composable
private fun OnboardingBiometricPreview() {
    OnboardingWireframe(step = OnboardingStep.BIOMETRIC_SETUP)
}

@Preview(showBackground = true, name = "09 · Onboarding — Creating")
@Composable
private fun OnboardingCreatingPreview() {
    OnboardingWireframe(step = OnboardingStep.CREATING)
}

@Preview(showBackground = true, name = "09 · Onboarding — Recovery Phrase")
@Composable
private fun OnboardingPhrasePreview() {
    OnboardingWireframe(step = OnboardingStep.RECOVERY_PHRASE)
}

@Preview(showBackground = true, name = "09 · Onboarding — All Set")
@Composable
private fun OnboardingAllSetPreview() {
    OnboardingWireframe(step = OnboardingStep.ALL_SET)
}

// ═══════════════════════════════════════════════════════════════════
// Send flow
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, name = "02 · Send — Token + Mode")
@Composable
private fun SendTokenModePreview() {
    SendWireframe(screen = SendScreen.TOKEN_MODE)
}

@Preview(showBackground = true, name = "02 · Send — Recipient")
@Composable
private fun SendRecipientPreview() {
    SendWireframe(screen = SendScreen.RECIPIENT)
}

@Preview(showBackground = true, name = "02 · Send — Amount")
@Composable
private fun SendAmountPreview() {
    SendWireframe(screen = SendScreen.AMOUNT)
}

@Preview(showBackground = true, name = "02 · Send — Amount (insufficient)")
@Composable
private fun SendAmountInsufficientPreview() {
    SendWireframe(screen = SendScreen.AMOUNT, state = SendWireframeState.INSUFFICIENT)
}

// ═══════════════════════════════════════════════════════════════════
// Send Confirmation
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, name = "03 · Confirmation — Review")
@Composable
private fun ConfirmationReviewPreview() {
    SendConfirmationWireframe(state = ConfirmationState.DEFAULT)
}

@Preview(showBackground = true, name = "03 · Confirmation — Pending")
@Composable
private fun ConfirmationPendingPreview() {
    SendConfirmationWireframe(state = ConfirmationState.PENDING_PROVING)
}

@Preview(showBackground = true, name = "03 · Confirmation — Success")
@Composable
private fun ConfirmationSuccessPreview() {
    SendConfirmationWireframe(state = ConfirmationState.SUCCESS)
}

@Preview(showBackground = true, name = "03 · Confirmation — Error")
@Composable
private fun ConfirmationErrorPreview() {
    SendConfirmationWireframe(state = ConfirmationState.ERROR)
}

// ═══════════════════════════════════════════════════════════════════
// Dust
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, name = "04 · Dust — Default")
@Composable
private fun DustDefaultPreview() {
    DustWireframe(state = DustWireframeState.DEFAULT)
}

@Preview(showBackground = true, name = "04 · Dust — Empty (register CTA)")
@Composable
private fun DustEmptyPreview() {
    DustWireframe(state = DustWireframeState.EMPTY)
}

@Preview(showBackground = true, name = "04 · Dust — Pending")
@Composable
private fun DustPendingPreview() {
    DustWireframe(state = DustWireframeState.PENDING_PROVE)
}

@Preview(showBackground = true, name = "04 · Dust — Success")
@Composable
private fun DustSuccessPreview() {
    DustWireframe(state = DustWireframeState.SUCCESS)
}

// ═══════════════════════════════════════════════════════════════════
// Settings
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, name = "05 · Settings — Default")
@Composable
private fun SettingsDefaultPreview() {
    SettingsWireframe()
}

@Preview(showBackground = true, name = "05 · Settings — Dev Mode")
@Composable
private fun SettingsDevModePreview() {
    SettingsWireframe(devModeUnlocked = true)
}

// ═══════════════════════════════════════════════════════════════════
// Tx History
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, name = "06 · History — Default")
@Composable
private fun HistoryDefaultPreview() {
    TxHistoryWireframe(screen = TxHistoryScreen.LIST, state = TxHistoryState.DEFAULT)
}

@Preview(showBackground = true, name = "06 · History — Empty")
@Composable
private fun HistoryEmptyPreview() {
    TxHistoryWireframe(screen = TxHistoryScreen.LIST, state = TxHistoryState.EMPTY)
}

@Preview(showBackground = true, name = "06 · History — Detail")
@Composable
private fun HistoryDetailPreview() {
    TxHistoryWireframe(screen = TxHistoryScreen.DETAIL)
}

// ═══════════════════════════════════════════════════════════════════
// Receive
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, name = "07 · Receive — Default")
@Composable
private fun ReceiveDefaultPreview() {
    ReceiveWireframe()
}

// ═══════════════════════════════════════════════════════════════════
// Recovery Phrase
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, name = "08 · Recovery Phrase — Settings")
@Composable
private fun RecoveryPhraseSettingsPreview() {
    RecoveryPhraseWireframe(isOnboardingEntry = false)
}

@Preview(showBackground = true, name = "08 · Recovery Phrase — Onboarding")
@Composable
private fun RecoveryPhraseOnboardingPreview() {
    RecoveryPhraseWireframe(isOnboardingEntry = true)
}

// ═══════════════════════════════════════════════════════════════════
// Light mode variants
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, name = "Light · Onboarding Welcome")
@Composable
private fun OnboardingWelcomeLightPreview() {
    OnboardingWireframe(step = OnboardingStep.WELCOME, palette = DuskPalette.LightMode)
}

@Preview(showBackground = true, name = "Light · Send Amount")
@Composable
private fun SendAmountLightPreview() {
    SendWireframe(screen = SendScreen.AMOUNT, palette = DuskPalette.LightMode)
}

@Preview(showBackground = true, name = "Light · Settings")
@Composable
private fun SettingsLightPreview() {
    SettingsWireframe(palette = DuskPalette.LightMode)
}
