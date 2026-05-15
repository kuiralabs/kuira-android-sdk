package com.midnight.example.common.sigil

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Drop-in sigil identity pill for example apps. Mirrors [WalletStatusPanel]'s
 * shape and lives next to it in [PanelBar]:
 *
 *  - Pill anchored top-left in the panel bar; the wallet pill takes top-right.
 *  - Tap (eventually — this is the skeleton commit) opens a TOP sheet that
 *    slides down from the top edge. Visual mirror of the wallet panel's
 *    bottom sheet.
 *  - Pill label: truncated DID (or, in the future, a Midnames `.night`
 *    domain when the lookup is wired). `.night` resolution is deferred.
 *
 * **Skeleton scope (this commit):** pill renders; tap is a no-op. The top
 * sheet, the migrated forge / backup / restore / testPrf actions, and the
 * `.night` domain resolution all land in subsequent commits.
 *
 * **State ownership:** ships its own [SigilPanelViewModel] (initially just a
 * status flow; later, the full passkey + backup pipeline). Hosts can supply
 * their own VM via the [viewModel] param if they want shared state with
 * another feature.
 */
@Composable
fun SigilStatusPanel(
    modifier: Modifier = Modifier,
    colors: SigilPanelColors = SigilPanelColors.Default,
    viewModel: SigilPanelViewModel = viewModel(factory = SigilPanelViewModel.Factory),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    // TODO(panel-step-2): rememberSaveable sheet-open + animate-in-from-top.
    SigilPill(status = status, colors = colors, modifier = modifier)
}

// ── Pill ──

@Composable
private fun SigilPill(
    status: SigilStatus,
    colors: SigilPanelColors,
    modifier: Modifier = Modifier,
) {
    val label = pillLabel(status)
    val isError = status is SigilStatus.Error
    val borderColor = if (isError) colors.error else colors.pillBorder
    val pillShape = RoundedCornerShape(SigilDimens.PillCornerRadius)
    Row(
        modifier = modifier
            .clip(pillShape)
            .background(colors.pillBackground)
            .border(width = SigilDimens.PillBorderWidth, color = borderColor, shape = pillShape)
            .padding(
                horizontal = SigilDimens.PillHorizontalPadding,
                vertical = SigilDimens.PillVerticalPadding,
            )
            .clickable {
                // TODO(panel-step-2): open the top sheet.
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SigilDimens.PillItemGap),
    ) {
        if (status is SigilStatus.Creating) {
            CircularProgressIndicator(
                modifier = Modifier.size(SigilDimens.PillSpinnerSize),
                strokeWidth = SigilDimens.PillSpinnerStroke,
                color = colors.accent,
            )
        }
        Text(
            text = label,
            color = if (isError) colors.error else colors.onPill,
            fontSize = SigilType.PillText,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "▾",
            color = colors.onPillDim,
            fontSize = SigilType.PillText,
        )
    }
}

/**
 * Visible label inside the pill. Short by design — falls back to the
 * truncated DID until Midnames `.night` resolution lands.
 */
internal fun pillLabel(status: SigilStatus): String = when (status) {
    is SigilStatus.None -> "no sigil"
    is SigilStatus.Creating -> "forging…"
    is SigilStatus.Forged -> truncateDid(status.did)
    is SigilStatus.Error -> "sigil error"
}

/**
 * Renders `did:key:zDnae…` as `did:zDnae…` — drop the `:key:` segment that's
 * always the same and keep just the first chunk of the multibase tail.
 * Twelve trailing chars is the sweet spot: long enough to disambiguate
 * between sigils in a debugging session, short enough to fit alongside the
 * wallet pill on a 360dp-wide phone.
 */
internal fun truncateDid(did: String): String {
    // Strip the `did:key:` prefix when present so the user sees more of the
    // unique multibase tail. Falls back to the raw string if the format
    // doesn't match (e.g. a non-did:key DID variant).
    val tail = did.removePrefix("did:key:").take(TRUNCATED_DID_LENGTH)
    return "did:$tail…"
}

private const val TRUNCATED_DID_LENGTH = 12

// ── Design tokens ──

private object SigilDimens {
    val PillCornerRadius = 22.dp
    val PillBorderWidth = 1.5.dp
    val PillHorizontalPadding = 18.dp
    val PillVerticalPadding = 12.dp
    val PillItemGap = 8.dp
    val PillSpinnerSize = 14.dp
    val PillSpinnerStroke = 2.dp
}

private object SigilType {
    val PillText = 14.sp
}

// ── Color palette ──

/**
 * Visual tokens for [SigilStatusPanel]. Matches the geometry of
 * [com.midnight.example.common.wallet.WalletPanelColors] so the two pills
 * in [PanelBar] read as a pair.
 */
data class SigilPanelColors(
    val pillBackground: Color,
    val pillBorder: Color,
    val onPill: Color,
    val onPillDim: Color,
    val accent: Color,
    val error: Color,
) {
    companion object {
        val Default = SigilPanelColors(
            pillBackground = Color(0xFF111111),
            pillBorder = Color.White.copy(alpha = 0.12f),
            onPill = Color.White.copy(alpha = 0.85f),
            onPillDim = Color.White.copy(alpha = 0.35f),
            accent = Color(0xFF64B5F6),
            error = Color(0xFFFF6666),
        )
    }
}
