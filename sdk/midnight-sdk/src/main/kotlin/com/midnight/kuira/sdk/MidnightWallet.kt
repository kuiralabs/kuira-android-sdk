package com.midnight.kuira.sdk

import android.util.Log
import com.midnight.kuira.core.compact.BalanceProgress
import com.midnight.kuira.core.compact.TransactionBalancer
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.ReceiptEvent
import com.midnight.kuira.core.indexer.model.TokenTypeMapper
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.repository.SpentDustNullifierStore
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.NodeRpcClient.SubmissionStage
import com.midnight.kuira.core.ledger.api.NodeRpcError
import com.midnight.kuira.core.ledger.api.NodeRpcException
import com.midnight.kuira.core.ledger.api.TransactionRejected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import com.midnight.kuira.core.identity.backup.DriveConsentRequiredException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.math.BigInteger

/**
 * Embedded wallet that handles transaction balancing and submission on-device.
 *
 * Implements [TransactionBalancer] so it can be plugged into
 * [com.midnight.kuira.core.compact.MidnightConfig] as a drop-in replacement
 * for the remote DAppConnectorClient.
 *
 * Uses [DustSyncManager] for session-scoped caching. Handles error 170
 * (InvalidDustSpendProof) by auto-retrying with a fresh dust sync.
 */
