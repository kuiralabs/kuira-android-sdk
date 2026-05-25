package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.sdk.MidnightSdk

/**
 * Seam for constructing a raw [MidnightSdk] from a [WalletConfig] + seed.
 *
 * Exists so [MidnightSdkProvider]'s ownership state machine (build-once,
 * rebuild-on-change, seed-wipe, teardown) is unit-testable without standing
 * up real HD derivation / Room / indexer sockets — tests supply a fake that
 * returns a mock SDK. The production binding lives in [WalletRuntimeModule].
 */
fun interface MidnightSdkFactory {
    /**
     * Build an SDK for [config] using [seed] (64-byte BIP-39 seed).
     *
     * The builder copies [seed] internally; [MidnightSdkProvider] wipes its
     * own view afterward, so implementations must not retain the array.
     */
    fun create(config: WalletConfig, seed: ByteArray): MidnightSdk
}
