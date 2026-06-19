package com.midnight.kuira.dapp.wallet

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.StarField
import kotlinx.coroutines.delay

/**
 * Reveal flow for the sovereign recovery phrase (#252). The bundled UI for
 * [com.midnight.kuira.sdk.walletseed.WalletRecovery] — a dApp can build its own against the same
 * contract.
 *
 * Two steps gated by [phrase]: a consent step that states the trade-off (this is the deliberate,
 * one-way opt-out from hardware-only custody), then — after a biometric — the 24 words plus a
 * mandatory "I've written it down" acknowledgment. The whole screen runs under FLAG_SECURE so it
 * can't be screenshotted or appear in the recents thumbnail.
 *
 * Visual language matches the rest of the wallet surface (StarField over the void, a GlassPanel
 * for the words, hand-drawn line-art glyphs) — the design-sprint recovery wireframe, ported to the
 * SDK's reachable tokens ([SendPalette]/[GlassPanel]).
 *
 * Stateless w.r.t. the SDK: [onReveal] triggers the biometric reveal (result arrives back as
 * [phrase]); [onConfirmSaved] records the acknowledgment and closes.
 */
@Composable
internal fun WalletRecoveryScreen(
    phrase: List<String>?,
    error: String?,
    colors: WalletPanelColors = WalletPanelColors.Default,
    onReveal: () -> Unit,
    onConfirmSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = remember(colors) { SendPalette.from(colors) }
    SecureScreen()

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.text,
            alpha = if (palette.isLight) STAR_ALPHA_LIGHT else STAR_ALPHA_DARK,
            starCount = STAR_COUNT,
        )

        Column(Modifier.fillMaxSize().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())) {
            RecoveryTopBar(title = "Recovery phrase", palette = palette, onBack = onBack)

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SendDimens.PanelPadding)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + SendDimens.Space24),
            ) {
                Spacer(Modifier.height(SendDimens.Space24))
                if (phrase == null) {
                    ConsentStep(palette = palette, error = error, onReveal = onReveal)
                } else {
                    PhraseStep(phrase = phrase, palette = palette, onConfirmSaved = onConfirmSaved)
                }
            }
        }
    }
}

@Composable
private fun ConsentStep(palette: SendPalette, error: String?, onReveal: () -> Unit) {
    Text(
        "Your wallet is already protected two ways:",
        color = palette.text,
        fontSize = SendType.Body,
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(SendDimens.Space8))
    Bullet("Your passkey on this device", palette)
    Bullet("Synced to your account for a new phone", palette)
    Spacer(Modifier.height(SendDimens.Space16))
    Text(
        "A recovery phrase adds a third, sovereign layer — 24 words that restore your funds even " +
            "if you lose your phone AND your account.",
        color = palette.textSoft,
        fontSize = SendType.Body,
    )
    Spacer(Modifier.height(SendDimens.Space16))
    Bullet("Survives total device + account loss", palette, accent = palette.success)
    Bullet(
        "Once revealed it lives outside secure hardware. Anyone who sees it controls your funds. " +
            "Store it offline.",
        palette,
        accent = palette.error,
    )
    if (error != null) {
        Spacer(Modifier.height(SendDimens.Space12))
        Text(error, color = palette.error, fontSize = SendType.Caption)
    }
    Spacer(Modifier.height(SendDimens.Space24))
    PrimaryButton("Reveal my recovery phrase", palette, enabled = true, onClick = onReveal)
}