class MidnightWallet internal constructor(
    private val dustSyncManager: DustSyncManager,
    private val dustRepository: DustRepository,
    private val indexerClient: IndexerClient,
    private val nodeRpcClient: NodeRpcClient,
    private val balanceRepository: BalanceRepository,
    private val shieldedTracker: ShieldedBalanceTracker,
    private val walletAddress: String,
    private val dustSeed: ByteArray,
    private val provingKeysDir: String,
    private val networkId: String,
    private val spentDustNullifierStore: SpentDustNullifierStore,
    private val dustCloudBackup: DustCloudBackup? = null,
    private val appStateCloudBackup: AppStateCloudBackup? = null,
    receiptEvents: Flow<ReceiptEvent> = emptyFlow(),
) : TransactionBalancer {

    /**
     * Per-transaction inbound NIGHT receipts (#284), classified by UTXO-set provenance in the
     * indexer subscription: a genuine receipt is a transaction that created NIGHT to us without
     * spending any of our UTXOs. Our own sends (which return change to us) are NEVER receipts,
     * so a consumer wiring this to a notification will never tell the user they "received" their
     * own change. Each event carries the exact amount received IN THAT transaction — not a
     * balance delta. Cold by default ([emptyFlow]) when no subscription is wired (e.g. tests).
     */
    val receipts: Flow<ReceiptEvent> = receiptEvents

    private val balanceMutex = Mutex()

    /**
     * Host-registered snapshot of app state to back up automatically on each
     * [refresh] (≤2 KB; e.g. Kicks match witnesses). The host sets this once
     * after obtaining the wallet — the SDK can't inject it (the provider lives
     * above this layer). The upload is silent (seed-keyed) + digest-guarded, so
     * it's a no-op when the snapshot is unchanged. Null → nothing to back up.
     */
    @Volatile
    var appStateProvider: (suspend () -> ByteArray?)? = null

    /**
     * User opt-out for dust cloud backup. When false, [backupDustToCloud] (and
     * therefore [refresh]) **skip the Drive upload entirely** — that's the
     * "disable" semantics: we simply stop uploading. The host persists the
     * preference and re-applies it after each SDK (re)build. Default true.
     */
    @Volatile
    var dustBackupEnabled: Boolean = true

    /**
     * Same "disable = stop uploading" gate for the app-state blob (#246). Set false by
     * [disableAppStateCloudBackup]; the host persists the preference and re-applies it after each
     * SDK (re)build — WITHOUT it a disable wouldn't stick (the next [refresh] would re-upload the
     * deleted blob). Default true.
     */
    @Volatile
    var appStateBackupEnabled: Boolean = true

    /**
     * Live NIGHT-UTXO list (as the registration-filter JSON) wired by [MidnightSdk].
     * Lets [balance]/[balanceFlow] compute `dustRegistered` from true per-UTXO
     * registration state — "are all current NIGHT UTXOs generating dust?" — instead
     * of the dust>0 heuristic (#265). Null → fall back to the heuristic (e.g. before
     * the SDK wires it).
     */
    @Volatile
    var nightUtxoJsonProvider: (suspend () -> String)? = null

    private val _backupStatus = MutableStateFlow(BackupStatusSnapshot())

    /**
     * Observable cloud-backup status (dust + app-state lanes), updated by the
     * backup methods so the UI can show "syncing / up to date / needs consent /
     * failed" instead of a silent log. Identity recovery (the passkey) is sourced
     * separately at the app layer.
     */
    val backupStatus: StateFlow<BackupStatusSnapshot> = _backupStatus.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    /**
     * Observable wallet-sync status (#235). EVERY heavy sync this wallet runs
     * publishes here — [syncDust], the proactive [proactiveDustResync], the
     * shielded + dust [refresh], and the [forceFullSync] genesis rebuild — so a
     * background observer (the foreground-service Live-Update notification) can
     * render progress for whichever phase is in flight without driving the sync
     * itself. The in-app `WalletSyncIndicator` can also collect this instead of
     * the per-call lambda.
     */
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    // Atomic update: dust + app-state backups can report concurrently (a host
    // calling backupAppStateToCloud while refresh() runs backupDustToCloud), so a
    // read-copy-write on .value could drop a lane's update.
    private fun updateDustBackup(status: CloudBackupStatus) {
        _backupStatus.update { it.copy(dust = status) }
    }

    private fun updateAppDataBackup(status: CloudBackupStatus) {
        _backupStatus.update { it.copy(appData = status) }
    }

    /**
     * Dust nullifiers captured during [balanceTransaction], keyed by the balanced
     * tx hex, awaiting their [submitTransaction]. Recorded as spent only once the
     * node accepts the tx — a balanced-but-unsubmitted UTXO is still spendable, so
     * recording it early would wrongly exclude it. [balanceAndSubmit] records
     * directly (it owns both steps) and doesn't use this map.
     */
    private val pendingSpentNullifiers = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Bech32m address this wallet receives NIGHT at. Exposed for dApps that want to display / share it. */
    val address: String get() = walletAddress

    /**
     * Current snapshot of every balance the SDK tracks for this wallet —
     * unshielded NIGHT, shielded NIGHT, DUST, and the registration flag.
     *
     * **Sources (all kept fresh by the SDK; no consumer action required):**
     *  - Unshielded NIGHT: [BalanceRepository] flow over the unshielded-tx
     *    subscription started at SDK-build time.
     *  - Shielded NIGHT: [ShieldedBalanceTracker]'s cache, refreshed by the
     *    zswap-event subscription started at SDK-build time. Returns 0 until
     *    the first sync lands (a few hundred ms on localnet, a few seconds
     *    on PREPROD on first run).
     *  - DUST: [DustRepository] local replay state.
     *
     * `dustRegistered` is a best-effort heuristic — true iff DUST > 0. A
     * fresh wallet that hasn't called [MidnightSdk.registerForDustGeneration]
     * reports false here even with NIGHT funded, because the chain doesn't
     * release spendable dust to an unregistered key.
     *
     * For a forced resync (e.g. before a sequential tx or when the UI wants
     * to skip the subscription's natural cadence), call [refresh] first.
     */
    suspend fun balance(): WalletBalance = withContext(Dispatchers.IO) {
        val tokenBalances = balanceRepository.observeBalances(walletAddress).first()
        val unshielded = tokenBalances
            .firstOrNull { it.tokenType == TokenTypeMapper.NIGHT_SYMBOL }
            ?.balance
            ?: BigInteger.ZERO
        val shielded = shieldedTracker.currentNight()
        val dust = dustRepository.getCurrentBalance(walletAddress)
        WalletBalance(
            unshieldedNight = unshielded,
            shieldedNight = shielded,
            dust = dust,
            dustRegistered = computeDustRegistered(dust),
        )
    }

    /**
     * NIGHT UTXOs (from [nightUtxosJson]) that are NOT yet generating dust — i.e.
     * still need a registration. Matched natively against the current synced dust
     * state's backing-nights, so it's correct regardless of the cache-layer tokens.
     * A null/absent dust state means nothing is registered yet, so all are returned.
     * Drives [MidnightSdk.registerForDustGeneration]'s per-UTXO registration loop.
     */
    suspend fun unregisteredNightUtxos(nightUtxosJson: String): String = withContext(Dispatchers.IO) {
        val state = dustRepository.getLastSyncedState() ?: return@withContext nightUtxosJson
        runCatching { state.filterUnregisteredNight(nightUtxosJson) }.getOrNull() ?: nightUtxosJson
    }

    /**
     * Truthful `dustRegistered`: are ALL current NIGHT UTXOs generating dust? Runs
     * the same per-UTXO registration filter [MidnightSdk.registerForDustGeneration]
     * loops on, against the live NIGHT-UTXO list from [nightUtxoJsonProvider]. This
     * is why a fresh CHANGE UTXO (created by a send) reads as "needs registration"
     * even while older dust lingers — unlike the old dust>0 proxy.
     *
     * Falls back to the dust>0 heuristic when the UTXO list or synced dust state
     * isn't available yet, so a cold start doesn't flash a false "needs registration".
     */
    private suspend fun computeDustRegistered(dust: BigInteger): Boolean {
        // Fast path: no provider wired yet (cold start / unit tests) → heuristic,
        // and crucially NO dispatcher hop, so flow-collection tests stay
        // deterministic. Only the real per-UTXO work offloads to IO.
        val provider = nightUtxoJsonProvider ?: return dust > BigInteger.ZERO
        return withContext(Dispatchers.IO) {
            val nightJson = runCatching { provider() }.getOrNull() ?: return@withContext dust > BigInteger.ZERO
            val nightCount = runCatching { JSONArray(nightJson).length() }.getOrDefault(0)
            if (nightCount == 0) return@withContext false // no NIGHT → nothing is generating dust
            val state = dustRepository.getLastSyncedState() ?: return@withContext dust > BigInteger.ZERO
            val unregistered = runCatching { JSONArray(state.filterUnregisteredNight(nightJson)).length() }
                .getOrNull() ?: return@withContext dust > BigInteger.ZERO
            unregistered == 0
        }
    }

    /**
     * Observable balance — emits a fresh [WalletBalance] whenever the wallet's
     * **unshielded OR shielded** NIGHT changes. Both are driven by background
     * indexer subscriptions, so **externally-received funds (an airdrop, an
     * incoming shielded or unshielded transfer) surface automatically** without
     * a manual refresh. Each emission also re-reads dust.
     *
     * Use this for a live UI (the wallet panel collects it); [balance] stays the
     * one-shot snapshot for callers that just need the current value.
     *
     * Note: a *dust-only* change (e.g. right after
     * [MidnightSdk.registerForDustGeneration]) won't re-emit on its own — that
     * path forces its own refresh.
     */
    fun balanceFlow(): Flow<WalletBalance> =
        combine(
            balanceRepository.observeBalances(walletAddress),
            shieldedTracker.nightFlow,
        ) { tokenBalances, shieldedNight ->
            val unshielded = tokenBalances
                .firstOrNull { it.tokenType == TokenTypeMapper.NIGHT_SYMBOL }
                ?.balance
                ?: BigInteger.ZERO
            val dust = dustRepository.getCurrentBalance(walletAddress)
            WalletBalance(
                unshieldedNight = unshielded,
                shieldedNight = shieldedNight,
                dust = dust,
                dustRegistered = computeDustRegistered(dust),
            )
        }

    /**
     * Suspend until the wallet's NIGHT balance reaches [minNight] or [timeoutMs] elapses.
     *
     * Used by onboarding flows after the user is told "fund this address" — the
     * UI can call this and show a spinner until funding lands, then proceed to
     * [MidnightSdk.registerForDustGeneration]. Throws
     * [kotlinx.coroutines.TimeoutCancellationException] on timeout; callers can
     * catch and re-prompt the user.
     *
     * On the funded edge, forces a fresh dust resync so the next operation
     * doesn't read stale UTXO state. (The unshielded subscription drives NIGHT
     * automatically — dust is on a separate sync path.)
     *
     * @param minNight Minimum NIGHT amount that counts as "funded" (in u128 base units).
     * @param pollIntervalMs Reserved — currently driven by the underlying Flow's emissions, not a poll.
     * @param timeoutMs Wall-clock cap on how long to wait.
     */
    suspend fun waitForFunding(
        minNight: BigInteger,
        @Suppress("UNUSED_PARAMETER") pollIntervalMs: Long = 3_000L,
        timeoutMs: Long = DEFAULT_FUNDING_TIMEOUT_MS,
    ): WalletBalance = withContext(Dispatchers.IO) {
        val funded = withTimeout(timeoutMs) {
            balanceRepository.observeBalances(walletAddress)
                .first { balances ->
                    val night = balances
                        .firstOrNull { it.tokenType == TokenTypeMapper.NIGHT_SYMBOL }
                        ?.balance
                        ?: BigInteger.ZERO
                    night >= minNight
                }
        }
        // Funding arrived → force a dust resync so subsequent ops aren't stale.
        dustSyncManager.invalidateMemo()
        dustSyncManager.ensureSynced()
        val unshielded = funded
            .firstOrNull { it.tokenType == TokenTypeMapper.NIGHT_SYMBOL }
            ?.balance
            ?: BigInteger.ZERO
        val dust = dustRepository.getCurrentBalance(walletAddress)
        WalletBalance(
            unshieldedNight = unshielded,
            // Shielded NIGHT is the SDK's cached value — external funding
            // lands on the unshielded address, so we don't trigger a shielded
            // resync here. Consumers that need a fresh shielded reading can
            // call [refresh] after waitForFunding returns.
            shieldedNight = shieldedTracker.currentNight(),
            dust = dust,
            dustRegistered = computeDustRegistered(dust),
        )
    }

    /**
     * Latest indexer-known chain block timestamp in milliseconds.
     *
     * Used by SDK-internal call sites that need a chain-anchored `ctime` rather
     * than wall-clock — see the Error 170 fix in [tryBalance] (commit 868e0d9)
     * for why this matters. The block timestamp is fetched fresh on every call;
     * it's already cached one layer down by [IndexerClient].
     */
    internal suspend fun indexerBlockTimestampMs(): Long =
        indexerClient.getCurrentBlockWithParams().timestamp

    /**
     * Sync dust state from the blockchain.
     *
     * @param onProgress Optional callback with (eventsProcessed, totalEvents) for progress UX.
     */
    suspend fun syncDust(
        onProgress: (suspend (eventsProcessed: Int, totalEvents: Int) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        runTrackedSync(SyncPhase.DustFull) { publish ->
            dustSyncManager.ensureSynced(onSyncProgress = { processed, total ->
                onProgress?.invoke(processed, total) // external callback (back-compat)
                publish(processed, total)            // observable syncStatus
            })
        }
    }

    /**
     * Proactive delta dust-sync for the live tip-advance path (#235). Runs the
     * incremental refresh under [balanceMutex] — same discipline as [refresh] —
     * so it can't close the shared `DustLocalState` mid-balance. Publishes to
     * [syncStatus]. Used by the background `DustBalanceTracker`; never does a
     * genesis wipe.
     */
    internal suspend fun proactiveDustResync() = withContext(Dispatchers.IO) {
        runTrackedSync(SyncPhase.DustDelta) { publish ->
            balanceMutex.withLock { dustSyncManager.refreshIncremental(onSyncProgress = publish) }
        }
    }

    /**
     * Map a (processed, total) tick onto [syncStatus] under the running
     * [basePhase]. The -1 replay-tail sentinel flips to [SyncPhase.Finalizing]
     * (indeterminate); everything else keeps the base phase.
     */
    private fun publishSyncProgress(basePhase: SyncPhase, eventsProcessed: Int, totalEvents: Int) {
        _syncStatus.value = when {
            eventsProcessed < 0 ->
                SyncStatus.Syncing(null, eventsProcessed, totalEvents, SyncPhase.Finalizing)
            totalEvents > 0 ->
                SyncStatus.Syncing(
                    (eventsProcessed.toFloat() / totalEvents).coerceIn(0f, 1f),
                    eventsProcessed, totalEvents, basePhase,
                )
            else ->
                SyncStatus.Syncing(null, eventsProcessed, totalEvents, basePhase)
        }
    }

    /**
     * Run a sync phase while driving [syncStatus]: Syncing([phase]) → UpToDate on
     * success, Failed on error, Idle on cancellation. The [block]'s `publish`
     * callback carries determinate progress (mapped via [publishSyncProgress]
     * under the same phase). Rethrows so callers keep their existing error/
     * cancellation semantics.
     *
     * Used by every heavy sync — dust, shielded refresh, genesis rebuild — so the
     * single [syncStatus] observer (the background notification) reflects all of
     * them, not just dust.
     */
    private suspend fun runTrackedSync(
        phase: SyncPhase,
        block: suspend (publish: suspend (Int, Int) -> Unit) -> Unit,
    ) {
        _syncStatus.value = SyncStatus.Syncing(null, 0, 0, phase)
        try {
            block { processed, total -> publishSyncProgress(phase, processed, total) }
            _syncStatus.value = SyncStatus.UpToDate
        } catch (ce: CancellationException) {
            _syncStatus.value = SyncStatus.Idle
            throw ce
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Failed(e.message ?: "Sync failed")
            throw e
        }
    }

    override suspend fun balanceTransaction(provenTxHex: String): String = withContext(Dispatchers.IO) {
        balanceMutex.withLock {
            val balanced = doBalance(provenTxHex)
            // Defer recording until submit succeeds (see [pendingSpentNullifiers]).
            if (balanced.spentNullifiers.isNotEmpty()) {
                pendingSpentNullifiers[balanced.txHex] = balanced.spentNullifiers
            }
            balanced.txHex
        }
    }

    override suspend fun submitTransaction(balancedTxHex: String) = withContext(Dispatchers.IO) {
        // Remove the pending entry up front so it can't leak if submit throws.
        val pending = pendingSpentNullifiers.remove(balancedTxHex)
        try {
            nodeRpcClient.submitAndWaitForFinalization(balancedTxHex)
        } catch (e: TransactionRejected) {
            // Error 115: the node says these dust UTXOs are already spent — record
            // them so the caller's next balance excludes them. (The split path can't
            // re-balance here, so we record then rethrow for the caller to retry.)
            if (e.isStaleUtxo) recordSpentNullifiers(pending)
            throw e
        }
        recordSpentNullifiers(pending)
        dustSyncManager.invalidateMemo()
    }

    override suspend fun balanceAndSubmit(
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)?,
    ): Unit = withContext(Dispatchers.IO) { balanceMutex.withLock {
        onProgress?.invoke(BalanceProgress.SyncingDust)

        // Error-170 (stale dust root) recovery escalates: a fast delta re-sync first,
        // then a full genesis rebuild only if that still 170s. Other failures —
        // including error 115 — propagate as a clean BalancingFailed (no in-place
        // loop). With the zero-fee fix in balance_ffi, 0-fee networks spend no dust,
        // so 115 doesn't arise there; PREPROD's own-fee-spend handling is tracked
        // separately (it depends on the indexer reflecting contract-tx fee spends).
        val recoveries: List<Pair<String, suspend () -> Unit>> = listOf(
            "delta re-sync" to { dustSyncManager.refreshIncremental(); Unit },
            "genesis rebuild" to { forceFullSync() },
        )
        var attempt = 0
        while (true) {
            try {
                val balanced = doBalance(provenTxHex, onProgress)
                onProgress?.invoke(BalanceProgress.Submitting)
                nodeRpcClient.submitAndWaitForFinalization(balanced.txHex) { stage ->
                    if (stage == SubmissionStage.IN_BLOCK) {
                        onProgress?.invoke(BalanceProgress.WaitingFinalization)
                    }
                }
                // Record the dust nullifier(s) the node accepted as spent so a later
                // balance can exclude them (no-op on 0-fee, where nothing is spent).
                recordSpentNullifiers(balanced.spentNullifiers)
                dustSyncManager.invalidateMemo()
                return@withLock
            } catch (e: NodeRpcException) {
                if (!isDustSpendProofError(e) || attempt >= recoveries.size) throw e
                val (label, recover) = recoveries[attempt++]
                Log.w(TAG, "Error 170 — $label and retry ($attempt/${recoveries.size})")
                onProgress?.invoke(BalanceProgress.RetryingDustSync)
                recover()
            }
        }
    } }

    private suspend fun doBalance(
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)? = null,
    ): BalanceEnvelope {
        return tryBalance(provenTxHex, onProgress)
            ?: run {
                Log.w(TAG, "Balance failed, forcing full dust re-sync")
                onProgress?.invoke(BalanceProgress.RetryingDustSync)
                forceFullSync()
                tryBalance(provenTxHex, onProgress)
                    ?: throw IllegalStateException("Balance failed after full re-sync, check logcat")
            }
    }

    /** Durably record dust nullifiers the node just accepted as spent, so the
     *  balancer never re-selects them (the dust event stream doesn't reliably
     *  reflect the wallet's own fee spends → otherwise error 115). Best-effort:
     *  the tx already landed, so a storage hiccup must not surface as a failure. */
    private suspend fun recordSpentNullifiers(nullifiers: List<String>?) {
        nullifiers?.forEach { nullifier ->
            runCatching { spentDustNullifierStore.recordSpent(walletAddress, nullifier) }
                .onFailure { Log.w(TAG, "Failed to record spent dust nullifier: ${it.message}") }
        }
    }

    /**
     * Return a dust state fresh enough to balance against the chain tip.
     *
     * Error 170 (InvalidDustSpendProof) happens iff there are dust events between
     * our checkpoint and the block the node verifies the spend against (the tip):
     * the locally-replayed root then lags the tip's root. So probe whether the
     * indexer has dust events beyond our checkpoint; if it does, delta-refresh
     * first (fast — the events exist, so no first-event wait). If not, the memoized
     * state's dust root is already current (later blocks with no dust events don't
     * change it), so balance against the cached state.
     *
     * The probe uses a short timeout and degrades to the cached state on failure —
     * a wrong "not behind" only costs one 170 + the [balanceAndSubmit] retry.
     */
    private suspend fun ensureDustFresh(
        onProgress: (suspend (BalanceProgress) -> Unit)?,
    ): DustLocalState {
        val progress: suspend (Int, Int) -> Unit = { processed, total ->
            onProgress?.invoke(BalanceProgress.SyncingDustProgress(processed, total))
        }
        val checkpoint = dustRepository.getLastAppliedEventId(walletAddress)
        val behind = checkpoint != null && runCatching {
            indexerClient.queryDustEventsDelta(
                fromId = checkpoint + 1,
                timeoutMs = DUST_FRESHNESS_PROBE_MS,
            ).eventCount > 0
        }.getOrElse {
            Log.w(TAG, "dust freshness probe failed; using cached state: ${it.message}")
            false
        }
        return if (behind) {
            Log.i(TAG, "dust state behind tip — delta refresh before balancing")
            dustSyncManager.refreshIncremental(progress)
        } else {
            dustSyncManager.ensureSynced(progress)
        }
    }

    private suspend fun tryBalance(
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)? = null,
    ): BalanceEnvelope? = balanceAgainst(ensureDustFresh(onProgress), provenTxHex, onProgress)

    /**
     * Balance against an already-synced dust state — **no chain sync of its own**.
     *
     * Split out from the sync so an error-115 retry can re-balance against the same
     * in-memory state with an updated skip-set (just re-selecting a different UTXO),
     * without re-streaming + re-replaying dust events on every attempt. Re-syncing is
     * only warranted for error 170 (stale root), handled by the caller.
     */
    private suspend fun balanceAgainst(
        dustState: DustLocalState,
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)? = null,
    ): BalanceEnvelope? {
        // Nullifiers the wallet recorded as spent but the synced state may still list
        // as available (the event stream lags our own fee spends). The native balancer
        // skips these so it can't re-select a consumed UTXO → node error 115.
        val recorded = spentDustNullifierStore.spentNullifiers(walletAddress)

        // Prune against the freshly-synced state: keep only recorded nullifiers whose
        // UTXO is still present (stream behind), drop those now gone (stream caught up
        // → spend confirmed). `currentNullifiers` returns null on a native error —
        // treat that as "unknown" and skip pruning, never as empty (which would
        // wrongly clear the whole skip-set and re-enable 115). The exclude set sent to
        // native is the pruned set, computed in-memory; we persist only when the prune
        // actually drops something, to avoid a DataStore write on every balance.
        val presentNullifiers = dustState.currentNullifiers(dustSeed)?.toSet()
        val excludeNullifiers: Set<String> = if (presentNullifiers != null) {
            val pruned = recorded intersect presentNullifiers
            if (pruned.size != recorded.size) {
                runCatching { spentDustNullifierStore.retainPresent(walletAddress, presentNullifiers) }
                    .onFailure { Log.w(TAG, "dust skip-set prune failed: ${it.message}") }
            }
            pruned
        } else {
            recorded
        }

        // Fast-fail: if the synced state has UTXOs but every one is excluded, all of
        // the wallet's dust is spent-but-unconfirmed — no balance can succeed. Surface
        // a clear error instead of a wasteful full re-sync that ends in an opaque
        // failure (the path `doBalance` takes on a null return).
        if (presentNullifiers != null &&
            presentNullifiers.isNotEmpty() &&
            (presentNullifiers - excludeNullifiers).isEmpty()
        ) {
            throw InsufficientDustException(
                "No spendable dust: all ${presentNullifiers.size} dust UTXO(s) are awaiting " +
                    "confirmation of a recent fee spend. Wait for the dust to reconfirm, then retry.",
            )
        }

        val blockInfo = indexerClient.getCurrentBlockWithParams()
        val ledgerParamsHex = blockInfo.ledgerParameters
            ?: throw IllegalStateException("Indexer returned no ledger parameters")

        onProgress?.invoke(BalanceProgress.ProvingDust)

        // Chain-anchored dust ctime (#287): the node validates the dust spend via
        // `dust.root_history.get(ctime)`, so ctime must resolve to the SAME dust root our
        // proof commits to — the block our local dust state synced to, NOT the chain tip.
        // On an active chain (PreProd) the tip races ahead of the replayed state between
        // sync and balance, so anchoring to the tip resolves a newer root than ours →
        // InvalidDustSpendProof (170) → futile delta re-sync → genesis rebuild. The send
        // path (TransactionSubmitter) already anchors to sync_time; the contract-call
        // balancer (deploy / contract calls) did not — this completes #287 for it.
        // Fall back to the indexer tip only if the state reports no sync_time.
        val dustSyncTimeMs = dustState.syncTimeMs()
        val chainTimeMs = if (dustSyncTimeMs > 0L) dustSyncTimeMs else blockInfo.timestamp

        val raw = TransactionBalancerNative.nativeBalanceProvenTransaction(
            provenTxHex = provenTxHex,
            dustStatePtr = dustState.getStatePointer(),
            seed = dustSeed,
            ledgerParamsHex = ledgerParamsHex,
            currentTimeMs = chainTimeMs,
            keysDir = provingKeysDir,
            networkId = networkId,
            excludeNullifiers = excludeNullifiers.joinToString(","),
        ) ?: return null
        return BalanceEnvelope.parse(raw)
    }

    private suspend fun forceFullSync() {
        runTrackedSync(SyncPhase.GenesisRebuild) { publish ->
            dustSyncManager.forceResync()
            dustSyncManager.ensureSynced(onSyncProgress = publish)
        }
    }

    /**
     * Force a fresh resync of both shielded and dust state.
     *
     * Unshielded NIGHT is already kept live by the subscription started at
     * SDK build time, so it's not re-pulled here. Shielded NIGHT and DUST
     * are local-replay-driven and can lag if the SDK's subscriptions had a
     * transient hiccup; calling [refresh] catches them up immediately.
     *
     * Use cases:
     *  - Between sequential transactions (avoid stale UTXO state for fees).
     *  - When the UI shows a "refresh" affordance and the user wants
     *    on-demand freshness rather than waiting on the natural cadence.
     *  - After [waitForFunding] returns, if you also need shielded NIGHT
     *    fresh (waitForFunding only refreshes dust).
     *
     * Errors in shielded resync don't abort the dust resync (and vice versa) —
     * partial freshness is better than no freshness.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        // Shielded resync + dust delta are the user-visible ShieldedRefresh work —
        // track them on syncStatus so the background notification covers this phase
        // too (not just the dust sync above it). Each leg keeps its own best-effort
        // try/catch (partial freshness beats none); the tracked block doesn't
        // rethrow, so syncStatus settles on UpToDate either way.
        // The WHOLE refresh — shielded resync, dust delta, AND the cloud backups —
        // is one tracked span. The backups are the tail of a refresh, so keeping
        // them inside keeps syncStatus on Syncing until refresh() truly finishes:
        // otherwise the notification/chip vanished the instant the sync legs
        // completed while the UI still showed "Refreshing balances…" through the
        // (network-bound) backup tail. Each leg keeps its own best-effort try/catch
        // (partial freshness beats none); the tracked block never rethrows, so
        // syncStatus settles on UpToDate either way.
        runTrackedSync(SyncPhase.ShieldedRefresh) { publish ->
            try {
                shieldedTracker.resync()
            } catch (e: Exception) {
                Log.w(TAG, "Shielded resync failed during refresh(): ${e.message}")
            }
            try {
                // Routine refresh = incremental delta on the persisted checkpoint,
                // NOT a genesis wipe. forceFullSync (forceResync) is only for
                // error-170 recovery where stale roots demand a clean rebuild.
                //
                // Under balanceMutex: refreshIncremental closes the shared
                // DustLocalState, which must not happen while a balance is mid-flight
                // (it holds the same state) — otherwise the balance resumes onto a
                // closed state and fails with "DustLocalState has been closed".
                balanceMutex.withLock {
                    dustSyncManager.refreshIncremental(onSyncProgress = publish)
                }
            } catch (e: CancellationException) {
                throw e // refresh cancelled (superseded / scope closed) — propagate, not a failure
            } catch (e: Exception) {
                Log.w(TAG, "Dust resync failed during refresh(): ${e.message}")
            }
            // Best-effort cloud backup of the freshly-synced checkpoint (no-op when
            // unconfigured; the coordinator's hash guard skips an unchanged blob).
            try {
                backupDustToCloud()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Dust cloud backup failed during refresh(): ${e.message}")
            }
            // Best-effort silent app-state backup of the host's CURRENT snapshot
            // (digest-guarded → no-op when unchanged). Using the host-registered
            // provider means we never clobber real state with an empty blob; absent
            // a provider there's simply nothing to back up.
            appStateProvider?.let { provider ->
                try {
                    backupAppStateToCloud(provider.invoke())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "App-state cloud backup failed during refresh(): ${e.message}")
                }
            }
        }
    }

    /**
     * Disable dust cloud backup and DELETE the cloud copy (#246 — true backup disable). Stops
     * future uploads ([dustBackupEnabled] = false) and clears the remote blob + local upload
     * digests via the coordinator. No-op when no coordinator is wired. The wallet itself is
     * unaffected — recovery is the passkey; the cloud blob is only a dust-sync-speed checkpoint.
     */
    suspend fun disableDustCloudBackup() = withContext(Dispatchers.IO) {
        dustBackupEnabled = false
        dustCloudBackup?.clear()
        updateDustBackup(CloudBackupStatus.Idle)
    }

    /**
     * Delete the cloud app-state blob (#246 — true backup disable). Clears the remote blob + local
     * upload digest via the coordinator; no-op when none is wired. Recovery is unaffected (the seed
     * is passkey-derived) — this only drops the convenience app-state copy.
     */
    suspend fun disableAppStateCloudBackup() = withContext(Dispatchers.IO) {
        appStateBackupEnabled = false // stop future uploads, else refresh() re-uploads the deleted blob
        appStateCloudBackup?.clear()
        updateAppDataBackup(CloudBackupStatus.Idle)
    }

    /**
     * Snapshot the current dust checkpoint and hand it to the cloud backup
     * coordinator (e.g. Google Drive) for cross-device recovery. No-op when no
     * coordinator is wired or there's no checkpoint yet. The coordinator
     * hash-guards redundant uploads, so this is cheap to call after every sync.
     */
    suspend fun backupDustToCloud() = withContext(Dispatchers.IO) {
        val backup = dustCloudBackup ?: return@withContext
        if (!dustBackupEnabled) {
            // User opted out — never upload. Reflect "off" on the lane.
            updateDustBackup(CloudBackupStatus.Idle)
            return@withContext
        }
        // Read state + cursor as ONE consistent snapshot. The old two-read form
        // (loadState then getLastAppliedEventId) could capture a state and a
        // cursor from different chain points if a sync wrote between the reads,
        // then upload that torn pair — poisoning every device that later restored
        // it (frontier ≠ cursor → NonLinearInsertion → genesis-resync loop). With
        // an atomic read, a clean local checkpoint always heals the cloud copy.
        val checkpoint = dustRepository.loadCheckpoint(walletAddress) ?: return@withContext
        val bytes = try {
            checkpoint.state.serialize()
        } finally {
            checkpoint.state.close()
        } ?: return@withContext
        updateDustBackup(CloudBackupStatus.Syncing)
        try {
            backup.upload(walletAddress, bytes, checkpoint.lastEventId)
            updateDustBackup(CloudBackupStatus.UpToDate(System.currentTimeMillis()))
        } catch (e: DriveConsentRequiredException) {
            // Surfaced (not silent) so the UI can offer "enable cloud backup".
            updateDustBackup(CloudBackupStatus.NeedsConsent)
        } catch (e: CancellationException) {
            throw e // backup cancelled (e.g. backgrounded mid-upload) — not a failure; don't flip to red Failed
        } catch (e: Exception) {
            if (isTransientNetworkError(e)) {
                // Best-effort backup, momentary connectivity/DNS blip — not a real
                // failure. Don't alarm the user (no red); the next sync retries.
                updateDustBackup(CloudBackupStatus.Offline)
                Log.i(TAG, "Dust cloud backup deferred — offline: ${e.message}")
            } else {
                updateDustBackup(CloudBackupStatus.Failed(e.message ?: "backup failed"))
                Log.w(TAG, "Dust cloud backup failed: ${e.message}")
            }
        }
    }

    /**
     * True when [t] (or a cause in its chain) is a transient connectivity/DNS
     * error — so a best-effort cloud backup that hit one is shown as [Offline]
     * (retry-when-online), not a red [Failed]. Covers UnknownHostException /
     * SocketTimeout / connection failures, plus message-sniffing for wrapped cases.
     */
    private fun isTransientNetworkError(t: Throwable?): Boolean {
        var e = t
        repeat(6) {
            val cur = e ?: return false
            if (cur is java.io.IOException) return true
            val m = cur.message?.lowercase().orEmpty()
            if ("unable to resolve host" in m || "no address associated" in m ||
                "failed to connect" in m || "network is unreachable" in m ||
                "unable to resolve" in m || "timeout" in m
            ) {
                return true
            }
            e = cur.cause
        }
        return false
    }

    /**
     * Back up host **app state** (≤2 KB; e.g. Kicks match witnesses) to the
     * cloud, encrypted under a seed-derived key — no passkey/biometric, so it's
     * safe to call automatically. No-op when no coordinator is wired. The
     * coordinator hash-guards redundant uploads, so this is cheap to call after
     * every meaningful state change. Never throws to the caller.
     *
     * Pass `null`/empty for a "sigil exists, no app state" sentinel.
     */
    suspend fun backupAppStateToCloud(appMetadata: ByteArray? = null) = withContext(Dispatchers.IO) {
        val backup = appStateCloudBackup ?: return@withContext
        if (!appStateBackupEnabled) {
            // User disabled backup — never upload (else a deleted blob silently re-uploads on the
            // next refresh). Reflect "off" on the lane. Mirrors backupDustToCloud's gate.
            updateAppDataBackup(CloudBackupStatus.Idle)
            return@withContext
        }
        updateAppDataBackup(CloudBackupStatus.Syncing)
        runCatching { backup.uploadAppState(appMetadata ?: ByteArray(0)) }
            .onSuccess { updateAppDataBackup(CloudBackupStatus.UpToDate(System.currentTimeMillis())) }
            .onFailure {
                if (it is CancellationException) throw it // cancelled, not failed — runCatching swallows it, so rethrow
                if (isTransientNetworkError(it)) {
                    updateAppDataBackup(CloudBackupStatus.Offline)
                    Log.i(TAG, "App-state cloud backup deferred — offline: ${it.message}")
                } else {
                    updateAppDataBackup(CloudBackupStatus.Failed(it.message ?: "backup failed"))
                    Log.w(TAG, "App-state cloud backup failed: ${it.message}")
                }
            }
        Unit
    }

    /**
     * Fetch the previously backed-up host app state, or null if none / no
     * coordinator / unavailable. Decryption is seed-derived (no biometric), so
     * a freshly-signed-in device recovers its app state silently.
     */
    suspend fun fetchAppState(): ByteArray? = withContext(Dispatchers.IO) {
        appStateCloudBackup?.let { runCatching { it.fetchAppState() }.getOrNull() }
    }

    /** Node error 170 = InvalidDustSpendProof. Submit throws it as a
     *  [TransactionRejected] (customErrorCode); a raw RPC error carries it in
     *  [NodeRpcError.data]. Both must be detected or the 170 recovery never fires. */
    internal fun isDustSpendProofError(e: NodeRpcException): Boolean = when (e) {
        is TransactionRejected -> e.customErrorCode == 170
        is NodeRpcError -> e.data?.contains("Custom error: 170") == true
        else -> false
    }

    fun close() {
        dustSyncManager.close()
        nodeRpcClient.close()
    }

    companion object {
        private const val TAG = "MidnightWallet"

        /**
         * Timeout for the pre-balance "are there dust events beyond my checkpoint?"
         * probe. Short on purpose: a behind state returns the first event well within
         * this; a caught-up state just waits it out and balances against the cache.
         */
        private const val DUST_FRESHNESS_PROBE_MS = 1_000L

        /**
         * Default cap for [waitForFunding]. 5 minutes is the same envelope used
         * by [com.midnight.kicks.MatchManager.DEFAULT_OPPONENT_WAIT_MS] — long
         * enough for a faucet / manual transfer to clear, short enough that a
         * caller doesn't hang forever.
         */
        private const val DEFAULT_FUNDING_TIMEOUT_MS = 5L * 60L * 1_000L
    }
}

