package com.midnight.kuira.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.devModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dev_mode_preferences",
)

/**
 * Persists the developer-mode toggle. Survives app restarts
 * (DataStore-backed). NOT cleared on wallet wipe — dev mode is
 * an app preference, not wallet data.
 */
@Singleton
class DevModeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = booleanPreferencesKey("dev_mode_unlocked")

    val isUnlocked: Flow<Boolean> = context.devModeDataStore.data
        .map { prefs -> prefs[key] ?: false }

    suspend fun setUnlocked(unlocked: Boolean) {
        context.devModeDataStore.edit { prefs ->
            prefs[key] = unlocked
        }
    }
}
