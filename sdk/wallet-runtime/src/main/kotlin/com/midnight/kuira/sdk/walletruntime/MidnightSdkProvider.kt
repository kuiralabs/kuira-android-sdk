package com.midnight.kuira.sdk.walletruntime

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.sdk.walletseed.WalletSeedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide owner of the single live [MidnightSdk] instance.
 *
 * **Why this exists.** Before this, every consumer that needed a wallet built
 * its own SDK — the wallet panel and BBoard each constructed one, so the app
 * opened two indexer WebSockets, replayed zswap from genesis twice, and ran
 * two dust DBs. Two instances of the same wallet on the same seed and network
 * was never intended; it was duplication, not redundancy. This provider holds
 * exactly one SDK and hands it to all consumers.
 *
 * **Ownership model — one authority, many followers.**
 *  - The **config authority** (the wallet panel — the only UI with the
 *    network / proving-mode / proof-server toggles) calls [ensureSdk] with a
 *    full [WalletConfig]. That is the sole place config changes enter the
 *    system, which is what makes [activeConfig] a single source of truth.
 *  - **Followers** (BBoard's contract ops, Kicks's match manager) don't own
 *    config. They call [awaitSdk] for the shared handle and read
 *    [activeConfig] for "which chain am I on". They never pass a competing
 *    config, so the provider never thrashes between two configs.
 *
 * **Lifecycle.** The SDK is a process-singleton, not view-model-scoped. It
 * survives activity recreation (rotation) — a net win, since rebuilding means
 * a fresh biometric prompt + resync. Teardown is therefore explicit via
 * [close] (logout / wallet reset). No caller invokes [close] today; it's the
 * seam a future session-auto-lock (idle timeout) will use.
 *
 * @see MidnightSdkFactory for the construction seam this delegates to.
 */
@Singleton
class MidnightSdkProvider @Inject constructor(
    private val walletSeedSource: WalletSeedSource,
    private val sdkFactory: MidnightSdkFactory,
) {
    /**
     * Serializes [ensureSdk] so two consumers racing the first build (or a
     * build against a rebuild) can't construct two SDKs. Followers wait on
     * [sdk] (a StateFlow), not this lock, so they don't contend here.
     */
    private val buildMutex = Mutex()

    private val _sdk = MutableStateFlow<MidnightSdk?>(null)

    /** The live SDK, or null before the first build / after [close]. */
    val sdk: StateFlow<MidnightSdk?> = _sdk.asStateFlow()

    private val _activeConfig = MutableStateFlow<WalletConfig?>(null)

    /**
     * The config the live [sdk] was built with — the canonical answer to
     * "which network / proving mode is the wallet on". Null when no SDK exists.
     */
    val activeConfig: StateFlow<WalletConfig?> = _activeConfig.asStateFlow()

    /**
     * Build the SDK for [config], or reuse the live one if [config] is
     * unchanged. On a config change, the previous SDK is closed (its
     * subscriptions cancelled) before the new one is built.
     *
     * Called by the config authority (wallet panel). Triggers the seed
     * bootstrap on first build — i.e. the biometric prompt — via
     * [WalletSeedSource.ensureSeedReady].
     *
     * @throws com.midnight.kuira.core.identity.backup.SigilRequiredException
     *   if no passkey is forged yet (propagated from the seed source).
     */
    suspend fun ensureSdk(activity: FragmentActivity, config: WalletConfig): MidnightSdk =
        buildMutex.withLock {
            _sdk.value?.let { existing ->
                if (_activeConfig.value == config) return@withLock existing
                Log.i(TAG, "Config changed (${_activeConfig.value} → $config) — rebuilding SDK")
                teardown()
            }

            val seed = walletSeedSource.ensureSeedReady(activity)
            val built = try {
                sdkFactory.create(config, seed)
            } finally {
                // Builder copies the seed; wipe our view so BIP-39 material
                // doesn't linger on the heap (even if create() threw).
                seed.fill(0)
            }

            // Wallet proving-key readiness (local-tmp shortcut → S3 fallback)
            // is part of "the SDK is usable". The SDK owns the recipe; we just
            // await it. dApp-specific contract keys are NOT our concern — those
            // stay with the dApp (e.g. BBoard's post/takeDown verifier keys).
            built.provingKeyManager.ensureWalletKeysAvailable(logger = { Log.i(TAG, it) })

            // Publish config before the SDK: any awaitSdk() waiter unblocks on
            // a non-null sdk, and must see the matching activeConfig when it does.
            _activeConfig.value = config
            _sdk.value = built
            Log.i(TAG, "SDK ready for $config")
            built
        }

    /**
     * Suspend until an SDK exists, then return it. For followers that consume
     * the shared SDK but don't drive config (BBoard contract ops). If the
     * authority never builds (e.g. the panel is gated on an un-forged sigil),
     * this waits — by design, since a follower has no wallet to act on until
     * the authority bootstraps one.
     */
    suspend fun awaitSdk(): MidnightSdk = sdk.filterNotNull().first()

    /**
     * Tear down the live SDK and clear state (logout / wallet reset).
     *
     * Not mutex-guarded: intended for a deliberate teardown when no build is
     * in flight, not for racing an in-progress [ensureSdk].
     */
    fun close() {
        teardown()
    }

    private fun teardown() {
        _sdk.value?.close()
        _sdk.value = null
        _activeConfig.value = null
    }

    private companion object {
        const val TAG = "MidnightSdkProvider"
    }
}
