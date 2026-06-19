package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.midnight.kuira.core.designsystem.effect.RunnerWithDust
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.core.network.MidnightNetwork
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Full-screen Send wizard (#240) — the dapp-ui port of the design-sprint Send
 * wireframe ([com.midnight.kuira.dev.wireframes.send]), which lives in the app
 * module and so can't be reached from the published SDK. Reskins the pill's
 * Send onto the wireframe's visual language (StarField + GlassPanel + hero
 * amount), themed via [SendPalette] so the panel's light/dark toggle carries.
 *
 * Three steps + review, then the post-submit state machine driven by
 * [sendState]:
 *   Token+Mode → Recipient → Amount → Review → (Confirm) → Pending → Success/Failure
 *
 * The SDK pill only sends unshielded NIGHT today, so the Token+Mode screen
 * offers NIGHT/Unshielded and shows Shielded as "soon". The screen stays
 * stateless w.r.t. the chain: it hands (recipient, base-unit amount) to
 * [onSubmit]; the host VM runs `MidnightSdk.sendNight` and pushes progress and
 * outcome back through [sendState].
 *
 * @param spendableNightRaw unshielded NIGHT available, in base units
 *   (1 NIGHT = 1,000,000) — drives the Available hint + MAX shortcut.
 * @param sendState live send-flow state (idle / sending / success / failure).
 * @param onSubmit (recipient, amount-in-base-units) — fired on Confirm.
 * @param onResetState clear the flow back to Idle.
 * @param onBack dismiss the whole wizard.
 */
