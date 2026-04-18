package com.midnight.kuira.dev.wireframes.send

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.dev.wireframes.settings.SettingsDivider
import com.midnight.kuira.dev.wireframes.settings.SettingsRow
import com.midnight.kuira.dev.wireframes.settings.SettingsSectionHeader
import com.midnight.kuira.feature.balance.redesign.DuskPalette

/** Which screen of the 3-screen wizard to render in the dev portal. */
enum class SendScreen { TOKEN_MODE, RECIPIENT, AMOUNT }

/** State variants for the dev-controls. */
enum class SendWireframeState {
    DEFAULT,
    INVALID,               // Screen 2b: address error
    PREFILLED_FROM_URI,     // Screen 2b: URI paste
    INSUFFICIENT,           // Screen 2c: amount > balance
    PREFILLED_AMOUNT,       // Screen 2c: URI amount
    OFFLINE,                // Screen 2c: cached balance
}

@Composable
fun SendWireframe(
    screen: SendScreen = SendScreen.TOKEN_MODE,
    state: SendWireframeState = SendWireframeState.DEFAULT,
    palette: DuskPalette = DuskPalette.DarkMode,
    onBack: () -> Unit = {},
) {
    when (screen) {
        SendScreen.TOKEN_MODE -> TokenModeScreen(palette = palette, onBack = onBack)
        SendScreen.RECIPIENT -> RecipientScreen(state = state, palette = palette, onBack = onBack)
        SendScreen.AMOUNT -> AmountScreen(state = state, palette = palette, onBack = onBack)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Screen 2a — Token + Mode
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun TokenModeScreen(palette: DuskPalette, onBack: () -> Unit) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.Void),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.Light,
            alpha = if (palette === DuskPalette.LightMode) 0.55f else 1f,
            starCount = 60,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            TopBar(title = "Send", palette = palette, onBack = onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                SettingsSectionHeader(label = "SELECT TOKEN", palette = palette)
                Spacer(modifier = Modifier.size(12.dp))

                // NIGHT token card
                GlassPanel(
                    tint = palette.contentPanel,
                    border = palette.LightFaint,
                    contentPadding = 0.dp,
                ) {
                    // Header row (reuses SettingsRow)
                    SettingsRow(
                        label = "NIGHT",
                        rightValue = "1,234.567890",
                        readOnly = true,
                        palette = palette,
                    )
                    SettingsDivider(palette = palette)
                    TokenModeSubRow(
                        label = "Unshielded",
                        hint = "Visible on chain",
                        palette = palette,
                        onClick = { },
                    )
                    SettingsDivider(palette = palette)
                    TokenModeSubRow(
                        label = "Shielded",
                        hint = "Private \u00B7 ZK proof",
                        palette = palette,
                        onClick = { },
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Screen 2b — Recipient
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun RecipientScreen(
    state: SendWireframeState,
    palette: DuskPalette,
    onBack: () -> Unit,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    val initialRecipient = when (state) {
        SendWireframeState.PREFILLED_FROM_URI ->
            "mn_addr_preprod1qq2x7y8f5a2k3j9nvxz4t8pq3mdlwnrhc7vwu8f"
        SendWireframeState.INVALID -> "not-a-real-address"
        else -> ""
    }
    var recipient by remember(state) { mutableStateOf(initialRecipient) }

    val recipientError: String? = when {
        state != SendWireframeState.INVALID -> null
        recipient.isBlank() -> null
        !recipient.startsWith("mn_") -> "This doesn't look like a Midnight address"
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.Void),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.Light,
            alpha = if (palette === DuskPalette.LightMode) 0.55f else 1f,
            starCount = 60,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            TopBar(title = "Send NIGHT", palette = palette, onBack = onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // Mode badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9999.dp))
                        .background(palette.LightBarely)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "UNSHIELDED",
                        color = palette.LightSoft,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W400,
                        letterSpacing = 3.sp,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                SettingsSectionHeader(label = "TO", palette = palette)
                Spacer(modifier = Modifier.size(12.dp))

                GlassPanel(
                    tint = palette.contentPanel,
                    border = palette.LightFaint,
                    contentPadding = 16.dp,
                ) {
                    DuskInputField(
                        value = recipient,
                        onValueChange = { recipient = it },
                        placeholder = "mn_addr_preprod1\u2026",
                        error = recipientError,
                        monospace = true,
                        palette = palette,
                        trailingSlot = {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentPaste,
                                    contentDescription = "Paste recipient from clipboard",
                                    tint = palette.LightMuted,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Recently used placeholder (v1.1+)
                Text(
                    text = "No recent recipients",
                    color = palette.LightMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W400,
                    lineHeight = 16.sp,
                )

                Spacer(modifier = Modifier.weight(1f))

                DuskPrimaryButtonPaletted(
                    text = "Next",
                    onClick = { },
                    palette = palette,
                    enabled = recipient.isNotBlank() && recipientError == null,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Screen 2c — Amount (hero screen)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun AmountScreen(
    state: SendWireframeState,
    palette: DuskPalette,
    onBack: () -> Unit,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val availableBalance = "1234.567890"

    val initialAmount = when (state) {
        SendWireframeState.INSUFFICIENT -> "999999"
        SendWireframeState.PREFILLED_AMOUNT -> "5.5"
        else -> ""
    }
    var amount by remember(state) { mutableStateOf(initialAmount) }
    var inputMode by remember { mutableStateOf(AmountInputMode.NIGHT) }

    // Mock exchange rate for wireframe display ($1 = 1 NIGHT).
    // Real rate comes from a price oracle in production.
    val mockRate = 1.0
    val amountDouble = amount.toDoubleOrNull()

    val nightAmount = when (inputMode) {
        AmountInputMode.NIGHT -> amountDouble
        AmountInputMode.USD -> amountDouble?.let { it / mockRate }
    }
    val isInsufficient = nightAmount != null &&
        nightAmount > (availableBalance.toDoubleOrNull() ?: 0.0)
    val amountError: String? = if (isInsufficient || state == SendWireframeState.INSUFFICIENT) {
        "Insufficient balance"
    } else null

    val conversionHint: String? = if (amountDouble != null && amountDouble > 0 && amountError == null) {
        when (inputMode) {
            AmountInputMode.NIGHT -> "~$%,.2f".format(amountDouble * mockRate)
            AmountInputMode.USD -> "~%,.6f NIGHT".format(amountDouble / mockRate)
        }
    } else null

    val reviewEnabled = nightAmount != null && nightAmount > 0 && amountError == null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.Void),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.Light,
            alpha = if (palette === DuskPalette.LightMode) 0.55f else 1f,
            starCount = 60,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            // Top bar with "Review" right-slot text button
            AmountTopBar(
                palette = palette,
                reviewEnabled = reviewEnabled,
                onBack = onBack,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                // Recipient chip
                Spacer(modifier = Modifier.height(4.dp))
                RecipientChip(
                    addressShort = "mn_addr\u2026f5a2",
                    palette = palette,
                    onEdit = { },
                )

                // Center the hero inside a GlassPanel — star-protection
                // on critical numeric content, same as Balance hero.
                Spacer(modifier = Modifier.weight(1f))

                GlassPanel(
                    tint = palette.contentPanel,
                    border = palette.LightFaint,
                    contentPadding = 24.dp,
                ) {
                    AmountHeroInput(
                        value = amount,
                        onValueChange = { amount = it },
                        inputMode = inputMode,
                        onToggleMode = {
                            inputMode = if (inputMode == AmountInputMode.NIGHT)
                                AmountInputMode.USD else AmountInputMode.NIGHT
                        },
                        conversionHint = conversionHint,
                        error = amountError,
                        palette = palette,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Bottom bar: Available + MAX
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            bottom = navBarPadding.calculateBottomPadding() + 16.dp,
                        ),
                ) {
                    Column {
                        Text(
                            text = "Available",
                            color = palette.LightMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.W400,
                            lineHeight = 16.sp,
                        )
                        Text(
                            text = "%s NIGHT".format(
                                availableBalance.toDoubleOrNull()
                                    ?.let { "%,.6f".format(it) } ?: availableBalance
                            ),
                            color = palette.Light,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.W300,
                            lineHeight = 20.sp,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(palette.LightBarely)
                            .clickable { amount = availableBalance }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "MAX",
                            color = palette.LightSoft,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.W400,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountTopBar(
    palette: DuskPalette,
    reviewEnabled: Boolean,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to recipient",
            tint = palette.Light,
            modifier = Modifier
                .size(24.dp)
                .clickable { onBack() },
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "Enter Amount",
            color = palette.Light,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Review",
            color = if (reviewEnabled) palette.LightSoft else palette.LightFaint,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
            modifier = Modifier.clickable(enabled = reviewEnabled) { },
        )
    }
    HorizontalDivider(color = palette.LightFaint, thickness = 1.dp)
}

// ── Shared top bar for Screens 2a and 2b ──

@Composable
private fun TopBar(title: String, palette: DuskPalette, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = palette.Light,
            modifier = Modifier
                .size(24.dp)
                .clickable { onBack() },
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            color = palette.Light,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
        )
    }
    HorizontalDivider(color = palette.LightFaint, thickness = 1.dp)
}
