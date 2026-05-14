package com.midnight.example.common.wallet

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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.network.MidnightNetwork

/**
 * Full-screen receive surface — addresses, QR, and (on canary networks) the
 * `mn airdrop` command the user can copy into a host terminal.
 *
 * Reached from [WalletStatusPanel]'s sheet via the "receive" action. Closes
 * itself when [onBack] is invoked.
 *
 * **Why a dedicated screen** (vs another section in the sheet): the QR code
 * needs real estate, the address is long enough to wrap onto multiple lines,
 * and the unshielded/shielded tab toggle wants horizontal width. A
 * ModalBottomSheet at full height could host this, but a plain full-screen
 * overlay reads more like a receive flow in mainstream wallets (Lace,
 * Phantom, MetaMask) and avoids the sheet's mandatory drag-handle / scrim.
 *
 * @param unshieldedAddress Public Bech32m address that receives external NIGHT
 *   transfers and is what the SDK builds transactions against.
 * @param shieldedAddress Public Bech32m address (`mn_shield-addr_<net>1…`) for
 *   private NIGHT transfers.
 * @param network Drives the network badge and gates whether the airdrop CLI
 *   command appears (only on UNDEPLOYED — PREPROD/PREVIEW have a faucet UI
 *   instead of a CLI).
 * @param colors Shared palette with the rest of [WalletStatusPanel].
 * @param onBack User-initiated dismissal (back arrow / system back gesture).
 */
@Composable
fun WalletReceiveScreen(
    unshieldedAddress: String,
    shieldedAddress: String,
    network: MidnightNetwork,
    colors: WalletPanelColors = WalletPanelColors.Default,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var selectedTab by rememberSaveable { mutableStateOf(ReceiveTab.UNSHIELDED) }
    val currentAddress = when (selectedTab) {
        ReceiveTab.UNSHIELDED -> unshieldedAddress
        ReceiveTab.SHIELDED -> shieldedAddress
    }

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
            ReceiveTopBar(colors, onBack)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ReceiveDimens.HorizontalPadding)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            + ReceiveDimens.BottomSpacing,
                    ),
            ) {
                Spacer(modifier = Modifier.height(ReceiveDimens.HeaderTopGap))

                Text(
                    text = "Receive NIGHT on ${network.displayName}",
                    color = colors.onSheet,
                    fontSize = ReceiveType.Header,
                    fontWeight = FontWeight.W300,
                )
                Spacer(modifier = Modifier.height(ReceiveDimens.HeaderBadgeGap))
                NetworkBadge(network = network, colors = colors)

                Spacer(modifier = Modifier.height(ReceiveDimens.SectionGap))

                // QR + address card.
                QrAddressCard(
                    address = currentAddress,
                    colors = colors,
                    onCopy = { clipboard.setText(AnnotatedString(currentAddress)) },
                )

                Spacer(modifier = Modifier.height(ReceiveDimens.SectionGap))

                ReceiveTabRow(selectedTab = selectedTab, colors = colors) { selectedTab = it }

                // Canary affordance — only on UNDEPLOYED (localnet) since
                // PREPROD/PREVIEW have no local `mn` CLI; on those networks
                // funding goes through a faucet UI instead.
                if (network == MidnightNetwork.UNDEPLOYED) {
                    Spacer(modifier = Modifier.height(ReceiveDimens.SectionGap))
                    FundCommandBlock(
                        tab = selectedTab,
                        unshieldedAddress = unshieldedAddress,
                        shieldedAddress = shieldedAddress,
                        colors = colors,
                        onCopy = { cmd -> clipboard.setText(AnnotatedString(cmd)) },
                    )
                }
            }
        }
    }
}

// ── Internal components ──

@Composable
private fun ReceiveTopBar(colors: WalletPanelColors, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ReceiveDimens.TopBarHeight)
            .padding(horizontal = ReceiveDimens.HorizontalPadding),
    ) {
        // Back chevron — plain text glyph to avoid a Material-Icons dependency.
        Text(
            text = "←",
            color = colors.onSheet,
            fontSize = ReceiveType.BackGlyph,
            modifier = Modifier
                .clip(RoundedCornerShape(ReceiveDimens.BackHitRadius))
                .clickable(onClick = onBack)
                .padding(ReceiveDimens.BackHitPadding),
        )
        Spacer(modifier = Modifier.size(ReceiveDimens.TopBarTitleGap))
        Text(
            text = "Receive",
            color = colors.onSheet,
            fontSize = ReceiveType.TopBarTitle,
            fontWeight = FontWeight.W400,
        )
    }
}

@Composable
private fun NetworkBadge(network: MidnightNetwork, colors: WalletPanelColors) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(ReceiveDimens.BadgeCornerRadius))
            .background(colors.button)
            .padding(
                horizontal = ReceiveDimens.BadgeHorizontalPadding,
                vertical = ReceiveDimens.BadgeVerticalPadding,
            ),
    ) {
        Text(
            text = network.displayName.uppercase(),
            color = colors.onSheetDim,
            fontSize = ReceiveType.Badge,
            fontWeight = FontWeight.W400,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun QrAddressCard(
    address: String,
    colors: WalletPanelColors,
    onCopy: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ReceiveDimens.CardCornerRadius))
            .background(colors.button)
            .border(
                width = ReceiveDimens.CardBorderWidth,
                color = colors.pillBorder,
                shape = RoundedCornerShape(ReceiveDimens.CardCornerRadius),
            )
            .padding(ReceiveDimens.CardPadding),
    ) {
        // QR is on a white background regardless of theme — most camera apps
        // scan poorly when the dark side is the data.
        Box(
            modifier = Modifier
                .size(ReceiveDimens.QrSize)
                .clip(RoundedCornerShape(ReceiveDimens.QrCornerRadius))
                .background(Color.White)
                .padding(ReceiveDimens.QrInnerPadding),
            contentAlignment = Alignment.Center,
        ) {
            QrCode(text = address, size = ReceiveDimens.QrSize - ReceiveDimens.QrInnerPadding * 2)
        }
        Spacer(modifier = Modifier.height(ReceiveDimens.QrAddressGap))
        Text(
            text = address,
            color = colors.onSheet,
            fontSize = ReceiveType.Address,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable(onClick = onCopy),
        )
        Spacer(modifier = Modifier.height(ReceiveDimens.AddressHintGap))
        Text(
            text = "tap address to copy",
            color = colors.onSheetSubtle,
            fontSize = ReceiveType.AddressHint,
        )
    }
}