@Composable
internal fun WalletSendScreen(
    spendableNightRaw: BigInteger,
    network: MidnightNetwork,
    sendState: SendUiState,
    colors: WalletPanelColors = WalletPanelColors.Default,
    onSubmit: (toAddress: String, amount: BigInteger) -> Unit,
    onResetState: () -> Unit,
    onBack: () -> Unit,
    registerBack: (() -> Boolean) -> Unit = {},
) {
    val palette = remember(colors) { SendPalette.from(colors) }
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    var step by rememberSaveable { mutableStateOf(SendStep.TOKEN_MODE) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    // Tracks whether a text field holds focus (keyboard up) — the back press
    // then hides the keyboard instead of leaving the screen.
    var fieldFocused by remember { mutableStateOf(false) }

    val amountRaw = remember(amountText) { parseNightToBaseUnits(amountText) }
    val overBalance = amountRaw != null && amountRaw > spendableNightRaw
    val recipientError = recipientErrorOrNull(recipient)
    val recipientReady = recipient.isNotBlank() && recipientError == null
    val amountReady = amountRaw != null && !overBalance

    // Editing anything after a failure clears the stale error.
    val clearFailure: () -> Unit = { if (sendState is SendUiState.Failure) onResetState() }

    // The Send screen lives in a Popup whose back press is consumed at the view
    // level (it fires the host's onDismissRequest) before any child BackHandler
    // runs — so we publish the back logic for the host to invoke. It walks the
    // wizard stack and hides the keyboard instead of dismissing the whole Popup.
    // Returns true when it consumed the back; false tells the host to close.
    val handleBack: () -> Boolean = {
        when {
            sendState is SendUiState.Sending -> true                 // don't abandon an in-flight submit
            scanning -> { scanning = false; true }
            fieldFocused -> { focusManager.clearFocus(); true }      // hide keyboard, stay put
            sendState is SendUiState.Success -> { onResetState(); false }
            step == SendStep.REVIEW -> { step = SendStep.AMOUNT; true }
            step == SendStep.AMOUNT -> { step = SendStep.RECIPIENT; true }
            step == SendStep.RECIPIENT -> { step = SendStep.TOKEN_MODE; true }
            else -> { onResetState(); false }                        // TOKEN_MODE → host closes
        }
    }
    SideEffect { registerBack(handleBack) }

    // The QR scanner takes over the whole surface while open.
    if (scanning) {
        QrScannerScreen(
            palette = palette,
            onResult = { scanned ->
                recipient = extractAddress(scanned)
                scanning = false
                clearFailure()
            },
            onCancel = { scanning = false },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(palette.bg)) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.text,
            alpha = if (palette.isLight) STAR_ALPHA_LIGHT else STAR_ALPHA_DARK,
            starCount = STAR_COUNT,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        ) {
            // Post-submit states override the wizard chrome.
            when (sendState) {
                is SendUiState.Sending -> {
                    WizardTopBar(title = "Sending", palette = palette, backEnabled = false, onBack = {})
                    PendingScreen(stage = sendState.stage, palette = palette)
                }

                is SendUiState.Success -> {
                    SuccessScreen(
                        txHash = sendState.txHash,
                        palette = palette,
                        onCopyHash = { clipboard.setText(AnnotatedString(sendState.txHash)) },
                        onSendAnother = {
                            recipient = ""
                            amountText = ""
                            step = SendStep.TOKEN_MODE
                            onResetState()
                        },
                        onDone = { onResetState(); onBack() },
                    )
                }

                else -> when (step) {
                    SendStep.TOKEN_MODE -> TokenModeStep(
                        palette = palette,
                        availableNight = baseUnitsToNight(spendableNightRaw),
                        onBack = { onResetState(); onBack() },
                        onPickUnshielded = { step = SendStep.RECIPIENT },
                    )

                    SendStep.RECIPIENT -> RecipientStep(
                        palette = palette,
                        network = network,
                        recipient = recipient,
                        error = recipientError,
                        canAdvance = recipientReady,
                        onBack = { step = SendStep.TOKEN_MODE },
                        onChange = { recipient = it; clearFailure() },
                        onPaste = { clipboard.getText()?.text?.let { recipient = extractAddress(it); clearFailure() } },
                        onScan = { scanning = true },
                        onNext = { step = SendStep.AMOUNT },
                        onFocusChanged = { fieldFocused = it },
                    )

                    SendStep.AMOUNT -> AmountStep(
                        palette = palette,
                        recipient = recipient,
                        amountText = amountText,
                        availableNight = baseUnitsToNight(spendableNightRaw),
                        overBalance = overBalance,
                        canReview = amountReady,
                        onBack = { step = SendStep.RECIPIENT },
                        onEditRecipient = { step = SendStep.RECIPIENT },
                        onChange = { amountText = sanitizeNightAmount(it); clearFailure() },
                        onMax = { amountText = baseUnitsToNight(spendableNightRaw); clearFailure() },
                        onPreset = { pct ->
                            // pct% of the spendable balance, in base units → formatted NIGHT.
                            val part = spendableNightRaw.multiply(BigInteger.valueOf(pct.toLong())).divide(BigInteger.valueOf(100))
                            amountText = baseUnitsToNight(part)
                            clearFailure()
                        },
                        onReview = { step = SendStep.REVIEW },
                        onFocusChanged = { fieldFocused = it },
                    )

                    SendStep.REVIEW -> ReviewStep(
                        palette = palette,
                        recipient = recipient,
                        amountText = amountText,
                        failure = sendState as? SendUiState.Failure,
                        onBack = { step = SendStep.AMOUNT },
                        onConfirm = { amountRaw?.let { onSubmit(recipient.trim(), it) } },
                    )
                }
            }
        }
    }
}

// ── Screen 1 — Token + Mode ──

@Composable
private fun TokenModeStep(
    palette: SendPalette,
    availableNight: String,
    onBack: () -> Unit,
    onPickUnshielded: () -> Unit,
) {
    WizardTopBar(title = "Send", palette = palette, onBack = onBack)
    ScrollColumn {
        Spacer(modifier = Modifier.height(SendDimens.Space32))
        SendSectionHeader(label = "SELECT TOKEN", palette = palette)
        Spacer(modifier = Modifier.height(SendDimens.Space12))
        SendPanel(palette = palette, contentPadding = 0.dp) {
            SendDetailRow(label = "NIGHT", value = "$availableNight available", palette = palette)
            SendDivider(palette)
            TokenModeRow(
                label = "Unshielded",
                hint = "Visible on chain",
                palette = palette,
                onClick = onPickUnshielded,
                trailing = { Text("›", color = palette.textMuted, fontSize = SendType.HeroDenom) },
            )
            SendDivider(palette)
            TokenModeRow(
                label = "Shielded",
                hint = "Private · ZK proof",
                palette = palette,
                enabled = false,
                onClick = {},
                trailing = { SendBadge(text = "SOON", palette = palette) },
            )
        }
    }
}

