package com.midnight.example.common.sigil

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Drop-in sigil identity pill for example apps. Mirrors
 * [com.midnight.example.common.wallet.WalletStatusPanel]'s shape and lives
 * next to it in [com.midnight.example.common.PanelBar]:
 *
 *  - Pill anchored top-left in the panel bar; the wallet pill takes top-right.
 *  - Tap opens a TOP sheet — visual mirror of the wallet panel's bottom
 *    sheet. The sheet renders state-dependent content: a "forge sigil"
 *    CTA when there's no identity yet, a spinner during the passkey
 *    ceremony, the DID + root key once forged, or an error + retry on
 *    failure.
 *  - Pill label: truncated DID (or "no sigil" / "forging…" / "sigil
 *    error" per state). Future: Midnames `.night` domain resolution.
 *
 * **State ownership:** ships its own [SigilPanelViewModel] which owns the
 * passkey manager + persisted-DID prefs. Host apps don't need to wire
 * anything beyond placing this composable in [PanelBar].
 */
@Composable
fun SigilStatusPanel(
    modifier: Modifier = Modifier,
    colors: SigilPanelColors = SigilPanelColors.Default,
    viewModel: SigilPanelViewModel = viewModel(factory = SigilPanelViewModel.Factory),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    // Activity is required by PasskeyManager.createPasskey — the Credential
    // Manager prompt hangs off it. Same reachability constraint as the
    // wallet panel: hosts must use a FragmentActivity-derived host (most
    // ComponentActivity-based apps do).
    val activity = LocalContext.current as? FragmentActivity

    SigilPill(
        status = status,
        colors = colors,
        modifier = modifier.clickable { sheetOpen = true },
    )

    if (sheetOpen) {
        TopSheet(
            colors = colors,
            onDismiss = { sheetOpen = false },
        ) {
            SigilSheetContent(
                status = status,
                colors = colors,
                onForgeSigil = { activity?.let { viewModel.forgeSigil(it) } },
            )
        }
    }
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
            ),
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
    val tail = did.removePrefix("did:key:").take(TRUNCATED_DID_LENGTH)
    return "did:$tail…"
}

private const val TRUNCATED_DID_LENGTH = 12

// ── Top sheet ──

/**
 * Minimal top sheet — full-screen [Popup] with a translucent scrim and a
 * card anchored to the top edge. Material 3 doesn't ship a "TopModalSheet"
 * so this is hand-rolled.
 *
 * **No animation yet** — step 2 lands the structural sheet, step 3 will
 * add a slide-down-from-top transition. The Popup itself appears/
 * disappears synchronously, which is acceptable for a dev-tooling panel
 * (jarring, not broken).
 *
 * Dismisses on:
 *  - Scrim tap (`dismissOnClickOutside = true` via [PopupProperties])
 *  - System back press (`focusable = true`)
 */
@Composable
private fun TopSheet(
    colors: SigilPanelColors,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Popup(
        alignment = Alignment.TopCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
    ) {
        // Full-screen overlay so the scrim covers everything (including the
        // panel bar that opened us). Tapping the scrim dismisses the sheet.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    onClick = onDismiss,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ),
        ) {
            // Card anchored top with a system-bar-aware top padding so it
            // doesn't sit under the status bar. Clickable with a no-op
            // consumer so taps on the card itself don't reach the scrim
            // dismiss handler underneath.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .clip(RoundedCornerShape(bottomStart = SigilDimens.SheetCornerRadius, bottomEnd = SigilDimens.SheetCornerRadius))
                    .background(colors.sheetBackground)
                    .clickable(
                        onClick = { /* swallow */ },
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    )
                    .padding(
                        horizontal = SigilDimens.SheetHorizontalPadding,
                        vertical = SigilDimens.SheetVerticalPadding,
                    ),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SigilSheetContent(
    status: SigilStatus,
    colors: SigilPanelColors,
    onForgeSigil: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    Text(
        text = "sigil identity",
        color = colors.onSheetDim,
        fontSize = SigilType.SheetTitle,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(SigilDimens.SheetTitleGap))

    when (status) {
        is SigilStatus.None -> NoneBody(colors = colors, onForgeSigil = onForgeSigil)
        is SigilStatus.Creating -> CreatingBody(stage = status.stage, colors = colors)
        is SigilStatus.Forged -> ForgedBody(
            forged = status,
            colors = colors,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
        )
        is SigilStatus.Error -> ErrorBody(
            message = status.message,
            colors = colors,
            onRetry = onForgeSigil,
        )
    }
}

@Composable
private fun NoneBody(colors: SigilPanelColors, onForgeSigil: () -> Unit) {
    Text(
        "Create a passkey to establish your identity. One DID, stable across all Midnight dApps.",
        color = colors.onSheetSubtle,
        fontSize = SigilType.Body,
    )
    Spacer(modifier = Modifier.height(SigilDimens.SheetSectionGap))
    SheetButton(text = "forge sigil", enabled = true, colors = colors, onClick = onForgeSigil)
}

@Composable
private fun CreatingBody(stage: String, colors: SigilPanelColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            color = colors.accent,
            strokeWidth = SigilDimens.PillSpinnerStroke,
            modifier = Modifier.size(SigilDimens.SheetSpinnerSize),
        )
        Spacer(modifier = Modifier.size(SigilDimens.PillItemGap))
        Text(stage, color = colors.onSheetDim, fontSize = SigilType.Body)
    }
}

