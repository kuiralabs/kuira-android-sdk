package com.midnight.kuira.dapp.sigil

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.LottieRunner
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import com.midnight.kuira.dapp.dappPressable
import com.midnight.kuira.dapp.wallet.GLASS_FILL_ALPHA
import com.midnight.kuira.dapp.wallet.GearGlyph
import com.midnight.kuira.dapp.wallet.GlyphButton
import com.midnight.kuira.dapp.wallet.SigilChipUi
import com.midnight.kuira.dapp.wallet.WalletPanelColors
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Drop-in sigil identity pill for example apps. Mirrors
 * [com.midnight.kuira.dapp.wallet.WalletStatusPanel]'s shape and lives
 * next to it in [com.midnight.kuira.dapp.PanelBar]:
 *
 *  - Pill anchored top-left in the panel bar; the wallet pill takes top-right.
 *  - Tap opens a TOP sheet — visual mirror of the wallet panel's bottom
 *    sheet. The sheet renders state-dependent content: a "forge sigil"
 *    CTA when there's no identity yet, a spinner during the passkey
 *    ceremony, the DID once forged, or an error + retry on
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
    viewModel: SigilPanelViewModel = hiltViewModel(),
    /**
     * Fires whenever the panel's internal [SigilStatus] changes. Hosts that
     * want to gate UI on "is there a sigil?" — e.g. an onboarding banner
     * that warns the user they need a sigil before posting — mirror this
     * into their own state. Default no-op.
     */
    onStatusChange: (SigilStatus) -> Unit = {},
    /**
     * Opens the host's Settings surface — fired by the gear in the expanded sheet (shown only
     * once a sigil is Forged, since the settings act on a live identity/wallet). [PanelBar] wires
     * this to the same [com.midnight.kuira.dapp.wallet.WalletSettingsScreen] the wallet pill opens,
     * so the gear is one entry point from either panel. Default no-op hides the gear.
     */
    onOpenSettings: () -> Unit = {},
    /** One-shot: each new non-zero value opens the sheet (a floating host taps the chip → here). */
    openSheetSignal: Int = 0,
    /**
     * Custom pill slot (floating mode): replaces the default pill with the host's surface (e.g. the
     * resizable floating widget), fed the live [SigilChipUi] + an `onTap` that opens the sheet. The
     * panel keeps the sheet + state machine; only the pill's visual is delegated. Null → built-in.
     */
    pill: (@Composable (SigilChipUi, onTap: () -> Unit) -> Unit)? = null,
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    LaunchedEffect(status) { onStatusChange(status) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openSheetSignal) { if (openSheetSignal > 0) sheetOpen = true }

    // Activity is required by PasskeyManager.createPasskey — the Credential
    // Manager prompt hangs off it. Same reachability constraint as the
    // wallet panel: hosts must use a FragmentActivity-derived host (most
    // ComponentActivity-based apps do).
    val activity = LocalContext.current as? FragmentActivity

    if (pill != null) {
        pill(status.toSigilChipUi()) { sheetOpen = true }
    } else {
        SigilPill(
            status = status,
            colors = colors,
            // dappPressable gives the pill the pressed/hover/focus state layer +
            // press scale, matching the rest of the app's interactive surfaces.
            modifier = modifier.dappPressable(
                shape = RoundedCornerShape(SigilDimens.PillCornerRadius),
            ) { sheetOpen = true },
        )
    }

    if (sheetOpen) {
        TopSheet(
            colors = colors,
            onDismiss = { sheetOpen = false },
        ) {
            SigilSheetContent(
                status = status,
                colors = colors,
                onForgeSigil = { activity?.let { viewModel.forgeSigil(it) } },
                onRestore = { activity?.let { viewModel.restoreSeed(it) } },
                onSignOut = { activity?.let { viewModel.signOut(it) } },
                onStartFresh = {
                    viewModel.dismissBackup()
                    sheetOpen = false
                },
                onOpenSettings = {
                    sheetOpen = false
                    onOpenSettings()
                },
            )
        }
    }
}

