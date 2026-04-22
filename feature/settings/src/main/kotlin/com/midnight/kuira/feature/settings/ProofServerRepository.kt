package com.midnight.kuira.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.proofServerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "proof_server_preferences",
)

/**
 * Persists the user-configurable proof server URL. When null,
 * the app uses the default URL from [NetworkConfig]. NOT cleared
 * on wallet wipe — proof server is an app preference.
 *
 * URL changes take effect on the next proving operation (the
 * reactive network architecture picks up the new URL via the Flow).
 */
@Singleton
class ProofServerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("proof_server_url")

    /** Emits the custom URL or null (= use network default). */
    val customUrl: Flow<String?> = context.proofServerDataStore.data
        .map { prefs -> prefs[key] }

    suspend fun setCustomUrl(url: String?) {
        context.proofServerDataStore.edit { prefs ->
            if (url.isNullOrBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = url
            }
        }
    }
}