@Composable
private fun ReceiveTabRow(
    selectedTab: ReceiveTab,
    colors: WalletPanelColors,
    onSelect: (ReceiveTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ReceiveDimens.TabCornerRadius))
            .background(colors.button)
            .padding(ReceiveDimens.TabPadding),
        horizontalArrangement = Arrangement.spacedBy(ReceiveDimens.TabGap),
    ) {
        ReceiveTab.entries.forEach { tab ->
            val isActive = tab == selectedTab
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(ReceiveDimens.TabPillCornerRadius))
                    .background(if (isActive) colors.onSheet else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = ReceiveDimens.TabPillVerticalPadding),
            ) {
                Text(
                    text = tab.label,
                    color = if (isActive) colors.sheetBackground else colors.onSheetDim,
                    fontSize = ReceiveType.Tab,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

/**
 * Localnet-only canary affordance: the exact `mn airdrop` line to fund the
 * currently-selected address. Both tabs use the same airdrop form; the
 * shielded variant just appends `--shielded`:
 *
 *  - Unshielded: `mn airdrop <amount> --wallet <addr>` mints NIGHT into a
 *    public UTXO at the given Bech32m address.
 *  - Shielded: `mn airdrop <amount> --wallet <addr> --shielded` does the
 *    same into a Zswap coin owned by the matching shielded address.
 *
 * Hidden on PREPROD/PREVIEW where the local `mn` CLI isn't reachable — those
 * networks have a faucet UI instead.
 */
@Composable
private fun FundCommandBlock(
    tab: ReceiveTab,
    unshieldedAddress: String,
    shieldedAddress: String,
    colors: WalletPanelColors,
    onCopy: (String) -> Unit,
) {
    val targetAddress = when (tab) {
        ReceiveTab.UNSHIELDED -> unshieldedAddress
        ReceiveTab.SHIELDED -> shieldedAddress
    }
    val shieldedFlag = if (tab == ReceiveTab.SHIELDED) " --shielded" else ""
    val cmd = "mn airdrop $DEFAULT_AIRDROP_AMOUNT --wallet $targetAddress$shieldedFlag"
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "fund via cli",
            color = colors.onSheetSubtle,
            fontSize = ReceiveType.SectionLabel,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(ReceiveDimens.AirdropLabelGap))
        Text(
            text = cmd,
            color = colors.accent,
            fontSize = ReceiveType.AirdropCmd,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ReceiveDimens.AirdropCornerRadius))
                .background(colors.button)
                .padding(ReceiveDimens.AirdropPadding)
                .clickable { onCopy(cmd) },
        )
    }
}

// ── Tabs ──

private enum class ReceiveTab(val label: String) {
    UNSHIELDED("UNSHIELDED"),
    SHIELDED("SHIELDED"),
}

// ── Local design tokens ──
//
// Kept here (rather than merged into [PanelDimens] / [PanelType]) because
// the Receive screen has its own scale — bigger headers, full-screen
// padding, a hero-sized QR. Local tokens keep the panel's smaller surfaces
// from inheriting layout decisions that only make sense for a full screen.

private object ReceiveDimens {
    val HorizontalPadding = 20.dp
    val BottomSpacing = 24.dp
    val SectionGap = 24.dp

    // Top bar.
    val TopBarHeight = 56.dp
    val TopBarTitleGap = 16.dp
    val BackHitRadius = 20.dp
    val BackHitPadding = 8.dp

    // Header.
    val HeaderTopGap = 24.dp
    val HeaderBadgeGap = 8.dp

    // Network badge.
    val BadgeCornerRadius = 100.dp
    val BadgeHorizontalPadding = 12.dp
    val BadgeVerticalPadding = 6.dp

    // QR / address card.
    val CardCornerRadius = 20.dp
    val CardBorderWidth = 1.dp
    val CardPadding = 20.dp
    val QrSize: Dp = 260.dp
    val QrCornerRadius = 12.dp
    val QrInnerPadding = 12.dp
    val QrAddressGap = 16.dp
    val AddressHintGap = 8.dp

    // Tab row.
    val TabCornerRadius = 12.dp
    val TabPadding = 4.dp
    val TabGap = 4.dp
    val TabPillCornerRadius = 10.dp
    val TabPillVerticalPadding = 12.dp

    // Airdrop block.
    val AirdropLabelGap = 8.dp
    val AirdropCornerRadius = 10.dp
    val AirdropPadding = 12.dp
}

private object ReceiveType {
    val TopBarTitle = 16.sp
    val BackGlyph = 24.sp
    val Header = 20.sp
    val Badge = 11.sp
    val Address = 13.sp
    val AddressHint = 11.sp
    val Tab = 11.sp
    val SectionLabel = 11.sp
    val AirdropCmd = 12.sp
}