/** Maps the sigil state machine to the floating widget's display model (all states representable). */
private fun SigilStatus.toSigilChipUi(): SigilChipUi = when (this) {
    is SigilStatus.Forged -> SigilChipUi("Sigil", "Forged · Protected", shortDid(did), did)
    is SigilStatus.Creating -> SigilChipUi("Sigil", "Creating…", "", "")
    is SigilStatus.None -> SigilChipUi("Sigil", "Tap to create", "", "")
    is SigilStatus.BackupAvailable -> SigilChipUi("Sigil", "Restore available", "", "")
    is SigilStatus.Initializing -> SigilChipUi("Sigil", "…", "", "")
    is SigilStatus.Error -> SigilChipUi("Sigil", "Error", "", "")
}

private fun shortDid(did: String): String =
    if (did.length <= 22) did else "${did.take(14)}…${did.takeLast(4)}"

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
        // Left-side avatar slot. Real Midnames / user-uploaded avatars land
        // here later; for now, a deterministic placeholder derived from the
        // DID (forged states) or a neutral icon (None / Error states).
        // Spinner takes over the slot while a passkey ceremony is in flight.
        PillAvatar(status = status, colors = colors)
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
 * Tiny round icon that anchors the left edge of the pill.
 *
 *  - [SigilStatus.None]: muted gray circle, no glyph — the "you have no
 *    identity yet" placeholder.
 *  - [SigilStatus.Creating]: a spinner replaces the circle so the user
 *    sees their tap was registered. Same size as the circle so the pill
 *    width doesn't jump between states.
 *  - [SigilStatus.Forged]: filled circle whose color is derived from the
 *    DID (so the same DID always renders the same hue across sessions
 *    and devices) plus a single letter — the first multibase character
 *    after the `did:key:` prefix. Deterministic, no network round-trip.
 *  - [SigilStatus.Error]: red circle with `!`.
 *
 * Real avatars (Midnames profile, user-uploaded) replace this layer in a
 * follow-up; the API stays — a fixed-size composable that takes a
 * [SigilStatus].
 */
