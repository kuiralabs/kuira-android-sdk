package com.midnight.kuira.core.indexer.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.midnight.kuira.core.crypto.shielded.ZswapLocalState
import com.midnight.kuira.core.indexer.di.ShieldedStateDataStore
import kotlinx.coroutines.flow.first
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for shielded (zswap) wallet operations.
 *
 * Mirrors [DustRepository] pattern:
 * - Sync zswap events from blockchain via indexer
 * - Persist ZswapLocalState to DataStore
 * - Query shielded balances from state
 *
 * **Architecture:**
 * ```
 * ShieldedRepository
 *  ├─ ZswapLocalState (FFI) — Source of truth, in-memory Rust state
 *  ├─ IndexerClient — Event subscription
 *  └─ DataStore (Persistence) — Serialized state + last event ID
 * ```
 */
@Singleton
class ShieldedRepository @Inject constructor(
    @ShieldedStateDataStore private val dataStore: DataStore<Preferences>,
    private val indexerClient: com.midnight.kuira.core.indexer.api.IndexerClient
) {
    companion object {
        private const val TAG = "ShieldedRepository"
        private fun stateKey(address: String) = stringPreferencesKey("zswap_state_$address")
        private fun eventIdKey(address: String) = longPreferencesKey("zswap_last_event_$address")
    }

    /**
     * Sync shielded state from blockchain.
     *
     * Queries all zswap events, replays into ZswapLocalState, persists.
     * For now: full replay from genesis (incremental sync in Phase 2).
     *
     * @param address Wallet address (for DataStore key)
     * @param zswapSeed 32-byte zswap seed (derived at m/44'/2400'/0'/3/0)
     * @return true if sync succeeded and coins found
     */
    suspend fun syncFromBlockchain(address: String, zswapSeed: ByteArray): Boolean {
        Log.d(TAG, "Syncing shielded state for $address")

        try {
            val eventsHex = indexerClient.queryZswapEvents()

            if (eventsHex.isEmpty()) {
                Log.d(TAG, "No zswap events found")
                return false
            }

            Log.d(TAG, "Retrieved ${eventsHex.length / 2} bytes of zswap events")

            val initialState = ZswapLocalState.create()
            if (initialState == null) {
                Log.e(TAG, "Failed to create ZswapLocalState")
                return false
            }

            try {
                val restoredState = initialState.replayEvents(zswapSeed, eventsHex)
                if (restoredState == null) {
                    Log.e(TAG, "Failed to replay zswap events")
                    return false
                }

                try {
                    val coinCount = restoredState.getCoinCount()
                    Log.d(TAG, "Replay complete: $coinCount shielded coins")

                    saveState(address, restoredState)

                    return coinCount > 0
                } finally {
                    restoredState.close()
                }
            } finally {
                initialState.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync shielded state", e)
            return false
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

    suspend fun deleteState(address: String) {
        dataStore.edit { prefs ->
            prefs.remove(stateKey(address))
            prefs.remove(eventIdKey(address))
        }
        Log.d(TAG, "Deleted shielded state for $address")
    }
}
