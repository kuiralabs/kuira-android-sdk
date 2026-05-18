package com.midnight.kuira.dapp.wallet

import com.midnight.kuira.core.compact.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork

/**
 * Snapshot of every wallet-level toggle the panel exposes to the user.
 *
 * Passed to [WalletPanelViewModel.refreshBalance] and [WalletPanelViewModel.registerDust]
 * — when any field differs from what the SDK was built with, the VM tears
 * down the current SDK (cancels its subscriptions, wipes seed material) and
 * builds a fresh one. Equality is by-value so the rebuild check is a single
 * `!=` comparison.
 *
 * **Why one struct instead of three parameters:**
 *  - Network + proving mode + proof-server URL all affect SDK construction.
 *    Bundling them avoids "added a new knob, missed updating two of three
 *    call sites" bugs.
 *  - Composable side can `remember(network, provingMode, proofServerUrl) {
 *    WalletConfig(...) }` to avoid object-identity thrash in `LaunchedEffect`
 *    keys.
 *
 * @property network Which Midnight network the wallet talks to.
 * @property provingMode How transactions are proved (on-device vs hosted prover).
 * @property proofServerUrl Override for [ProvingMode.REMOTE] — null = use the
 *   network's default proof-server URL (`NetworkConfig.forNetwork(...).proofServerUrl`).
 *   Ignored when [provingMode] is [ProvingMode.LOCAL].
 */
data class WalletConfig(
    val network: MidnightNetwork,
    val provingMode: ProvingMode = ProvingMode.DEFAULT,
    val proofServerUrl: String? = null,
)
