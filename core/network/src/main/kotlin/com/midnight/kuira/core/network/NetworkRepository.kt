package com.midnight.kuira.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Repository for managing the selected Midnight network.
 *
 * **Persistence:**
 * Uses DataStore to persist the selected network across app restarts.
 *
 * **MVP Limitation:**
 * Network change requires app restart because Hilt singletons (IndexerClient,
 * NodeRpcClient, ProofServerClient) are created at app startup with URLs
 * based on the selected network.
 *
 * **Usage:**
 * ```kotlin
 * // Observe network changes
 * networkRepository.selectedNetworkFlow.collect { network ->
 *     updateUI(network)
 * }
 *
 * // Change network (requires restart)
 * networkRepository.setSelectedNetwork(MidnightNetwork.PREVIEW)
 * ```
 */
class NetworkRepository(
    private val context: Context
) {
    private val dataStore: DataStore<Preferences> = context.networkDataStore

    /**
     * Flow of the currently selected network.
     * Emits the stored network or DEFAULT if not set.
     */
    val selectedNetworkFlow: Flow<MidnightNetwork> = dataStore.data.map { preferences ->
        val networkName = preferences[SELECTED_NETWORK_KEY]
        if (networkName != null) {
            MidnightNetwork.fromName(networkName)
        } else {
            MidnightNetwork.DEFAULT
        }
    }

    /**
     * Get the currently selected network synchronously.
     *
     * **Note:** This blocks the calling thread. Use sparingly.
     * Primarily intended for Hilt module initialization at app startup.
     */
    fun getSelectedNetworkBlocking(): MidnightNetwork {
        return runBlocking {
            selectedNetworkFlow.first()
        }
    }

    /**
     * Get the NetworkConfig for the currently selected network synchronously.
     *
     * **Note:** This blocks the calling thread. Use sparingly.
     * Primarily intended for Hilt module initialization at app startup.
     */
    fun getNetworkConfigBlocking(): NetworkConfig {
        return NetworkConfig.forNetwork(getSelectedNetworkBlocking())
    }

    /**
     * Set the selected network.
     *
     * **Important:** This persists the selection but doesn't update the running app.
     * The app must be restarted for the new network to take effect.
     *
     * @param network The network to select
     */
    suspend fun setSelectedNetwork(network: MidnightNetwork) {
        dataStore.edit { preferences ->
            preferences[SELECTED_NETWORK_KEY] = network.name
        }
    }

    companion object {
        private val SELECTED_NETWORK_KEY = stringPreferencesKey("selected_network")
    }
}

/**
 * DataStore extension property for network preferences.
 * Stored in "network_preferences" file.
 */
private val Context.networkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "network_preferences"
)
