package com.midnight.kuira.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.midnight.kuira.core.compact.proving.ProvingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.proofServerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "proof_server_preferences",
)

/**
 * Persists the proving mode and optional remote proof server URL.
 *
 * Architecture:
 * - [ProvingMode.LOCAL] → on-device proving, no URL needed (default)
 * - [ProvingMode.REMOTE] → requires [remoteUrl] to be set
 *
 * NOT cleared on wallet wipe — this is an app preference.
 */
@Singleton
class ProofServerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val modeKey = stringPreferencesKey("proving_mode")
    private val urlKey = stringPreferencesKey("proof_server_url")

    val provingMode: Flow<ProvingMode> = context.proofServerDataStore.data
        .map { prefs ->
            prefs[modeKey]?.let { ProvingMode.valueOf(it) } ?: ProvingMode.DEFAULT
        }

    val remoteUrl: Flow<String?> = context.proofServerDataStore.data
        .map { prefs -> prefs[urlKey] }

    suspend fun setProvingMode(mode: ProvingMode) {
        context.proofServerDataStore.edit { prefs ->
            prefs[modeKey] = mode.name
        }
    }

    suspend fun setRemoteUrl(url: String?) {
        context.proofServerDataStore.edit { prefs ->
            if (url.isNullOrBlank()) {
                prefs.remove(urlKey)
            } else {
                prefs[urlKey] = url
            }
        }
    }
}