// ── Screen 2 — Recipient ──

@Composable
private fun RecipientStep(
    palette: SendPalette,
    network: MidnightNetwork,
    recipient: String,
    error: String?,
    canAdvance: Boolean,
    onBack: () -> Unit,
    onChange: (String) -> Unit,
    onPaste: () -> Unit,
    onScan: () -> Unit,
    onNext: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    WizardTopBar(title = "Send NIGHT", palette = palette, onBack = onBack)
    ColumnWithFooter(
        footer = {
            SendPrimaryButton(text = "Next", onClick = onNext, palette = palette, enabled = canAdvance)
        },
    ) {
        Spacer(modifier = Modifier.height(SendDimens.Space32))
        SendBadge(text = "UNSHIELDED", palette = palette)
        Spacer(modifier = Modifier.height(SendDimens.Space24))
        SendSectionHeader(label = "TO", palette = palette)
        Spacer(modifier = Modifier.height(SendDimens.Space12))
        SendInputField(
            value = recipient,
            onValueChange = onChange,
            palette = palette,
            placeholder = "mn_addr_${network.rustNetworkId}1…",
            error = error,
            monospace = true,
            onFocusChanged = onFocusChanged,
            trailingSlot = {
                GlyphButton(palette = palette, onClick = onPaste) { PasteGlyph(color = palette.textMuted) }
                GlyphButton(palette = palette, onClick = onScan) { ScanGlyph(color = palette.textMuted) }
            },
        )
    }
}

// ── Screen 3 — Amount (hero) ──

@Composable
private fun AmountStep(
    palette: SendPalette,
    recipient: String,
    amountText: String,
    availableNight: String,
    overBalance: Boolean,
    canReview: Boolean,
    onBack: () -> Unit,
    onEditRecipient: () -> Unit,
    onChange: (String) -> Unit,
    onMax: () -> Unit,
    onPreset: (Int) -> Unit,
    onReview: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    WizardTopBar(
        title = "Enter amount",
        palette = palette,
        onBack = onBack,
        trailing = {
            Text(
                text = "Continue",
                color = if (canReview) palette.textSoft else palette.hairline,
                fontSize = SendType.Title,
                fontWeight = FontWeight.W300,
                modifier = Modifier.clickable(enabled = canReview, onClick = onReview),
            )
        },
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SendDimens.Space16)
            // Reserve the keyboard's height so the hero + presets always sit ABOVE the IME.
            .imePadding(),
    ) {
        Spacer(modifier = Modifier.height(SendDimens.Space4))
        RecipientChip(addressShort = shortAddress(recipient), palette = palette, onEdit = onEditRecipient)

        // Available + MAX live at the TOP so the soft keyboard never hides them.
        Spacer(modifier = Modifier.height(SendDimens.Space12))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Available", color = palette.textMuted, fontSize = SendType.Hint, fontWeight = FontWeight.W400)
                Text("$availableNight NIGHT", color = palette.text, fontSize = SendType.Body, fontWeight = FontWeight.W300)
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(SendDimens.RadiusFull))
                    .background(palette.text.copy(alpha = GLASS_FILL_ALPHA))
                    .clickable(onClick = onMax)
                    .padding(horizontal = SendDimens.Space16, vertical = SendDimens.Space8),
            ) {
                Text("MAX", color = palette.textSoft, fontSize = SendType.Max, letterSpacing = SendType.TrackMax)
            }
        }

        // Big frosted hero — fills the space between the available row and the presets, so it stays
        // prominent yet always fits above the keyboard (tall, near-square when the keyboard is down).
        Spacer(modifier = Modifier.height(SendDimens.Space16))
        SendPanel(
            palette = palette,
            contentPadding = SendDimens.PanelPaddingHero,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AmountHero(
                    value = amountText,
                    onValueChange = onChange,
                    error = if (overBalance) "Insufficient balance" else null,
                    palette = palette,
                    numberSize = SendType.HeroNumberLg,
                    onFocusChanged = onFocusChanged,
                )
            }
        }

        // Three quick-amount presets — fractions of the spendable balance.
        Spacer(modifier = Modifier.height(SendDimens.Space16))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SendDimens.Space8)) {
            PresetChip("25%", palette, Modifier.weight(1f)) { onPreset(25) }
            PresetChip("50%", palette, Modifier.weight(1f)) { onPreset(50) }
            PresetChip("75%", palette, Modifier.weight(1f)) { onPreset(75) }
        }

        Spacer(modifier = Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + SendDimens.Space16))
    }
}

