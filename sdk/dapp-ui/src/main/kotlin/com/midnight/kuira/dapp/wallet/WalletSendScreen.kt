package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.network.MidnightNetwork
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Full-screen send surface (#240) — enter a recipient + amount and send unshielded
 * NIGHT. Reached from [WalletStatusPanel]'s sheet via the "send" action; mirrors
 * [WalletReceiveScreen]'s full-screen treatment (Lace/Phantom-style flow rather than
 * a cramped sheet section).
 *
 * The screen is stateless w.r.t. the chain: it parses the user's decimal NIGHT into
 * base units and hands (recipient, amount) to [onSubmit]; the host VM runs
 * `MidnightSdk.sendNight` (validate → fewest-coin select → auto-consolidate → sign →
 * dust-pay → submit) and pushes progress/outcome back through [sendState].
 *
 * @param senderAddress this wallet's unshielded address (shown as the "from").
 * @param spendableNightRaw unshielded NIGHT available to spend, in base units
 *   (1 NIGHT = 1,000,000) — drives the "available" hint and the MAX shortcut.
 *   Shielded NIGHT isn't spendable by this unshielded transfer, so it's excluded.
 * @param sendState live send-flow state (idle form / sending / success / failure).
 * @param onSubmit (recipient, amount-in-base-units) — fired when the user taps Send.
 * @param onResetState clear the flow back to Idle (on close, or to re-edit after a failure).
 * @param onBack user-initiated dismissal.
 */
@Composable
internal fun WalletSendScreen(
    senderAddress: String,
    spendableNightRaw: BigInteger,
    network: MidnightNetwork,
    sendState: SendUiState,
    colors: WalletPanelColors = WalletPanelColors.Default,
    onSubmit: (toAddress: String, amount: BigInteger) -> Unit,
    onResetState: () -> Unit,
    onBack: () -> Unit,
) {
    var recipient by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }

    val amountRaw = remember(amountText) { parseNightToBaseUnits(amountText) }
    val overBalance = amountRaw != null && amountRaw > spendableNightRaw
    val sending = sendState is SendUiState.Sending
    val canSend = recipient.isNotBlank() && amountRaw != null && !overBalance && !sending

    // Editing after a failure clears the stale error so the form reads clean.
    val clearFailureOnEdit: () -> Unit = { if (sendState is SendUiState.Failure) onResetState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.sheetBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        ) {
            SendTopBar(colors) { onResetState(); onBack() }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SendDimens.HorizontalPadding)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            + SendDimens.BottomSpacing,
                    ),
            ) {
                Spacer(modifier = Modifier.height(SendDimens.HeaderTopGap))

                if (sendState is SendUiState.Success) {
                    SendSuccessCard(txHash = sendState.txHash, colors = colors) { onResetState(); onBack() }
                    return@Column
                }

                Text(
                    text = "Send NIGHT on ${network.displayName}",
                    color = colors.onSheet,
                    fontSize = SendType.Header,
                    fontWeight = FontWeight.W300,
                )
                Spacer(modifier = Modifier.height(SendDimens.SectionGap))

                // Recipient.
                SendFieldCard(
                    label = "to",
                    value = recipient,
                    onValueChange = { recipient = it.trim(); clearFailureOnEdit() },
                    placeholder = "mn_addr_${network.rustNetworkId}1…",
                    monospace = true,
                    keyboardType = KeyboardType.Ascii,
                    enabled = !sending,
                    colors = colors,
                )

                Spacer(modifier = Modifier.height(SendDimens.FieldGap))

                // Amount + available hint + MAX shortcut.
                SendFieldCard(
                    label = "amount (NIGHT)",
                    value = amountText,
                    onValueChange = { amountText = sanitizeAmount(it); clearFailureOnEdit() },
                    placeholder = "0.0",
                    monospace = true,
                    keyboardType = KeyboardType.Decimal,
                    enabled = !sending,
                    colors = colors,
                    trailing = {
                        Text(
                            text = "MAX",
                            color = if (sending) colors.onSheetDim else colors.accent,
                            fontSize = SendType.Max,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(SendDimens.MaxCornerRadius))
                                .then(
                                    if (sending) Modifier
                                    else Modifier.clickable {
                                        amountText = baseUnitsToNight(spendableNightRaw)
                                        clearFailureOnEdit()
                                    },
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    },
                )
                Spacer(modifier = Modifier.height(SendDimens.HintGap))
                Text(
                    text = if (overBalance) "Amount exceeds your ${baseUnitsToNight(spendableNightRaw)} NIGHT"
                    else "Available: ${baseUnitsToNight(spendableNightRaw)} NIGHT",
                    color = if (overBalance) ErrorRed else colors.onSheetSubtle,
                    fontSize = SendType.Hint,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (sendState is SendUiState.Failure) {
                    Spacer(modifier = Modifier.height(SendDimens.SectionGap))
                    Text(
                        text = sendState.message,
                        color = ErrorRed,
                        fontSize = SendType.Error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(SendDimens.SectionGap))

                SendButton(
                    sendingStage = (sendState as? SendUiState.Sending)?.stage,
                    enabled = canSend,
                    colors = colors,
                    onClick = { amountRaw?.let { onSubmit(recipient.trim(), it) } },
                )
            }
        }
    }
}

// ── Internal components ──

