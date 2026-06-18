package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.midnight.kuira.core.designsystem.effect.StarField

/**
 * Restore flow for the sovereign recovery phrase (#252) — the bundled onboarding path for a user
 * who has lost their device/account but kept their 24 words. Stateless w.r.t. the SDK: it collects
 * the words, validates the BIP-39 checksum live, and hands them to [onRestore]; the host VM runs
 * [com.midnight.kuira.sdk.walletseed.WalletRecovery.restoreFromPhrase] and reports back via [state].
 *
 * A dApp can build its own restore UI against the same contract — this is one consumer, not the
 * only path. Runs under FLAG_SECURE while the words are on screen.
 */
@Composable
internal fun WalletRestoreScreen(
    state: RestoreUiState,
    colors: WalletPanelColors = WalletPanelColors.Default,
    isValidPhrase: (List<String>) -> Boolean,
    onRestore: (List<String>) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = remember(colors) { SendPalette.from(colors) }
    SecureScreen()

    var input by remember { mutableStateOf("") }
    val words = remember(input) { input.trim().split(Regex("\\s+")).filter { it.isNotBlank() } }
    val complete = words.size == EXPECTED_WORDS
    val valid = complete && isValidPhrase(words)

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.text,
            alpha = if (palette.isLight) STAR_ALPHA_LIGHT else STAR_ALPHA_DARK,
            starCount = STAR_COUNT,
        )
        Column(Modifier.fillMaxSize().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())) {
            RecoveryTopBar(title = "Restore from recovery phrase", palette = palette, onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SendDimens.PanelPadding)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + SendDimens.Space24),
            ) {
                Spacer(Modifier.height(SendDimens.Space24))

                if (state is RestoreUiState.Success) {
                    Text(
                        "Wallet restored",
                        color = palette.success,
                        fontSize = SendType.SuccessHeadline,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(SendDimens.Space8))
                    Text(
                        "Your funds and address are back. A fresh sigil identity is set up on this device; " +
                            "your funds are controlled by the restored seed.",
                        color = palette.textSoft,
                        fontSize = SendType.Body,
                    )
                    Spacer(Modifier.height(SendDimens.Space24))
                    PrimaryButton("Continue", palette, enabled = true, onClick = onDone)
                    return@Column
                }

                Text(
                    "Enter your 24 words in order, separated by spaces.",
                    color = palette.text,
                    fontSize = SendType.Body,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(SendDimens.Space16))

                val restoring = state is RestoreUiState.Restoring
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = !restoring,
                    modifier = Modifier.fillMaxWidth().height(RECOVERY_INPUT_HEIGHT),
                    textStyle = TextStyle(color = palette.text, fontSize = SendType.Body),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.confirm,
                        unfocusedBorderColor = palette.hairline,
                        cursorColor = palette.confirm,
                    ),
                )
                Spacer(Modifier.height(SendDimens.Space8))

                val counter = when {
                    valid -> "$EXPECTED_WORDS / $EXPECTED_WORDS words ✓"
                    complete -> "$EXPECTED_WORDS / $EXPECTED_WORDS words — checksum invalid"
                    else -> "${words.size} / $EXPECTED_WORDS words"
                }
                Text(
                    counter,
                    color = if (valid) palette.success else palette.textMuted,
                    fontSize = SendType.Caption,
                )

                if (state is RestoreUiState.Error) {
                    Spacer(Modifier.height(SendDimens.Space8))
                    Text(state.reason, color = palette.error, fontSize = SendType.Caption)
                }

                Spacer(Modifier.height(SendDimens.Space24))
                PrimaryButton(
                    label = if (restoring) "Restoring…" else "Restore",
                    palette = palette,
                    enabled = valid && !restoring,
                    onClick = { onRestore(words) },
                )
            }
        }
    }
}

private const val EXPECTED_WORDS = 24
private val RECOVERY_INPUT_HEIGHT = 140.dp
