package com.midnight.kuira.dapp.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Full-screen, edge-to-edge host for the SDK's overlay screens (Send / Receive /
 * Settings / Recovery).
 *
 * **The one thing that matters: `DialogProperties(decorFitsSystemWindows = false)`.**
 * A Compose [Dialog] is a *floating* window by default ([android.view.Window.isFloating]
 * is true), and floating windows fit INSIDE the system bars by design — so the window
 * frame is letterboxed at `[0, statusBarHeight .. height-navBar]` and the content never
 * reaches behind the bars. Poking the window by hand from a `SideEffect` does NOT work:
 * the Dialog re-applies its layout from this property on every pass (`updateProperties`
 * in Compose's `AndroidDialog.android.kt`) and reverts the change.
 *
 * Setting `decorFitsSystemWindows = false` is the SUPPORTED switch. The Dialog then makes
 * its own window non-floating, adds `FLAG_LAYOUT_IN_SCREEN`, sets the display-cutout mode,
 * and sizes it `MATCH_PARENT × MATCH_PARENT` — a genuinely full-bleed window. Each screen's
 * own `WindowInsets` padding keeps content clear of the bars while its `fillMaxSize()`
 * StarField draws behind them.
 *
 * `usePlatformDefaultWidth = false` is required alongside it so the content isn't clamped
 * to the platform dialog width.
 *
 * Like the focusable Popup it replaces, the Dialog consumes Back at the window level and
 * routes it to [onDismiss] (via `dismissOnBackPress`), preserving the Send wizard's
 * `registerBack` step-walking handoff.
 *
 * @param isLight Drives the system-bar icon contrast (dark icons on a light surface,
 *   light icons on a dark one).
 * @param onDismiss Invoked on Back / dismissal request.
 */
@Composable
internal fun EdgeToEdgeOverlay(
    isLight: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            // No scrim — this reads as a full-screen surface, not a dialog.
            window.setDimAmount(0f)
            // Only the icon contrast is ours to set; decorFitsSystemWindows=false already did the
            // full edge-to-edge window setup (non-floating, FLAG_LAYOUT_IN_SCREEN, cutout, MATCH_PARENT).
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isLight
                isAppearanceLightNavigationBars = isLight
            }
        }
        content()
    }
}