@Composable
private fun PillAvatar(status: SigilStatus, colors: SigilPanelColors) {
    if (status is SigilStatus.Creating || status is SigilStatus.Initializing) {
        CircularProgressIndicator(
            modifier = Modifier.size(SigilDimens.PillAvatarSize),
            strokeWidth = SigilDimens.PillSpinnerStroke,
            color = colors.accent,
        )
        return
    }
    // Monochrome per the brand: a frosted disc, never a colored hue. Identity is carried by the
    // monogram letter (deterministic per DID) + the truncated DID label beside it — not color.
    val (bg, fg, glyph) = when (status) {
        is SigilStatus.None -> Triple(colors.avatarPlaceholderBg, colors.onPillDim, null)
        // Same neutral avatar as None, plus a "?" glyph hinting that an
        // action is pending. The sheet body explains the choice.
        is SigilStatus.BackupAvailable -> Triple(colors.avatarPlaceholderBg, colors.onPillDim, "?")
        is SigilStatus.Forged -> Triple(
            colors.onPill.copy(alpha = AVATAR_FILL_ALPHA),
            colors.onPill,
            avatarGlyphFromDid(status.did),
        )
        is SigilStatus.Error -> Triple(colors.onPill.copy(alpha = AVATAR_FILL_ALPHA), colors.onPill, "!")
        is SigilStatus.Creating, is SigilStatus.Initializing ->
            error("unreachable — handled above")
    }
    Box(
        modifier = Modifier
            .size(SigilDimens.PillAvatarSize)
            .clip(RoundedCornerShape(SigilDimens.PillAvatarSize / 2))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) {
            Text(
                text = glyph,
                color = fg,
                fontSize = SigilType.PillAvatarGlyph,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Frosted-disc fill for the (monochrome) forged/error avatar — translucent so the pill reads as glass. */
private const val AVATAR_FILL_ALPHA = 0.20f

/**
 * First uppercase letter of the DID's multibase tail. We skip the `z`
 * prefix that every `did:key` over multibase Base58btc starts with — it
 * conveys nothing identity-wise — and use the next character. Letters
 * are uppercased so they read as monogram-style avatars.
 */
internal fun avatarGlyphFromDid(did: String): String {
    val tail = did.removePrefix("did:key:")
    // `z` is the multibase prefix indicating Base58btc — same on every did:key,
    // so it's noise. Take the next character; fall back to `?` if the tail is
    // shorter than expected (defensive — shouldn't happen with valid DIDs).
    val candidate = tail.drop(1).firstOrNull()?.uppercaseChar() ?: '?'
    return candidate.toString()
}

internal fun pillLabel(status: SigilStatus): String = when (status) {
    is SigilStatus.None -> "no sigil"
    is SigilStatus.BackupAvailable -> "backup found — tap"
    is SigilStatus.Initializing -> "checking…"
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
    onRestore: () -> Unit,
    onSignOut: () -> Unit = {},
    onStartFresh: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current

    // Title on the left; a settings gear on the right once a sigil exists (the settings act on a
    // live identity/wallet). Shares the bundled WalletSettingsScreen with the wallet pill's gear.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "sigil identity",
            color = colors.onSheetDim,
            fontSize = SigilType.SheetTitle,
            fontWeight = FontWeight.Medium,
        )
        if (status is SigilStatus.Forged) {
            GlyphButton(onClick = onOpenSettings, contentDescription = "Settings") {
                GearGlyph(color = colors.onSheetDim)
            }
        }
    }
    Spacer(modifier = Modifier.height(SigilDimens.SheetTitleGap))

    when (status) {
        is SigilStatus.None -> NoneBody(
            colors = colors,
            onForgeSigil = onForgeSigil,
            onRestore = onRestore,
        )
        is SigilStatus.BackupAvailable -> BackupAvailableBody(
            colors = colors,
            onRestore = onRestore,
            onStartFresh = onStartFresh,
        )
        is SigilStatus.Initializing -> CreatingBody(
            stage = "checking for cloud backup…",
            colors = colors,
        )
        is SigilStatus.Creating -> CreatingBody(stage = status.stage, colors = colors)
        is SigilStatus.Forged -> ForgedBody(
            forged = status,
            colors = colors,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
            onSignOut = onSignOut,
        )
        is SigilStatus.Error -> ErrorBody(
            message = status.message,
            colors = colors,
            onForgeSigil = onForgeSigil,
            onRestore = onRestore,
        )
    }
}

@Composable
private fun NoneBody(
    colors: SigilPanelColors,
    onForgeSigil: () -> Unit,
    onRestore: () -> Unit,
) {
    Text(
        "Create a passkey to establish your identity. One DID, stable across all Midnight dApps.",
        color = colors.onSheetSubtle,
        fontSize = SigilType.Body,
    )
    Spacer(modifier = Modifier.height(SigilDimens.SheetSectionGap))
    SheetButton(text = "forge sigil", enabled = true, colors = colors, onClick = onForgeSigil)
    Spacer(modifier = Modifier.height(SigilDimens.SheetSmallGap))
    SheetButton(text = "restore from cloud", enabled = true, colors = colors, onClick = onRestore, dimmed = true)
}

/**
 * Surface when a Block Store backup was detected on launch but no local
 * sigil exists yet. Two paths:
 *  - **restore previous** (primary): runs the PRF passkey flow,
 *    decrypts the cloud blob, re-creates the SeedVault entry,
 *    SIGKILL+relaunch for a clean rebootstrap.
 *  - **start fresh** (secondary): forges a brand-new sigil and lets
 *    the wallet panel auto-bootstrap with a freshly-generated seed.
 *    Wipes the prior cloud association going forward.
 */
@Composable
private fun BackupAvailableBody(
    colors: SigilPanelColors,
    onRestore: () -> Unit,
    onStartFresh: () -> Unit,
) {
    Text(
        "A previous wallet backup was found in your cloud. Restore it, or start fresh on this device.",
        color = colors.onSheetSubtle,
        fontSize = SigilType.Body,
    )
    Spacer(modifier = Modifier.height(SigilDimens.SheetSectionGap))
    SheetButton(text = "restore previous", enabled = true, colors = colors, onClick = onRestore)
    Spacer(modifier = Modifier.height(SigilDimens.SheetSmallGap))
    SheetButton(text = "start fresh", enabled = true, colors = colors, onClick = onStartFresh, dimmed = true)
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
    onSignOut: () -> Unit,
) {
    // A signed-in sigil shows only its identity. Recovery is the passkey and
    // it's automatic, so there's no manual backup/restore action here. The
    // P-256 root key stays out of the UI (raw jargon); hosts that need it —
    // e.g. BBoard's authorizeAccessKey — read it from SigilStatus.Forged.
    //
    // Branded identity card: the runner mark over the DID, frosted — so the sparse
    // panel reads as a real surface, not a stub.
    GlassPanel(
        tint = colors.onSheet.copy(alpha = GLASS_FILL_ALPHA),
        border = colors.onSheetSubtle,
        contentPadding = SigilDimens.SheetVerticalPadding,
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LottieRunner(modifier = Modifier.size(SigilDimens.RunnerMarkSize), color = colors.onSheet, animate = false)
        }
        Spacer(modifier = Modifier.height(SigilDimens.SheetSectionGap))
        MonoField(label = "did", value = forged.did, colors = colors, onCopy = onCopy)
    }

    var showSignOutConfirm by remember { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(SigilDimens.SheetSectionGap))
    // Danger zone — deliberately understated (monochrome, not red: sign-out is
    // recoverable, not a financial-danger signal). The confirm + biometric (in the
    // VM) are the real guards.
    Text(
        text = "sign out",
        color = colors.onSheetDim,
        fontSize = SigilType.Body,
        modifier = Modifier
            .clickable { showSignOutConfirm = true }
            .padding(vertical = SigilDimens.SheetSmallGap),
    )

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            // Theme to the sigil panel's (dark) palette. A bare AlertDialog falls back to the host's
            // default Material scheme — a light container — which left the "Sign out" label
            // (colors.onSheet, a light tone) as white-on-light and unreadable, and clashed with the
            // dark app. Mirrors BackupSection's themed dialog.
            containerColor = colors.sheetBackground,
            titleContentColor = colors.onSheet,
            textContentColor = colors.onSheetDim,
            title = { Text("Sign out?") },
            text = {
                Text(
                    "You'll need your passkey to sign back in. Your wallet and " +
                        "cloud backup are not deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    onSignOut()
                }) { Text("Sign out", color = colors.onSheet) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel", color = colors.onSheetDim) }
            },
        )
    }
}