/** A frosted-glass quick-amount preset chip (25% / 50% / 75%). */
@Composable
private fun PresetChip(label: String, palette: SendPalette, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(SendDimens.ButtonHeight)
            .clip(RoundedCornerShape(SendDimens.RadiusMd))
            .background(palette.text.copy(alpha = GLASS_FILL_ALPHA))
            .border(1.dp, palette.hairline, RoundedCornerShape(SendDimens.RadiusMd))
            .clickable(onClick = onClick),
    ) {
        Text(label, color = palette.textSoft, fontSize = SendType.Body, fontWeight = FontWeight.W300)
    }
}

// ── Screen 4 — Review / Confirm ──

@Composable
private fun ReviewStep(
    palette: SendPalette,
    recipient: String,
    amountText: String,
    failure: SendUiState.Failure?,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    WizardTopBar(title = "Review", palette = palette, onBack = onBack)
    ColumnWithFooter(
        footer = {
            SendButtonRow(
                secondaryText = "Cancel",
                primaryText = if (failure != null) "Try again" else "Confirm",
                onSecondary = onBack,
                onPrimary = onConfirm,
                palette = palette,
            )
        },
    ) {
        Spacer(modifier = Modifier.height(SendDimens.Space32))

        // Receipt hero — amount + mode badge, star-protected.
        SendPanel(palette = palette, contentPadding = SendDimens.PanelPaddingHero) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = amountText.ifEmpty { "0" },
                        color = palette.text,
                        fontSize = SendType.HeroNumberLg,
                        fontWeight = FontWeight.W200,
                        letterSpacing = SendType.HeroTracking,
                    )
                    Spacer(modifier = Modifier.width(SendDimens.Space8))
                    Text(
                        text = "NIGHT",
                        color = palette.textMuted,
                        fontSize = SendType.HeroDenom,
                        fontWeight = FontWeight.W300,
                        modifier = Modifier.padding(bottom = SendDimens.Space8),
                    )
                }
                Spacer(modifier = Modifier.height(SendDimens.Space8))
                SendBadge(text = "UNSHIELDED", palette = palette)
            }
        }

        Spacer(modifier = Modifier.height(SendDimens.Space32))

        SendPanel(palette = palette, contentPadding = 0.dp) {
            SendDetailRow(label = "To", value = shortAddress(recipient), palette = palette, valueMono = true)
            SendDivider(palette)
            SendDetailRow(label = "Amount", value = "${amountText.ifEmpty { "0" }} NIGHT", palette = palette)
        }
        Spacer(modifier = Modifier.height(SendDimens.Space12))
        // Honest fee note: Midnight NIGHT transfers pay fees from Dust, not
        // NIGHT — so there's no NIGHT fee line to show, and no oracle to fake one.
        Text(
            text = "Network fees are paid automatically from your Dust.",
            color = palette.textMuted,
            fontSize = SendType.Hint,
            fontWeight = FontWeight.W400,
        )

        if (failure != null) {
            Spacer(modifier = Modifier.height(SendDimens.Space24))
            SendPanel(palette = palette) {
                Text("Send failed", color = palette.error, fontSize = SendType.Caption, fontWeight = FontWeight.W400)
                Spacer(modifier = Modifier.height(SendDimens.Space4))
                Text(failure.message, color = palette.textSoft, fontSize = SendType.Hint, fontWeight = FontWeight.W400)
            }
        }
    }
}

