package com.midnight.kuira.dapp.wallet

import com.midnight.kuira.sdk.WalletBalance

/**
 * UI state for [WalletStatusPanel] / [WalletPanelViewModel].
 *
 * The panel walks a host's wallet through "no wallet yet → loading →
 * funded/registered → maybe error". Each variant maps 1:1 to what the pill +
 * sheet render. See [WalletPanelViewModel] for the transitions.
 */
sealed class WalletStatus {
    /** No SDK built yet — first launch, or after a reset. */
    data object None : WalletStatus()

    /** Building MidnightSdk and reading the first balance. [stage] feeds the sheet's status line. */
    data class Loading(val stage: String) : WalletStatus()

    /**
     * SDK ready, latest balance known.
     *
     * @property address Unshielded Bech32m address — receives external NIGHT
     *   transfers and is the address the SDK builds transactions against.
     * @property shieldedAddress Shielded Bech32m address — destination for
     *   private NIGHT transfers. Derived deterministically from the same seed
     *   so it's stable for the wallet's lifetime.
     * @property balance Most recent snapshot from `sdk.wallet.balance()`.
     * @property busy Set while a long-running action (waitForFunding / register) is in flight; null when idle.
     * @property message One-line status from the last action (success or failure detail).
     */
    data class Ready(
        val address: String,
        val shieldedAddress: String,
        val balance: WalletBalance,
        val busy: String? = null,
        val message: String? = null,
    ) : WalletStatus()

    /** SDK couldn't build or balance couldn't be read. */
    data class Error(val message: String) : WalletStatus()

    /**
     * No sigil forged yet — wallet bootstrap can't proceed because
     * the BIP-39 seed is derived from the passkey PRF. The host
     * should render a "forge your sigil to continue" affordance and
     * pump the user to [com.midnight.kuira.dapp.sigil.SigilStatusPanel];
     * once the forge completes the next refreshBalance call will
     * succeed.
     */
    data object SigilRequired : WalletStatus()
}