/**
 * Error UI: show the failure reason as a banner, then both entry points back
 * — `forge sigil` and `restore from cloud` — so the user can recover. Pre-fix
 * this was a single "retry" button hardcoded to forge, which stranded users
 * who hit a restore error (e.g. "No backup found") into creating a new sigil
 * rather than letting them re-attempt the restore.
 */
@Composable
private fun ErrorBody(
    message: String,
    colors: SigilPanelColors,
    onForgeSigil: () -> Unit,
    onRestore: () -> Unit,
) {
    Text(message, color = colors.error, fontSize = SigilType.Body)
    Spacer(modifier = Modifier.height(SigilDimens.SheetSectionGap))
    SheetButton(text = "forge sigil", enabled = true, colors = colors, onClick = onForgeSigil)
    Spacer(modifier = Modifier.height(SigilDimens.SheetSmallGap))
    SheetButton(text = "restore from cloud", enabled = true, colors = colors, onClick = onRestore, dimmed = true)
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
    /**
     * Visual de-emphasis for secondary actions (backup / restore / test prf
     * once a sigil exists, restore-from-cloud when one doesn't yet). Dimmed
     * buttons share the same shape + size but use a lower-contrast text
     * color so they sit visually below the primary action.
     */
    dimmed: Boolean = false,
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
            contentColor = if (dimmed) colors.onSheetDim else colors.onButton,
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
    val PillAvatarSize = 22.dp   // Matches the visual weight of the spinner that replaces it during Creating.

    // Top sheet.
    val SheetCornerRadius = 20.dp
    val SheetHorizontalPadding = 24.dp
    val SheetVerticalPadding = 20.dp
    val SheetTitleGap = 20.dp
    val SheetSectionGap = 14.dp
    val SheetSmallGap = 8.dp
    val SheetLabelGap = 4.dp
    val SheetSpinnerSize = 16.dp
    val RunnerMarkSize = 40.dp   // static runner brand mark on the forged identity card

    // Buttons inside the sheet.
    val ButtonHeight = 54.dp     // prominent CTA — above the 48dp HIG floor
    val ButtonCornerRadius = 12.dp
}