// ── Post-submit — Pending ──

@Composable
private fun PendingScreen(stage: String, palette: SendPalette) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center, // center the runner + text in the screen
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SendDimens.Space16),
    ) {
        RunnerWithDust(
            modifier = Modifier
                .fillMaxWidth(RUNNER_WIDTH_FRACTION)
                .height(RunnerHeight),
            color = palette.text,
        )
        Spacer(modifier = Modifier.height(SendDimens.Space32))
        Text(stage, color = palette.text, fontSize = SendType.Body, fontWeight = FontWeight.W300)
        Spacer(modifier = Modifier.height(SendDimens.Space8))
        Text(
            text = "This usually takes a few seconds.",
            color = palette.textMuted,
            fontSize = SendType.Hint,
            fontWeight = FontWeight.W400,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Post-submit — Success ──

@Composable
private fun SuccessScreen(
    txHash: String,
    palette: SendPalette,
    onCopyHash: () -> Unit,
    onSendAnother: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(SendDimens.TopBarHeight)
            .padding(horizontal = SendDimens.Space16),
    ) {
        Text(
            text = "Done",
            color = palette.text,
            fontSize = SendType.Title,
            fontWeight = FontWeight.W300,
            modifier = Modifier.clickable(onClick = onDone),
        )
        Spacer(modifier = Modifier.width(SendDimens.Space16))
        Text("Sent", color = palette.text, fontSize = SendType.Title, fontWeight = FontWeight.W400)
    }
    HorizontalDivider(color = palette.hairline, thickness = SendDimens.DividerThickness)

    ColumnWithFooter(
        footer = { SendPrimaryButton(text = "Send another", onClick = onSendAnother, palette = palette) },
    ) {
        Spacer(modifier = Modifier.height(SendDimens.Space48))
        SendPanel(palette = palette, contentPadding = SendDimens.PanelPaddingHero) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CheckGlyph(color = palette.success, size = SendDimens.Icon32)
                Spacer(modifier = Modifier.height(SendDimens.Space20))
                Text(
                    "Transaction submitted",
                    color = palette.text,
                    fontSize = SendType.SuccessHeadline,
                    fontWeight = FontWeight.W300,
                )
                Spacer(modifier = Modifier.height(SendDimens.Space8))
                Text(
                    "It may take a few minutes to confirm.",
                    color = palette.textMuted,
                    fontSize = SendType.Caption,
                    fontWeight = FontWeight.W400,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (txHash.isNotBlank()) {
            Spacer(modifier = Modifier.height(SendDimens.Space32))
            SendSectionHeader(label = "TRANSACTION", palette = palette)
            Spacer(modifier = Modifier.height(SendDimens.Space12))
            SendPanel(palette = palette, contentPadding = 0.dp) {
                SendDetailRow(
                    label = "Hash",
                    value = shortAddress(txHash),
                    palette = palette,
                    valueMono = true,
                    trailing = { CopyGlyph(color = palette.textMuted) },
                    onClick = onCopyHash,
                )
            }
        }
    }
}

// ── Shared chrome ──

@Composable
private fun WizardTopBar(
    title: String,
    palette: SendPalette,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(SendDimens.TopBarHeight)
            .padding(horizontal = SendDimens.Space16),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(SendDimens.RadiusLg))
                .clickable(enabled = backEnabled, onClick = onBack)
                .padding(SendDimens.GlyphHit),
        ) {
            BackGlyph(color = if (backEnabled) palette.text else palette.textMuted)
        }
        Spacer(modifier = Modifier.size(SendDimens.Space8))
        Text(title, color = palette.text, fontSize = SendType.Title, fontWeight = FontWeight.W300)
        if (trailing != null) {
            Spacer(modifier = Modifier.weight(1f))
            trailing()
        }
    }
    HorizontalDivider(color = palette.hairline, thickness = SendDimens.DividerThickness)
}