@Composable
private fun PhraseStep(phrase: List<String>, palette: SendPalette, onConfirmSaved: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var saved by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    // Auto-clear the clipboard a while after a copy so the phrase doesn't linger there.
    LaunchedEffect(copied) {
        if (copied) {
            delay(CLIPBOARD_CLEAR_MS)
            clipboard.setText(AnnotatedString(""))
            copied = false
        }
    }
    // The timed clear is cancelled if the screen leaves before it fires — so also scrub on dispose,
    // otherwise backing out right after a copy leaves the 24 words sitting in the clipboard.
    DisposableEffect(Unit) {
        onDispose { if (copied) clipboard.setText(AnnotatedString("")) }
    }

    Text(
        "Write these 24 words down, in order, and keep them offline.",
        color = palette.text,
        fontSize = SendType.Body,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    )
    Spacer(Modifier.height(SendDimens.Space16))

    // Security banner — the reveal's most important line, in a bordered card so it reads as a
    // warning, not body copy. The eye-with-a-slash says "keep this hidden".
    GlassPanel(tint = palette.barely, border = palette.hairline, contentPadding = SendDimens.Space12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeOffGlyph(palette.text)
            Spacer(Modifier.width(SendDimens.Space12))
            Text(
                "Anyone with these words can take your funds. Never share them.",
                color = palette.text,
                fontSize = SendType.Caption,
                lineHeight = 18.sp,
            )
        }
    }
    Spacer(Modifier.height(SendDimens.Space24))

    // The words in one card — a numbered 3-column grid (BIP-39 words are ≤ 8 chars, so three fit).
    GlassPanel(tint = palette.text.copy(alpha = GLASS_FILL_ALPHA), border = palette.hairline, contentPadding = SendDimens.Space16) {
        val rows = (phrase.size + WORD_COLUMNS - 1) / WORD_COLUMNS
        for (r in 0 until rows) {
            if (r > 0) Spacer(Modifier.height(SendDimens.Space12))
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until WORD_COLUMNS) {
                    WordCell(r * WORD_COLUMNS + c, phrase, palette, Modifier.weight(1f))
                }
            }
        }
    }
    Spacer(Modifier.height(SendDimens.Space20))

    // Copy — a real pill (auto-clears the clipboard a moment later), centered.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(SendDimens.RadiusFull))
                .background(palette.barely)
                .heightIn(min = SendDimens.RowMinHeightAccessibility)
                .clickable(role = Role.Button) {
                    clipboard.setText(AnnotatedString(phrase.joinToString(" ")))
                    copied = true
                }
                .padding(horizontal = SendDimens.Space16, vertical = SendDimens.Space8),
        ) {
            CopyGlyph(palette.text)
            Spacer(Modifier.width(SendDimens.Space8))
            Text(
                if (copied) "Copied — clears automatically" else "Copy",
                color = palette.text,
                fontSize = SendType.Caption,
                // Announce the copy/auto-clear state change to TalkBack without a focus move.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    Spacer(Modifier.height(SendDimens.Space32))

    // Acknowledge → Done. The whole row toggles (a wider target than the box alone).
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SendDimens.RadiusSm))
            // The whole row is ONE checkbox to TalkBack/Switch Access (single 48dp target with
            // checked state); the Checkbox itself is indicator-only (onCheckedChange = null).
            .toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it }),
    ) {
        Checkbox(
            checked = saved,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = palette.confirm,
                uncheckedColor = palette.textMuted,
                checkmarkColor = palette.onConfirm,
            ),
        )
        Text(
            "I've written these down and stored them safely",
            color = palette.textSoft,
            fontSize = SendType.Caption,
            lineHeight = 18.sp,
        )
    }
    Spacer(Modifier.height(SendDimens.Space16))
    PrimaryButton("Done", palette, enabled = saved, onClick = onConfirmSaved)
}

@Composable
private fun WordCell(index: Int, phrase: List<String>, palette: SendPalette, modifier: Modifier) {
    val word = phrase.getOrNull(index) ?: return Spacer(modifier)
    Row(
        // One node per word so TalkBack reads "Word 3, depth" — clean for transcription, instead
        // of the bare "3" then "depth" two separate stops.
        modifier.semantics(mergeDescendants = true) { contentDescription = "Word ${index + 1}, $word" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed-width monospace number so every word in the column starts at the same x.
        // Kept tight (fits "24") to leave max room for the word — clipping a recovery
        // word would be a data-loss bug, so the word also gets maxLines/ellipsis as a floor.
        Text(
            "${index + 1}",
            color = palette.textMuted,
            fontSize = SendType.Hint,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.width(SendDimens.Space20),
        )
        Spacer(Modifier.width(SendDimens.Space8))
        Text(
            word,
            color = palette.text,
            fontSize = SendType.Body,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Bullet(text: String, palette: SendPalette, accent: Color = palette.textMuted) {
    Row(Modifier.padding(top = SendDimens.Space4)) {
        Text("•", color = accent, fontSize = SendType.Body)
        Text(
            text,
            color = palette.textSoft,
            fontSize = SendType.Caption,
            modifier = Modifier.padding(start = SendDimens.Space8),
        )
    }
}

/**
 * Shared top app bar for the recovery/settings/restore overlays: a back arrow with a full 48dp
 * touch target, the title, and a hairline divider that separates the bar from the content.
 */
@Composable
internal fun RecoveryTopBar(title: String, palette: SendPalette, onBack: () -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(SendDimens.TopBarHeight)
                .padding(horizontal = SendDimens.Space8),
        ) {
            GlyphButton(onClick = onBack, contentDescription = "Back") {
                BackGlyph(color = palette.text)
            }
            Spacer(Modifier.width(SendDimens.Space4))
            Text(
                title,
                color = palette.text,
                fontSize = SendType.Title,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        HorizontalDivider(color = palette.hairline, thickness = SendDimens.DividerThickness)
    }
}

@Composable
internal fun PrimaryButton(label: String, palette: SendPalette, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(SendDimens.ButtonHeight)
            .clip(RoundedCornerShape(SendDimens.RadiusMd))
            .background(if (enabled) palette.confirm else palette.barely)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) palette.onConfirm else palette.textMuted,
            fontSize = SendType.Button,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Adds FLAG_SECURE for the lifetime of the composition (no screenshots / recents thumbnail). */
@Composable
internal fun SecureScreen() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private const val CLIPBOARD_CLEAR_MS = 30_000L
private const val WORD_COLUMNS = 3
