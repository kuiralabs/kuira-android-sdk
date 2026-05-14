package com.midnight.example.common.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.midnight.kuira.core.ledger.ui.BalanceFormatter
import com.midnight.kuira.core.network.MidnightNetwork
import kotlinx.coroutines.launch

/**
 * Drop-in wallet panel for example apps.
 *
 * Renders a compact pill anchored top-right (e.g. `10K · 167.3 · ✓`). Tap to
 * expand a [ModalBottomSheet] with the wallet address, full balance, and
 * three actions: read balance, wait for funding, register for dust.
 *
 * **Typical usage:**
 * ```kotlin
 * Box(modifier = Modifier.fillMaxSize()) {
 *     // your app's main content
 *     YourScreen()
 *
 *     // pill anchored top-right; sheet appears on tap
 *     WalletStatusPanel(
 *         network = MidnightNetwork.UNDEPLOYED,
 *         modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
 *     )
 * }
 * ```
 *
 * **State ownership:** the panel ships its own [WalletPanelViewModel] which
 * handles SeedVault unlock + SDK bootstrap. Host apps do not need to hold any
 * wallet state. If a host wants to drive the panel from its own ViewModel
 * (e.g. share the SDK with other features), pass a custom [viewModel].
 *
 * @param network The Midnight network all actions target. Switching networks
 *   rebuilds the SDK on next action.
 * @param modifier Modifier applied to the pill — typical placement is
 *   `Modifier.align(Alignment.TopEnd).padding(...)`.
 * @param colors UI palette; defaults match dark-themed example apps.
 * @param viewModel Custom panel VM. Defaults to one created by [WalletPanelViewModel.Factory].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletStatusPanel(
    network: MidnightNetwork,
    modifier: Modifier = Modifier,
    colors: WalletPanelColors = WalletPanelColors.Default,
    viewModel: WalletPanelViewModel = viewModel(factory = WalletPanelViewModel.Factory),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val formatter = remember { BalanceFormatter() }

    // The pill — always rendered. Tap opens the sheet.
    WalletPill(
        status = status,
        formatter = formatter,
        colors = colors,
        modifier = modifier.clickable { sheetOpen = true },
    )

    // FragmentActivity is required by SeedVault.loadSeed / storeSeed for the
    // biometric prompt. ComponentActivity (the AppCompat base) extends it, but
    // raw Activity doesn't — example apps that integrate this panel must use
    // a FragmentActivity-derived host (ComponentActivity counts).
    val activity = LocalContext.current as? FragmentActivity
    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = colors.sheetBackground,
            dragHandle = null,
        ) {
            WalletSheetContent(
                status = status,
                formatter = formatter,
                colors = colors,
                onRefreshBalance = { activity?.let { viewModel.refreshBalance(network, it) } },
                onWaitForFunding = { activity?.let { viewModel.waitForFunding(network, it) } },
                onRegisterDust = { activity?.let { viewModel.registerDust(network, it) } },
                onClose = {
                    coroutineScope.launch {
                        sheetState.hide()
                        sheetOpen = false
                    }
                },
            )
        }
    }
}

// ── Pill ──

@Composable
private fun WalletPill(
    status: WalletStatus,
    formatter: BalanceFormatter,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
) {
    val label = pillLabel(status, formatter)
    val isError = status is WalletStatus.Error
    val borderColor = if (isError) colors.error else colors.pillBorder

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(colors.pillBackground)
            .border(width = 1.5.dp, color = borderColor, shape = RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (status is WalletStatus.Loading || (status is WalletStatus.Ready && status.busy != null)) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = colors.accent,
            )
        }
        Text(
            text = label,
            color = if (isError) colors.error else colors.onPill,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "▾",
            color = colors.onPillDim,
            fontSize = 14.sp,
        )
    }
}

private fun pillLabel(status: WalletStatus, formatter: BalanceFormatter): String = when (status) {
    is WalletStatus.None -> "wallet"
    is WalletStatus.Loading -> "loading…"
    is WalletStatus.Ready -> buildString {
        // Asymmetric: NIGHT is the primary asset (unmarked), DUST gets a "D"
        // suffix. K/M/B abbreviations so the pill width stays predictable as
        // amounts grow; integer-only below 1K. Full precision is in the sheet.
        //
        // NIGHT aggregates shielded + unshielded — the pill is a "do I have
        // enough to do something?" signal, not a portfolio view. The shielded
        // breakdown lives in the sheet; the next iteration adds a shield
        // badge here when status.balance.hasShielded.
        append(formatter.formatAbbreviated(status.balance.totalNight, "NIGHT"))
        append(" · ")
        append(formatter.formatAbbreviated(status.balance.dust, "DUST"))
        append("D")
        if (status.balance.dustRegistered) append(" · ✓")
    }
    is WalletStatus.Error -> "error"
}

// ── Sheet content ──

@Composable
private fun WalletSheetContent(
    status: WalletStatus,
    formatter: BalanceFormatter,
    colors: WalletPanelColors,
    onRefreshBalance: () -> Unit,
    onWaitForFunding: () -> Unit,
    onRegisterDust: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Minimum height so the sheet feels like a real surface, not a
            // sliver. Content can expand past this when state grows (long
            // address line + busy/message rows).
            .heightIn(min = 420.dp)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text(
            "wallet status",
            color = colors.onSheetDim,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(20.dp))

        when (status) {
            is WalletStatus.None -> Text(
                "Read balance to bootstrap the wallet. First press shows a biometric prompt to seal/load the seed via SeedVault.",
                color = colors.onSheetSubtle,
                fontSize = 14.sp,
            )
            is WalletStatus.Loading -> Text(status.stage, color = colors.onSheetDim, fontSize = 16.sp)
            is WalletStatus.Ready -> ReadyBody(status, formatter, colors, onCopy = { txt ->
                clipboard.setText(AnnotatedString(txt))
            })
            is WalletStatus.Error -> Text("error: ${status.message}", color = colors.error, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(28.dp))

        val busy = status is WalletStatus.Loading ||
            (status is WalletStatus.Ready && status.busy != null)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PanelButton("balance", enabled = !busy, modifier = Modifier.weight(1f), colors = colors, onClick = onRefreshBalance)
            PanelButton("fund", enabled = !busy, modifier = Modifier.weight(1f), colors = colors, onClick = onWaitForFunding)
            PanelButton("register", enabled = !busy, modifier = Modifier.weight(1f), colors = colors, onClick = onRegisterDust)
        }
        Spacer(modifier = Modifier.height(10.dp))
        PanelButton("close", enabled = true, modifier = Modifier.fillMaxWidth(), colors = colors, onClick = onClose)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ReadyBody(
    status: WalletStatus.Ready,
    formatter: BalanceFormatter,
    colors: WalletPanelColors,
    onCopy: (String) -> Unit,
) {
    // Address — always visible, tap-to-copy.
    Text("address", color = colors.onSheetSubtle, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = status.address,
        color = colors.onSheet,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clickable { onCopy(status.address) },
    )

    Spacer(modifier = Modifier.height(14.dp))

    // Airdrop command — always rendered so the user can copy/paste before
    // tapping `fund`. waitForFunding may return instantly if already funded,
    // making this the only chance to see the command otherwise.
    val airdropCmd = "mn airdrop 10000 --wallet ${status.address}"
    Text("airdrop command", color = colors.onSheetSubtle, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = airdropCmd,
        color = colors.accent,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clickable { onCopy(airdropCmd) },
    )

    Spacer(modifier = Modifier.height(14.dp))

    // Balance — full precision (sheet has room).
    Text("balance", color = colors.onSheetSubtle, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = buildString {
            // Total NIGHT (shielded + unshielded) — pool breakdown is added in
            // the shielded-render iteration. Once that lands, this single row
            // splits into three (unshielded NIGHT / shielded NIGHT / DUST).
            append(formatter.formatCompact(status.balance.totalNight, "NIGHT"))
            append(" · ")
            append(formatter.formatCompact(status.balance.dust, "DUST"))
            if (status.balance.dustRegistered) append(" · ✓")
        },
        color = colors.onSheet,
        fontSize = 14.sp,
    )

    if (status.busy != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(status.busy, color = colors.accent, fontSize = 13.sp)
    }
    if (status.message != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(status.message, color = colors.accent.copy(alpha = 0.8f), fontSize = 13.sp)
    }
}

@Composable
private fun PanelButton(
    text: String,
    enabled: Boolean,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.button,
            contentColor = colors.onButton,
            disabledContainerColor = colors.buttonDisabled,
            disabledContentColor = colors.onButtonDisabled,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Color palette ──

/**
 * Color tokens for [WalletStatusPanel]. Host apps can pass [Custom] with their
 * own values to match a different theme; [Default] matches the dark-themed
 * example apps already in this repo.
 */
data class WalletPanelColors(
    val pillBackground: Color,
    val pillBorder: Color,
    val onPill: Color,
    val onPillDim: Color,
    val sheetBackground: Color,
    val onSheet: Color,
    val onSheetDim: Color,
    val onSheetSubtle: Color,
    val accent: Color,
    val error: Color,
    val button: Color,
    val onButton: Color,
    val buttonDisabled: Color,
    val onButtonDisabled: Color,
) {
    companion object {
        val Default = WalletPanelColors(
            pillBackground = Color(0xFF111111),
            pillBorder = Color.White.copy(alpha = 0.12f),
            onPill = Color.White.copy(alpha = 0.85f),
            onPillDim = Color.White.copy(alpha = 0.35f),
            sheetBackground = Color(0xFF111111),
            onSheet = Color.White,
            onSheetDim = Color.White.copy(alpha = 0.45f),
            onSheetSubtle = Color.White.copy(alpha = 0.25f),
            accent = Color(0xFF64B5F6),
            error = Color(0xFFFF6666),
            button = Color(0xFF1A1A1A),
            onButton = Color.White,
            buttonDisabled = Color.White.copy(alpha = 0.08f),
            onButtonDisabled = Color.White.copy(alpha = 0.25f),
        )
    }
}