/** A scrolling content column with full-screen padding (Token+Mode). */
@Composable
private fun ScrollColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SendDimens.Space16)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + SendDimens.Space24),
    ) { content() }
}

/** Content column that pins a footer (primary button / button row) to the bottom. */
@Composable
private fun ColumnWithFooter(
    footer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SendDimens.Space16)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + SendDimens.Space24),
    ) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
        Spacer(modifier = Modifier.height(SendDimens.Space16))
        footer()
    }
}

@Composable
private fun GlyphButton(palette: SendPalette, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(SendDimens.RowMinHeightAccessibility)
            .clip(RoundedCornerShape(SendDimens.RadiusSm))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

// ── Wizard step model ──

private enum class SendStep { TOKEN_MODE, RECIPIENT, AMOUNT, REVIEW }

// ── Amount parsing (NIGHT decimal ↔ base units; 1 NIGHT = 1,000,000) ──

/** Keep digits + a single decimal point, and cap fractional digits at the NIGHT scale. */
private fun sanitizeNightAmount(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    if (dot < 0) return filtered
    val whole = filtered.substring(0, dot)
    val frac = filtered.substring(dot + 1).replace(".", "").take(NIGHT_DECIMALS)
    return "$whole.$frac"
}

/** Parse decimal NIGHT → base units, or null if not a positive number. Sub-unit digits truncate. */
private fun parseNightToBaseUnits(input: String): BigInteger? = runCatching {
    val value = BigDecimal(input.trim())
    if (value <= BigDecimal.ZERO) null else value.movePointRight(NIGHT_DECIMALS).toBigInteger()
}.getOrNull()

/** Format base units → a plain NIGHT string (e.g. 1500000 → "1.5"). */
private fun baseUnitsToNight(base: BigInteger): String =
    base.toBigDecimal().movePointLeft(NIGHT_DECIMALS).stripTrailingZeros().toPlainString()

// `NIGHT_DECIMALS` is the package-level constant shared with WalletPanelViewModel.

// ── Address helpers ──

/** Soft client-side recipient check (the VM does authoritative bech32m validation on submit). */
private fun recipientErrorOrNull(recipient: String): String? = when {
    recipient.isBlank() -> null
    recipient.startsWith(SHIELDED_PREFIX) -> "Shielded addresses can't receive an unshielded send"
    !recipient.startsWith(UNSHIELDED_PREFIX) -> "This doesn't look like a Midnight address"
    else -> null
}

/** Pull the address out of a bare string or a `scheme:mn_addr…` URI from a QR. */
private fun extractAddress(scanned: String): String {
    val trimmed = scanned.trim()
    val start = trimmed.indexOf("mn_")
    return (if (start >= 0) trimmed.substring(start) else trimmed).takeWhile { !it.isWhitespace() }
}

/** Head…tail elision for long bech32m strings (addresses, hashes). */
private fun shortAddress(value: String): String =
    if (value.length <= ADDRESS_HEAD + ADDRESS_TAIL + 1) value
    else "${value.take(ADDRESS_HEAD)}…${value.takeLast(ADDRESS_TAIL)}"

private const val UNSHIELDED_PREFIX = "mn_addr_"
private const val SHIELDED_PREFIX = "mn_shield-addr"
private const val ADDRESS_HEAD = 10
private const val ADDRESS_TAIL = 6

// ── Local dimensions specific to this screen ──

private val RunnerHeight: Dp = 120.dp
private const val RUNNER_WIDTH_FRACTION = 0.4f
