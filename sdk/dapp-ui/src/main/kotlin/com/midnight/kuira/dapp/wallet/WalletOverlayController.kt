package com.midnight.kuira.dapp.wallet

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The full-screen wallet overlays the SDK can surface on top of a host's content.
 *
 * Each is a SCREEN (not a floating dialog): Send / Receive / Settings. Recovery is
 * NOT listed here — it's a reveal nested INSIDE the Settings overlay, exactly as it
 * was in the old `WalletSettingsOverlay`.
 *
 * [launchSource] (Settings only) records which pill's gear opened Settings so a host
 * that surfaces both a wallet and a sigil pill (e.g. `PanelBar`) can still tell them
 * apart after the fact. It defaults to [SettingsLaunchSource.Wallet] for the
 * standalone panel path where there is no sigil pill.
 */
@Stable
sealed interface WalletOverlay {
    data object Send : WalletOverlay
    data object Receive : WalletOverlay
    data class Settings(
        val launchSource: SettingsLaunchSource = SettingsLaunchSource.Wallet,
    ) : WalletOverlay
}

/** Which pill's gear opened the shared Settings overlay. */
enum class SettingsLaunchSource { Wallet, Sigil }

/**
 * UI-navigation state holder for the wallet's full-screen overlays.
 *
 * Plain class (NOT Hilt): it holds nothing but transient UI-navigation state, which
 * Compose owns. [WalletOverlayHost] `remember {}`s one and provides it over its
 * content via [LocalWalletOverlay]; the pill / [PanelBar] read the ambient instance
 * and call [open] / [close].
 *
 * The current overlay is plain Compose state, so any reader that touches [active]
 * recomposes when it changes.
 */
@Stable
class WalletOverlayController {
    var active: WalletOverlay? by mutableStateOf(null)
        private set

    /**
     * App version shown in the Settings ABOUT section. The SDK never fabricates this.
     * [WalletOverlayHost] seeds it from its own `appVersion`; a content-level host that
     * owns the value (e.g. `PanelBar`) can set it here so the host need not thread the
     * param. Null hides the ABOUT section.
     */
    var appVersion: String? by mutableStateOf(null)

    /**
     * The chosen appearance theme id, as observable Compose state.
     *
     * Settings (which the host renders) writes this when the user picks a theme; the pill /
     * [com.midnight.kuira.dapp.PanelBar] reads it so the pill re-themes live — Settings and
     * the pill are split across the host boundary now, so a shared observable here is what
     * keeps them on ONE palette. `null` = never picked (→ [WalletThemes.Default]). The durable
     * source of truth remains `ThemeStore`; this is the in-memory mirror that drives recomposition.
     */
    var selectedThemeId: String? by mutableStateOf(null)

    /**
     * One-shot "re-open the WALLET pill's sheet" counter. Bumped by [close] when the closed
     * overlay originated from the wallet pill (Send / Receive, or Settings opened from the
     * wallet gear). The wallet pill keys a `LaunchedEffect` on it so Back from an overlay lands
     * back on the sheet the user came from — not on the bare host content. Starts at 0 (no-op).
     */
    var walletSheetReopen by mutableIntStateOf(0)
        private set

    /** Sigil-pill twin of [walletSheetReopen] — bumped when Settings opened from the SIGIL gear closes. */
    var sigilSheetReopen by mutableIntStateOf(0)
        private set

    /**
     * True once a [WalletOverlayHost] has adopted this controller as its render surface. The
     * ambient DEFAULT controller (see [LocalWalletOverlay]) is never adopted — [open] on it flips
     * state nobody renders, which is a silent-broken gear/Send/Receive. The flag lets [open] turn
     * that silence into an actionable log line without breaking the no-crash contract.
     */
    internal var hostAttached: Boolean = false

    /**
     * Whether the NEXT [close] should re-open the originating pill's sheet. Set by [open].
     *
     * A sheet-launched overlay (the default) reopens so Back returns to the sheet it came from.
     * An overlay launched DIRECTLY from an enlarged floating chip's own action buttons (Send /
     * Receive / Settings on the widget itself) must NOT — there was no sheet, so springing one
     * open on close is a surprise sheet over the persistent widget.
     */
    private var reopenSheetOnClose: Boolean = true

    fun open(overlay: WalletOverlay, reopenSheetOnClose: Boolean = true) {
        if (!hostAttached) {
            Log.w(
                "WalletOverlay",
                "open($overlay) called but no WalletOverlayHost renders this controller — the " +
                    "overlay will NOT appear. Wrap the app root in WalletAppShell { } (or " +
                    "WalletOverlayHost { }) so full-screen Settings / Send / Receive have a surface.",
            )
        }
        this.reopenSheetOnClose = reopenSheetOnClose
        active = overlay
    }

    /**
     * Close the active overlay and re-open the ORIGINATING pill's sheet (Send / Receive ⇒ wallet;
     * Settings ⇒ its [WalletOverlay.Settings.launchSource]) by bumping that pill's reopen counter.
     * This restores the old back-to-sheet stack: Back from any overlay returns to the sheet that
     * launched it rather than dropping to bare content. Use [dismissForLock] for the no-reopen path.
     */
    fun close() {
        val closed = active
        val reopen = reopenSheetOnClose
        active = null
        if (!reopen) return
        when (closed) {
            is WalletOverlay.Send,
            is WalletOverlay.Receive -> walletSheetReopen++
            is WalletOverlay.Settings -> when (closed.launchSource) {
                SettingsLaunchSource.Wallet -> walletSheetReopen++
                SettingsLaunchSource.Sigil -> sigilSheetReopen++
            }
            null -> {}
        }
    }

    /**
     * Close the active overlay WITHOUT re-opening any sheet. Used when a session lock tears
     * the overlay down: the lock cover must be the only surface, and the sheet must NOT spring back
     * on unlock. Deliberately does not bump a reopen counter.
     */
    fun dismissForLock() {
        active = null
    }
}

/**
 * Ambient [WalletOverlayController]. Default is a real-but-orphaned controller whose
 * `open`/`close` simply flip state nobody renders — so a standalone pill that is NOT
 * wrapped in [WalletOverlayHost] never crashes; it just can't surface the overlays
 * (a standalone `WalletStatusPanel()` with no host has nowhere to put a full screen).
 */
val LocalWalletOverlay = compositionLocalOf { WalletOverlayController() }
