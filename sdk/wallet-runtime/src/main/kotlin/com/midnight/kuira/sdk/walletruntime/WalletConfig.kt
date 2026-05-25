package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.core.compact.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork

/**
 * Snapshot of every wallet-level toggle that affects SDK construction.
 *
 * This is the **canonical config** for the embedded wallet. It lives in
 * `sdk:wallet-runtime` (next to [MidnightSdkProvider], the thing that
 * consumes it) rather than in any UI module — the SDK owner is the single
 * source of truth for "which network / proving mode am I on", and UI is
 * just one of several consumers (the wallet panel, BBoard, Kicks).
 *
 * Passed to [MidnightSdkProvider.ensureSdk]. When any field differs from
 * what the live SDK was built with, the provider tears down the current
 * SDK (cancels its subscriptions, wipes seed material) and builds a fresh
 * one. Equality is by-value so the rebuild check is a single `!=`.
 *
 * **Why one struct instead of three parameters:**
 *  - Network + proving mode + proof-server URL all affect SDK construction.
 *    Bundling them avoids "added a new knob, missed updating two of three
 *    call sites" bugs.
 *  - The Compose side can `remember(network, provingMode, proofServerUrl) {
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
