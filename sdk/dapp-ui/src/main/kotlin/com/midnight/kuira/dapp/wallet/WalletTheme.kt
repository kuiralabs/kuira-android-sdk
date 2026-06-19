package com.midnight.kuira.dapp.wallet

import android.content.Context
import androidx.compose.ui.graphics.Color

/**
 * A pickable appearance theme: a display name + the full [WalletPanelColors] it maps to.
 *
 * The default is Kuira's monochrome "Void" — the brand. Color is **opt-in**: the rest are
 * established, openly-licensed (MIT) palettes designed for dark UIs, applied faithfully from
 * their published configs (attribution in [WalletThemes]). A theme never floods the UI with
 * hue — it shifts the surface + text tone and tints the *emphasis* (focus, selection, sync
 * progress) with the palette's accent; the reserved status poles (success green / error red)
 * come from each palette's own green/red so they stay coherent.
 *
 * Themeable surface for hosts: a dApp can read [WalletThemes.all] to render its own picker, or
 * ignore this entirely and keep passing a fixed [WalletPanelColors].
 *
 * @property id stable key persisted in [ThemeStore]; never localize it.
 * @property label human-facing name shown under the swatch.
 * @property colors the palette this theme resolves to (dark surface).
 */
data class WalletTheme(
    val id: String,
    val label: String,
    val colors: WalletPanelColors,
)

/**
 * The built-in appearance themes, in display order. [Default] (Kuira monochrome) is first and is
 * the fallback for an unknown/absent persisted id.
 *
 * Palette credits (all MIT): Catppuccin (catppuccin/catppuccin), Nord (nordtheme.com),
 * Dracula (draculatheme.com), Tokyo Night (folke/tokyonight.nvim), Rosé Pine (rosepinetheme.com).
 * Hex values taken verbatim from each project's published palette.
 */
object WalletThemes {

    /** Kuira's monochrome Void — the brand default. Mirrors [WalletPanelColors.Default]. */
    val Default = WalletTheme(id = "kuira_mono", label = "Monochrome", colors = WalletPanelColors.Default)

    /** Kuira's light "Paper" variant. Mirrors [WalletPanelColors.Light]. */
    val KuiraLight = WalletTheme(id = "kuira_light", label = "Paper", colors = WalletPanelColors.Light)

    val CatppuccinMocha = WalletTheme(
        id = "catppuccin_mocha",
        label = "Catppuccin",
        colors = darkTheme(
            base = Color(0xFF1E1E2E),     // base
            surface = Color(0xFF313244),  // surface0
            text = Color(0xFFCDD6F4),     // text
            muted = Color(0xFFA6ADC8),    // subtext0
            faint = Color(0xFF45475A),    // surface1
            accent = Color(0xFFCBA6F7),   // mauve
            error = Color(0xFFF38BA8),    // red
            positive = Color(0xFFA6E3A1), // green
        ),
    )

    val Nord = WalletTheme(
        id = "nord",
        label = "Nord",
        colors = darkTheme(
            base = Color(0xFF2E3440),     // nord0
            surface = Color(0xFF3B4252),  // nord1
            text = Color(0xFFECEFF4),     // nord6
            muted = Color(0xFFD8DEE9),    // nord4
            faint = Color(0xFF4C566A),    // nord3
            accent = Color(0xFF88C0D0),   // nord8 (frost)
            error = Color(0xFFBF616A),    // nord11
            positive = Color(0xFFA3BE8C), // nord14
        ),
    )

    val Dracula = WalletTheme(
        id = "dracula",
        label = "Dracula",
        colors = darkTheme(
            base = Color(0xFF282A36),     // background
            surface = Color(0xFF44475A),  // selection / current line
            text = Color(0xFFF8F8F2),     // foreground
            muted = Color(0xFF8B93C7),    // lightened comment (raw #6272A4 is 3.03:1 on bg — fails AA)
            faint = Color(0xFF44475A),    // selection
            accent = Color(0xFFBD93F9),   // purple
            error = Color(0xFFFF5555),    // red
            positive = Color(0xFF50FA7B), // green
        ),
    )

    val TokyoNight = WalletTheme(
        id = "tokyo_night",
        label = "Tokyo Night",
        colors = darkTheme(
            base = Color(0xFF1A1B26),     // bg
            surface = Color(0xFF292E42),  // bg_highlight
            text = Color(0xFFC0CAF5),     // fg
            muted = Color(0xFFA9B1D6),    // fg_dark (secondary text — comment #565f89 fails AA on bg)
            faint = Color(0xFF565F89),    // comment (decorative hairline)
            accent = Color(0xFF7AA2F7),   // blue
            error = Color(0xFFF7768E),    // red
            positive = Color(0xFF9ECE6A), // green
        ),
    )

    val RosePine = WalletTheme(
        id = "rose_pine",
        label = "Rosé Pine",
        colors = darkTheme(
            base = Color(0xFF191724),     // base
            surface = Color(0xFF1F1D2E),  // surface
            text = Color(0xFFE0DEF4),     // text
            muted = Color(0xFF908CAA),    // subtle
            faint = Color(0xFF403D52),    // highlightMed
            accent = Color(0xFFC4A7E7),   // iris
            error = Color(0xFFEB6F92),    // love
            positive = Color(0xFF9CCFD8), // foam
        ),
    )

    /** Display order — brand first, then the opt-in palettes. */
    val all: List<WalletTheme> = listOf(
        Default, KuiraLight, CatppuccinMocha, Nord, Dracula, TokyoNight, RosePine,
    )

    /** The theme for [id], or [Default] if the id is unknown (e.g. a removed/renamed theme). */
    fun byId(id: String?): WalletTheme = all.firstOrNull { it.id == id } ?: Default
}

/**
 * Maps a small set of palette roles onto the 14-field [WalletPanelColors]. The pill and the sheet
 * share one [base] surface (matching the monochrome default's flat elevation); [surface] is the
 * slightly-lifted fill behind frosted panels and ENABLED buttons. Status poles ([error]/[positive])
 * are kept distinct so the success-check / error semantics read in each palette's own green/red.
 */
private fun darkTheme(
    base: Color,
    surface: Color,
    text: Color,
    muted: Color,
    faint: Color,
    accent: Color,
    error: Color,
    positive: Color,
): WalletPanelColors = WalletPanelColors(
    pillBackground = base,
    pillBorder = faint,
    onPill = text,
    onPillDim = muted,
    sheetBackground = base,
    onSheet = text,
    onSheetDim = muted,
    onSheetSubtle = faint,
    accent = accent,
    error = error,
    button = surface,
    onButton = text,
    // Must read clearly dimmer than [surface] (the enabled fill) so a disabled button isn't
    // mistaken for an active one — a faint translucent tint, mirroring the monochrome LightBarely.
    buttonDisabled = text.copy(alpha = THEME_DISABLED_FILL_ALPHA),
    onButtonDisabled = muted,
    positive = positive,
)

private const val THEME_DISABLED_FILL_ALPHA = 0.10f

/**
 * Durable store for the chosen appearance theme — a single id in the wallet's existing
 * [SharedPreferences][Context.getSharedPreferences] file (no new dependency, survives process
 * death). The UI keeps an in-memory mirror for instant recomposition; this is the source of truth
 * read on first launch.
 */
object ThemeStore {
    private const val PREFS_NAME = "wallet_panel"
    private const val KEY_THEME_ID = "appearance_theme_id"

    /** The persisted theme id, or null if the user never picked one (→ [WalletThemes.Default]). */
    fun selectedThemeId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_THEME_ID, null)

    fun setSelectedThemeId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME_ID, id).apply()
    }
}