/**
 * Snapshot of a wallet's full balance state across both pools.
 *
 * **Pool semantics:**
 *  - Unshielded NIGHT lives in UTXOs on the public ledger; this is what
 *    external transfers ("fund this address") land in.
 *  - Shielded NIGHT lives in Zswap coins on the private ledger; the wallet
 *    moves NIGHT here for transactions whose amounts/recipients shouldn't
 *    be public.
 *  - DUST is a separate fee token, generated by the chain at a rate tied
 *    to the wallet's registered NIGHT. Not held in either pool's
 *    coin/UTXO graph; tracked in the ledger's dust state.
 *
 * **Why DUST has no "shielded" half:** Midnight's protocol generates DUST
 * against a registered NIGHT key directly. It's never wrapped in a Zswap
 * coin and never appears as a shielded balance — by design, since DUST
 * is the *fee* substrate, not a transferrable shielded asset.
 *
 * @property unshieldedNight Sum of unspent NIGHT UTXOs at this wallet's
 *   Bech32m address. u128 base units.
 * @property shieldedNight Sum of shielded NIGHT coins decryptable by this
 *   wallet's zswap key. u128 base units. Zero until the SDK's shielded
 *   subscription has completed its first replay (a few hundred ms on
 *   localnet, a few seconds on PREPROD).
 * @property dust Wallet's locally-replayed DUST state — what the SDK would
 *   spend on the next fee. u128 base units.
 * @property dustRegistered Best-effort signal: true iff the wallet currently
 *   holds spendable dust. A fresh wallet that hasn't run
 *   [MidnightSdk.registerForDustGeneration] reports `false` even with NIGHT
 *   funded, because the chain doesn't release spendable dust to an
 *   unregistered key.
 */
data class WalletBalance(
    val unshieldedNight: BigInteger,
    val shieldedNight: BigInteger,
    val dust: BigInteger,
    val dustRegistered: Boolean,
) {
    /** Sum of both NIGHT pools — what the wallet "has" in NIGHT terms. */
    val totalNight: BigInteger get() = unshieldedNight + shieldedNight

    /** True iff any shielded NIGHT is present. UIs use this to gate the privacy badge. */
    val hasShielded: Boolean get() = shieldedNight > BigInteger.ZERO
}