@Composable
private fun SendTopBar(colors: WalletPanelColors, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(SendDimens.TopBarHeight)
            .padding(horizontal = SendDimens.HorizontalPadding),
    ) {
        Text(
            text = "←",
            color = colors.onSheet,
            fontSize = SendType.BackGlyph,
            modifier = Modifier
                .clip(RoundedCornerShape(SendDimens.BackHitRadius))
                .clickable(onClick = onBack)
                .padding(SendDimens.BackHitPadding),
        )
        Spacer(modifier = Modifier.size(SendDimens.TopBarTitleGap))
        Text(
            text = "Send",
            color = colors.onSheet,
            fontSize = SendType.TopBarTitle,
            fontWeight = FontWeight.W400,
        )
    }
}

@Composable
private fun SendFieldCard(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    monospace: Boolean,
    keyboardType: KeyboardType,
    enabled: Boolean,
    colors: WalletPanelColors,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = colors.onSheetSubtle,
            fontSize = SendType.FieldLabel,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(SendDimens.FieldLabelGap))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SendDimens.FieldCornerRadius))
                .background(colors.button)
                .border(
                    width = SendDimens.FieldBorderWidth,
                    color = colors.pillBorder,
                    shape = RoundedCornerShape(SendDimens.FieldCornerRadius),
                )
                .padding(SendDimens.FieldPadding),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = colors.onSheetSubtle,
                        fontSize = SendType.Field,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = !monospace || keyboardType == KeyboardType.Decimal,
                    textStyle = TextStyle(
                        color = colors.onSheet,
                        fontSize = SendType.Field,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.size(8.dp))
                trailing()
            }
        }
    }
}

@Composable
private fun SendButton(
    sendingStage: String?,
    enabled: Boolean,
    colors: WalletPanelColors,
    onClick: () -> Unit,
) {
    val isSending = sendingStage != null
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SendDimens.ButtonCornerRadius))
            .background(if (enabled || isSending) colors.accent else colors.button)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = SendDimens.ButtonVerticalPadding),
    ) {
        if (isSending) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(
                    color = colors.sheetBackground,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(SendDimens.SpinnerSize),
                )
                Text(
                    text = sendingStage!!,
                    color = colors.sheetBackground,
                    fontSize = SendType.Button,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Text(
                text = "Send",
                color = if (enabled) colors.sheetBackground else colors.onSheetDim,
                fontSize = SendType.Button,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun SendSuccessCard(txHash: String, colors: WalletPanelColors, onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Spacer(modifier = Modifier.height(SendDimens.SuccessTopGap))
        Text(text = "✓", color = colors.accent, fontSize = SendType.SuccessGlyph)
        Spacer(modifier = Modifier.height(SendDimens.SectionGap))
        Text(text = "Sent", color = colors.onSheet, fontSize = SendType.Header, fontWeight = FontWeight.W300)
        if (txHash.isNotBlank()) {
            Spacer(modifier = Modifier.height(SendDimens.HintGap))
            Text(
                text = txHash,
                color = colors.onSheetSubtle,
                fontSize = SendType.TxHash,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(SendDimens.SuccessButtonGap))
        SendButton(sendingStage = null, enabled = true, colors = colors, onClick = onDone)
    }
}

// ── Amount parsing (NIGHT decimal ↔ base units; 1 NIGHT = 1,000,000) ──

/** Keep only digits and a single decimal point as the user types. */
private fun sanitizeAmount(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    return if (dot < 0) filtered else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
}

/** Parse decimal NIGHT → base units, or null if not a positive number. Sub-unit digits truncate. */
private fun parseNightToBaseUnits(input: String): BigInteger? = runCatching {
    val value = BigDecimal(input.trim())
    if (value <= BigDecimal.ZERO) null else value.movePointRight(NIGHT_DECIMAL_PLACES).toBigInteger()
}.getOrNull()

/** Format base units → a plain NIGHT string (e.g. 1500000 → "1.5"). */
private fun baseUnitsToNight(base: BigInteger): String =
    base.toBigDecimal().movePointLeft(NIGHT_DECIMAL_PLACES).stripTrailingZeros().toPlainString()

private const val NIGHT_DECIMAL_PLACES = 6

private val ErrorRed = Color(0xFFE57373)

// ── Local design tokens (own full-screen scale, like WalletReceiveScreen) ──

private object SendDimens {
    val HorizontalPadding = 20.dp
    val BottomSpacing = 24.dp
    val SectionGap = 20.dp
    val FieldGap = 16.dp
    val HintGap = 8.dp

    val TopBarHeight = 56.dp
    val TopBarTitleGap = 16.dp
    val BackHitRadius = 20.dp
    val BackHitPadding = 8.dp

    val HeaderTopGap = 16.dp

    val FieldLabelGap = 8.dp
    val FieldCornerRadius = 14.dp
    val FieldBorderWidth = 1.dp
    val FieldPadding = 14.dp
    val MaxCornerRadius = 8.dp

    val ButtonCornerRadius = 14.dp
    val ButtonVerticalPadding = 16.dp
    val SpinnerSize: Dp = 16.dp

    val SuccessTopGap = 48.dp
    val SuccessButtonGap = 40.dp
}

private object SendType {
    val TopBarTitle = 16.sp
    val BackGlyph = 24.sp
    val Header = 20.sp
    val FieldLabel = 11.sp
    val Field = 15.sp
    val Hint = 12.sp
    val Max = 11.sp
    val Error = 13.sp
    val Button = 15.sp
    val SuccessGlyph = 56.sp
    val TxHash = 12.sp
}
