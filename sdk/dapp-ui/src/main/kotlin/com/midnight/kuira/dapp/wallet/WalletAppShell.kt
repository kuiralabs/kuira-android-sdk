package com.midnight.kuira.dapp.wallet

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.midnight.kuira.dapp.lock.SessionLockGate

/**
 * Recommended root wrapper for a host that drops in the SDK's wallet UI.
 *
 * Composes the two independent SDK roots in the right order:
 * ```
 * SessionLockGate {        // OUTERMOST — its lock cover must obscure the overlays too
 *     WalletOverlayHost {  // full-screen Send / Receive / Settings live here
 *         content()        // the host's own app + the pill / PanelBar
 *     }
 * }
 * ```
 * The lock is outermost so a session lock covers EVERYTHING, including any open
 * overlay. Both [SessionLockGate] and [WalletOverlayHost] stay usable on their own;
 * this is the convenience entry point that wires them together.
 *
 * Replace a bare `SessionLockGate { ... }` at the activity root with
 * `WalletAppShell { ... }` to get the activity-window overlays.
 *
 * @param secureWindow forwarded to [SessionLockGate] (FLAG_SECURE for the window).
 * @param appVersion forwarded to [WalletOverlayHost] for the Settings ABOUT section.
 */
@Composable
fun WalletAppShell(
    modifier: Modifier = Modifier,
    secureWindow: Boolean = true,
    appVersion: String? = null,
    content: @Composable () -> Unit,
) {
    SessionLockGate(modifier = modifier, secureWindow = secureWindow) {
        WalletOverlayHost(appVersion = appVersion) {
            content()
        }
    }
}