private object SigilType {
    val PillText = 14.sp
    val PillAvatarGlyph = 11.sp   // Monogram letter inside the avatar circle.
    val SheetTitle = 14.sp
    val Body = 13.sp
    val FieldLabel = 11.sp
    val FieldValue = 13.sp
    val ButtonText = 15.sp
}

// ── Color palette ──

/**
 * Visual tokens for [SigilStatusPanel]. Matches the geometry of
 * [com.midnight.kuira.dapp.wallet.WalletPanelColors] so the two pills
 * in [com.midnight.kuira.dapp.PanelBar] read as a pair.
 *
 * **API contract:** part of the dapp-ui public surface — consumers may
 * supply a custom palette to theme the sigil panel. `@Immutable` so
 * Compose treats the type as stable across recompositions.
 */
@Immutable
data class SigilPanelColors(
    val pillBackground: Color,
    val pillBorder: Color,
    val onPill: Color,
    val onPillDim: Color,
    val avatarPlaceholderBg: Color,
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
        // On-brand "dusk" dark default — matches [WalletPanelColors.Default] so
        // the two PanelBar pills read as one themed unit. Sourced from the shared
        // brand palette ([MidnightColors] in core:designsystem).
        val Default = SigilPanelColors(
            pillBackground = MidnightColors.VoidElevated,
            pillBorder = MidnightColors.LightFaint,
            onPill = MidnightColors.LightSoft,
            onPillDim = MidnightColors.LightMuted,
            avatarPlaceholderBg = MidnightColors.ConfirmSurface,
            sheetBackground = MidnightColors.VoidElevated,
            onSheet = MidnightColors.Light,
            onSheetDim = MidnightColors.LightMuted,
            onSheetSubtle = MidnightColors.LightFaint,
            scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
            accent = MidnightColors.Light, // monochrome brand (was SuccessText green)
            error = MidnightColors.ErrorText,
            button = MidnightColors.ButtonSurface,
            onButton = MidnightColors.Light,
        )

        /**
         * Adapts an appearance-theme [WalletPanelColors] into the sigil pill's vocabulary so both
         * PanelBar pills retheme as ONE unit. The two palettes share every pole except the sigil's
         * avatar-placeholder fill (→ the wallet's button surface) and [scrim] (a fixed dim overlay).
         */
        fun from(w: WalletPanelColors): SigilPanelColors = SigilPanelColors(
            pillBackground = w.pillBackground,
            pillBorder = w.pillBorder,
            onPill = w.onPill,
            onPillDim = w.onPillDim,
            avatarPlaceholderBg = w.button,
            sheetBackground = w.sheetBackground,
            onSheet = w.onSheet,
            onSheetDim = w.onSheetDim,
            onSheetSubtle = w.onSheetSubtle,
            scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
            accent = w.accent,
            error = w.error,
            button = w.button,
            onButton = w.onButton,
        )

        private const val SCRIM_ALPHA = 0.55f
    }
}