@Composable
private fun ForgedBody(
    forged: SigilStatus.Forged,
    colors: SigilPanelColors,
    onCopy: (String) -> Unit,
) {
    MonoField(label = "did", value = forged.did, colors = colors, onCopy = onCopy)
    Spacer(modifier = Modifier.height(SigilDimens.SheetSectionGap))
    MonoField(label = "root key (P-256)", value = forged.publicKeyHex, colors = colors, onCopy = onCopy)
}

@Composable
private fun ErrorBody(message: String, colors: SigilPanelColors, onRetry: () -> Unit) {
    Text(message, color = colors.error, fontSize = SigilType.Body)
    Spacer(modifier = Modifier.height(SigilDimens.SheetSectionGap))
    SheetButton(text = "retry", enabled = true, colors = colors, onClick = onRetry)
}

@Composable
private fun MonoField(
    label: String,
    value: String,
    colors: SigilPanelColors,
    onCopy: (String) -> Unit,
) {
    Text(label, color = colors.onSheetSubtle, fontSize = SigilType.FieldLabel, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(SigilDimens.SheetLabelGap))
    Text(
        text = value,
        color = colors.onSheet,
        fontSize = SigilType.FieldValue,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clickable { onCopy(value) },
    )
}

@Composable
private fun SheetButton(
    text: String,
    enabled: Boolean,
    colors: SigilPanelColors,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(SigilDimens.ButtonHeight),
        shape = RoundedCornerShape(SigilDimens.ButtonCornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.button,
            contentColor = colors.onButton,
        ),
    ) {
        Text(text, fontSize = SigilType.ButtonText)
    }
}

// ── Design tokens ──

private object SigilDimens {
    val PillCornerRadius = 22.dp
    val PillBorderWidth = 1.5.dp
    val PillHorizontalPadding = 18.dp
    val PillVerticalPadding = 12.dp
    val PillItemGap = 8.dp
    val PillSpinnerSize = 14.dp
    val PillSpinnerStroke = 2.dp

    // Top sheet.
    val SheetCornerRadius = 20.dp
    val SheetHorizontalPadding = 24.dp
    val SheetVerticalPadding = 20.dp
    val SheetTitleGap = 20.dp
    val SheetSectionGap = 14.dp
    val SheetLabelGap = 4.dp
    val SheetSpinnerSize = 16.dp

    // Buttons inside the sheet.
    val ButtonHeight = 48.dp
    val ButtonCornerRadius = 12.dp
}

private object SigilType {
    val PillText = 14.sp
    val SheetTitle = 14.sp
    val Body = 13.sp
    val FieldLabel = 11.sp
    val FieldValue = 13.sp
    val ButtonText = 13.sp
}

// ── Color palette ──

/**
 * Visual tokens for [SigilStatusPanel]. Matches the geometry of
 * [com.midnight.example.common.wallet.WalletPanelColors] so the two pills
 * in [com.midnight.example.common.PanelBar] read as a pair.
 */
data class SigilPanelColors(
    val pillBackground: Color,
    val pillBorder: Color,
    val onPill: Color,
    val onPillDim: Color,
    val sheetBackground: Color,
    val onSheet: Color,
    val onSheetDim: Color,
    val onSheetSubtle: Color,
    val scrim: Color,
    val accent: Color,
    val error: Color,
    val button: Color,
    val onButton: Color,
) {
    companion object {
        val Default = SigilPanelColors(
            pillBackground = Color(0xFF111111),
            pillBorder = Color.White.copy(alpha = 0.12f),
            onPill = Color.White.copy(alpha = 0.85f),
            onPillDim = Color.White.copy(alpha = 0.35f),
            sheetBackground = Color(0xFF111111),
            onSheet = Color.White,
            onSheetDim = Color.White.copy(alpha = 0.45f),
            onSheetSubtle = Color.White.copy(alpha = 0.25f),
            scrim = Color.Black.copy(alpha = 0.55f),
            accent = Color(0xFF64B5F6),
            error = Color(0xFFFF6666),
            button = Color(0xFF1A1A1A),
            onButton = Color.White,
        )
    }
}
