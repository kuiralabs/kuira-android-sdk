package com.midnight.kuira.core.indexer.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.midnight.kuira.core.crypto.shielded.ZswapLocalState
import com.midnight.kuira.core.indexer.api.GraphQLQueries
import com.midnight.kuira.core.indexer.di.ShieldedStateDataStore
import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import com.midnight.kuira.core.indexer.websocket.GraphQLWebSocketClient
import com.midnight.kuira.core.network.NetworkConfig
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for shielded (zswap) wallet operations.
 *
 * Uses its OWN WebSocket client, independent from the shared IndexerClient.
 * This prevents the unshielded SubscriptionManager from killing the shielded
 * subscription when it resets the shared WebSocket connection.
 */
@Singleton
open class ShieldedRepository @Inject constructor(
    @ShieldedStateDataStore private val dataStore: DataStore<Preferences>,
    private val indexerClient: com.midnight.kuira.core.indexer.api.IndexerClient,
    private val networkConfig: NetworkConfig
) {
    companion object {
        private const val TAG = "ShieldedRepository"

        /**
         * Timeout for the cheap tip probe (read the chain's zswap `maxId` from the first event of
         * a from-genesis subscription) that decides at-tip / delta / full. If it doesn't resolve
         * in time the tip is unknown and we fall back to a safe full genesis sync.
         */
        private const val TIP_PROBE_TIMEOUT_MS = 30_000L

        private fun stateKey(address: String) = stringPreferencesKey("zswap_state_$address")
        private fun eventIdKey(address: String) = longPreferencesKey("zswap_last_event_$address")
    }

    // Each subscription gets a fresh WebSocket client to avoid state corruption
    private val wsEndpoint = networkConfig.indexerBaseUrl
        .replace("http://", "ws://")
        .replace("https://", "wss://") + "/graphql/ws"

    // Returns the engine so the caller can close it on teardown — the
    // GraphQLWebSocketClient does NOT own the injected httpClient's lifecycle, so
    // without an explicit httpClient.close() the OkHttp engine (dispatcher threads
    // + connection pool) leaks one instance per sync.
    private fun createFreshHttpClient(): HttpClient = HttpClient(OkHttp) {
        install(io.ktor.client.plugins.websocket.WebSockets)
        // Keep the shielded-events subscription alive. Ktor's plugin-level
        // pingInterval is a no-op on the OkHttp engine (KTOR-4752), so set
        // OkHttp's own ping: without it this socket is dropped at the ~60s
        // idle timeout and flaps (subscribe → error → reconnect) every minute.
        engine { config { pingInterval(20, TimeUnit.SECONDS) } }
    }

    /**
     * Sync shielded (zswap) state from blockchain (#279/#1 — checkpoint + delta).
     *
     * Previously this re-streamed and re-replayed EVERY zswap event from genesis on every
     * refresh ("Phase 2" TODO). Now it resumes from a saved checkpoint: probe the chain's
     * zswap tip (`maxId`) and route via [shieldedSyncPlan] —
     *  - **AT_TIP** (tip == cursor): nothing new — keep the persisted state, skip the network.
     *  - **DELTA** (tip > cursor): replay only events after the cursor ON TOP of the restored
     *    checkpoint (proven byte-identical to a genesis replay by `ZswapCheckpointResumeTest`).
     *  - **FULL** (no checkpoint / reorg / probe timeout): replay from genesis, as before.
     * The checkpoint (state + cursor) is written atomically; a half-written pair reads as absent
     * (forces a clean full sync), mirroring the dust side's `NonLinearInsertion` safety net.
     *
     * @param address Wallet address (for DataStore key)
     * @param zswapSeed 32-byte zswap seed (derived at m/44'/2400'/0'/3/0)
     * @return true if sync succeeded and coins were found
     */
    suspend fun syncFromBlockchain(address: String, zswapSeed: ByteArray): Boolean {
        Log.d(TAG, "Syncing shielded state for $address")
        try {
            // Only probe the tip when we have a checkpoint to compare against — a first sync
            // goes straight to full, where the genesis stream surfaces the tip anyway.
            val checkpoint = loadCheckpoint(address)
            val tip = if (checkpoint != null) {
                withTimeoutOrNull(TIP_PROBE_TIMEOUT_MS) {
                    subscribeToZswapEvents(fromId = null).firstOrNull()?.maxId ?: -1L
                }
            } else {
                null
            }

            return when (shieldedSyncPlan(tip = tip, cursor = checkpoint?.cursor)) {
                ShieldedSyncPlan.AT_TIP -> {
                    // Caught up: the persisted state already covers every zswap event, and (unlike
                    // dust) the shielded balance isn't time-based, so there's nothing to recompute.
                    val coinCount = checkpoint!!.state.getCoinCount()
                    checkpoint.state.close()
                    Log.d(TAG, "At tip (event ${checkpoint.cursor}) — shielded state current, skipping re-replay")
                    coinCount > 0
                }
                ShieldedSyncPlan.DELTA -> deltaSync(address, zswapSeed, checkpoint!!)
                ShieldedSyncPlan.FULL -> {
                    checkpoint?.state?.close()
                    fullSync(address, zswapSeed)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync shielded state", e)
            return false
        }
    }

    /** Replay every zswap event from genesis into a fresh state and checkpoint it. */
    private suspend fun fullSync(address: String, zswapSeed: ByteArray): Boolean {
        val stream = streamZswapEvents(fromId = null)
        if (stream.count == 0) {
            Log.d(TAG, "No zswap events found")
            return false
        }
        Log.d(TAG, "Full sync from genesis: ${stream.count} events (${stream.eventsHex.length / 2} bytes)")
        val base = ZswapLocalState.create() ?: run {
            Log.e(TAG, "Failed to create ZswapLocalState")
            return false
        }
        val state = try {
            base.replayEvents(zswapSeed, stream.eventsHex)
        } finally {
            base.close()
        }
        if (state == null) {
            Log.e(TAG, "Failed to replay zswap events")
            return false
        }
        return persistAndReport(address, state, stream.lastEventId, "Full sync")
    }

    /**
     * Replay only the events after the checkpoint cursor ON TOP of the restored checkpoint state.
     * Falls back to a full genesis rebuild if the delta replay is rejected (a misaligned resume —
     * the same `NonLinearInsertion` safety net the dust side relies on).
     */
    private suspend fun deltaSync(address: String, zswapSeed: ByteArray, checkpoint: ZswapCheckpoint): Boolean {
        val stream = streamZswapEvents(fromId = checkpoint.cursor + 1)
        if (stream.count == 0) {
            // Nothing new arrived between the probe and the stream — keep the checkpoint.
            val coinCount = checkpoint.state.getCoinCount()
            checkpoint.state.close()
            Log.d(TAG, "Delta sync: no new events past ${checkpoint.cursor}, keeping checkpoint")
            return coinCount > 0
        }
        Log.d(TAG, "Delta sync: resuming from event ${checkpoint.cursor}, replaying ${stream.count} events")
        val newState = try {
            checkpoint.state.replayEvents(zswapSeed, stream.eventsHex)
        } finally {
            checkpoint.state.close()
        }
        if (newState == null) {
            Log.w(TAG, "Delta replay rejected (misaligned resume) — rebuilding from genesis")
            deleteState(address)
            return fullSync(address, zswapSeed)
        }
        return persistAndReport(address, newState, stream.lastEventId, "Delta sync")
    }

    /** Checkpoint [state] at [cursor] (best-effort) and report has-coins. Closes [state]. */
    private suspend fun persistAndReport(address: String, state: ZswapLocalState, cursor: Long, label: String): Boolean {
        try {
            val coinCount = state.getCoinCount()
            saveCheckpoint(address, state, cursor)
            Log.d(TAG, "$label complete: $coinCount shielded coins, cursor=$cursor")
            return coinCount > 0
        } finally {
            state.close()
        }
    }

    /**
     * Get current shielded balances.
     *
     * @param address Wallet address
     * @param zswapSeed 32-byte zswap seed (needed to load state)
     * @return Map of token type hex → balance, or empty if no state
     */
    suspend fun getBalances(address: String): Map<String, BigInteger> {
        val state = loadState(address) ?: return emptyMap()
        try {
            return state.getBalances()
        } finally {
            state.close()
        }
    }

    /** Get number of shielded coins. */
    suspend fun getCoinCount(address: String): Int {
        val state = loadState(address) ?: return 0
        try {
            return state.getCoinCount()
        } finally {
            state.close()
        }
    }

    /**
     * Subscribe to zswap events using dedicated WebSocket (not shared with unshielded).
     *
     * `open` so tests can substitute a deterministic event source (the production body opens a
     * real WebSocket); see `ShieldedRepositorySyncPlanInstrumentedTest`.
     */
    open fun subscribeToZswapEvents(fromId: Long? = null): Flow<RawLedgerEvent> {
        val variables = buildMap<String, Any> {
            if (fromId != null) {
                put("id", fromId)
            }
        }

        return flow {
            // Each subscription gets its own WebSocket client (per the class-level
            // comment about isolation from the shared IndexerClient). The
            // try/finally is critical: when the collector is cancelled — e.g.
            // ShieldedBalanceTracker.close() during an SDK rebuild on network
            // switch — we MUST close this client. Without it the underlying
            // OkHttp dispatcher keeps retrying connections from the dead SDK
            // and floods logcat with `ConnectException` from the previous
            // network's host. Symptom: switching networks leaves "phantom"
            // shielded subscriptions hammering the old endpoint forever.
            val httpClient = createFreshHttpClient()
            val client = GraphQLWebSocketClient(url = wsEndpoint, httpClient = httpClient)
            try {
                client.connect()
                client.subscribe(GraphQLQueries.SUBSCRIBE_ZSWAP_LEDGER_EVENTS, variables)
                    .buffer(Channel.UNLIMITED)
                    .collect { jsonElement ->
                        val dataElement = jsonElement.jsonObject["data"]
                        if (dataElement == null || dataElement is kotlinx.serialization.json.JsonNull) {
                            return@collect
                        }

                        val eventObj = dataElement.jsonObject["zswapLedgerEvents"]?.jsonObject
                            ?: return@collect

                        val id = eventObj["id"]?.jsonPrimitive?.long ?: return@collect
                        val rawHex = eventObj["raw"]?.jsonPrimitive?.content ?: return@collect
                        val maxId = eventObj["maxId"]?.jsonPrimitive?.long ?: return@collect

                        emit(RawLedgerEvent(id = id, rawHex = rawHex, maxId = maxId))
                    }
            } finally {
                // Teardown runs under cancellation (transformWhile stops collection
                // on id >= maxId, or ShieldedBalanceTracker.close() on a network
                // switch). client.close() and httpClient.close() MUST actually run —
                // a suspend call in a cancelled coroutine throws at the first
                // suspension point, so without NonCancellable the socket, its
                // keepalive + message-loop scope, AND the OkHttp engine all leak,
                // accumulating one live client per sync. Both wrapped so a cleanup
                // fault never masks the body's real exception, and httpClient is
                // closed even if client.close() threw.
                withContext(NonCancellable) {
                    runCatching { client.close() }
                    runCatching { httpClient.close() }
                }
            }
        }
    }

    /** Result of streaming zswap events: the concatenated hex, the last applied event id, and a count. */
    private data class ZswapStreamResult(val eventsHex: String, val lastEventId: Long, val count: Int)

    /**
     * Stream zswap events from [fromId] (null = genesis) until caught up to the chain tip, in id
     * order. Returns the concatenated raw hex (the form [ZswapLocalState.replayEvents] expects)
     * plus the highest applied event id — the cursor to checkpoint. Same WebSocket pattern as
     * `IndexerClientImpl.queryDustEvents`.
     */
    private suspend fun streamZswapEvents(fromId: Long?): ZswapStreamResult {
        val events = subscribeToZswapEvents(fromId = fromId)
            .transformWhile { event ->
                emit(event)
                event.id < event.maxId
            }
            .toList()

        if (events.isEmpty()) {
            return ZswapStreamResult(eventsHex = "", lastEventId = (fromId ?: 0L) - 1, count = 0)
        }
        val sorted = events.sortedBy { it.id }
        return ZswapStreamResult(
            eventsHex = sorted.joinToString("") { it.rawHex },
            lastEventId = sorted.last().id,
            count = sorted.size,
        )
    }

    /** Check if cached shielded state exists. */
    suspend fun hasCachedState(address: String): Boolean {
        val key = stateKey(address)
        return dataStore.data.first()[key] != null
    }

    // ── Persistence ──

    suspend fun loadState(address: String): ZswapLocalState? {
        val key = stateKey(address)
        val hexString = dataStore.data.first()[key] ?: return null
        return ZswapLocalState.deserialize(hexString)
    }

    suspend fun saveState(address: String, state: ZswapLocalState) {
        val serialized = state.serialize() ?: return
        val key = stateKey(address)
        dataStore.edit { prefs ->
            prefs[key] = serialized
        }
    }

    /**
     * Persist the checkpoint (serialized state + resume cursor) ATOMICALLY — both keys in one
     * edit — so a reader can never observe a torn pair (an old state with a new cursor would make
     * the next delta resume at the wrong frontier; see [loadCheckpoint]).
     */
    suspend fun saveCheckpoint(address: String, state: ZswapLocalState, cursor: Long) {
        val serialized = state.serialize() ?: return
        dataStore.edit { prefs ->
            prefs[stateKey(address)] = serialized
            prefs[eventIdKey(address)] = cursor
        }
    }

    /**
     * Load the checkpoint (state + cursor) as ONE consistent snapshot — both keys from a single
     * DataStore emission, so the pair can't be torn across a concurrent write. Returns null if
     * EITHER half is missing or the state fails to deserialize (a legacy state saved by [saveState]
     * has no cursor → reads as absent → caller does a clean full sync that establishes the cursor).
     * The returned [ZswapCheckpoint.state] is owned by the caller and must be closed.
     */
    suspend fun loadCheckpoint(address: String): ZswapCheckpoint? {
        val prefs = dataStore.data.first()
        val hexString = prefs[stateKey(address)] ?: return null
        val cursor = prefs[eventIdKey(address)] ?: return null
        val state = ZswapLocalState.deserialize(hexString) ?: return null
        return ZswapCheckpoint(state, cursor)
    }

    suspend fun deleteState(address: String) {
        dataStore.edit { prefs ->
            prefs.remove(stateKey(address))
            prefs.remove(eventIdKey(address))
        }
        Log.d(TAG, "Deleted shielded state for $address")
    }
}

/** A loaded shielded checkpoint: the restored state plus its resume cursor (last applied event id). */
class ZswapCheckpoint(val state: ZswapLocalState, val cursor: Long)

/** What a shielded sync should do, given the chain zswap tip vs our checkpoint cursor. */
internal enum class ShieldedSyncPlan {
    /** No checkpoint, unknown tip (probe timeout), or chain reset below us → full genesis replay. */
    FULL,

    /** Chain tip equals our cursor — already caught up; keep the checkpoint, skip the network. */
    AT_TIP,

    /** Chain tip is ahead — replay only the events after the cursor onto the checkpoint. */
    DELTA,
}

/**
 * Pure decision seam (unit-tested) for [ShieldedRepository.syncFromBlockchain].
 *
 * [tip] is the chain's current highest zswap event id (the `maxId` probe); null means no
 * checkpoint to probe against, or the probe timed out. [cursor] is our checkpoint's last applied
 * event id; null means no checkpoint.
 *
 *  - no [cursor] → [ShieldedSyncPlan.FULL] (first sync).
 *  - [tip] null → [ShieldedSyncPlan.FULL] (unknown tip → safe full).
 *  - `tip < cursor` → [ShieldedSyncPlan.FULL] (chain reset below us → rebuild from genesis).
 *  - `tip == cursor` → [ShieldedSyncPlan.AT_TIP] (caught up — skip the genesis re-replay).
 *  - `tip > cursor` → [ShieldedSyncPlan.DELTA] (replay only the new events).
 */
internal fun shieldedSyncPlan(tip: Long?, cursor: Long?): ShieldedSyncPlan = when {
    cursor == null -> ShieldedSyncPlan.FULL
    tip == null -> ShieldedSyncPlan.FULL
    tip < cursor -> ShieldedSyncPlan.FULL
    tip == cursor -> ShieldedSyncPlan.AT_TIP
    else -> ShieldedSyncPlan.DELTA
}
