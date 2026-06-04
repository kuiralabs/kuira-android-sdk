package com.midnight.kuira.core.indexer.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.midnight.kuira.core.indexer.di.DustStateDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable, per-address record of dust nullifiers the wallet has **spent and
 * submitted** but whose spend the indexer's `dustLedgerEvents` stream has not yet
 * reflected.
 *
 * **Why this exists.** The dust event stream does not reliably/promptly include the
 * wallet's *own* dust **fee** spends (see `docs/indexer-dust-fee-spend-question.md`).
 * So right after a fee-paying move, the synced dust state still lists the spent
 * UTXO as available, and the next move re-selects it — the node then rejects the
 * transaction with `Custom error: 115` ("UTXO already spent"). The balancer must be
 * able to skip nullifiers the wallet already spent, independent of the stream.
 *
 * **Lifecycle.** A nullifier is recorded on a successful submit and pruned via
 * [retainPresent] on the next balance, once the synced dust state no longer holds its
 * UTXO (the stream has reflected the spend). The set is therefore bounded to the
 * wallet's in-flight fee spends — submitted but not yet reflected in the stream.
 * Excluding a nullifier whose UTXO is already gone would be a harmless no-op anyway,
 * so the set is always *correct*; pruning just keeps it small.
 *
 * Nullifiers are stored lowercase hex.
 */
@Singleton
class SpentDustNullifierStore @Inject constructor(
    @DustStateDataStore private val dataStore: DataStore<Preferences>,
) {
    private fun key(address: String) = stringSetPreferencesKey("dust_spent_nullifiers_$address")

    /** Nullifiers (lowercase hex) the wallet has spent but the stream hasn't confirmed gone. */
    suspend fun spentNullifiers(address: String): Set<String> =
        dataStore.data.first()[key(address)].orEmpty()

    /** Record a nullifier the wallet just spent + submitted, so it isn't re-selected. */
    suspend fun recordSpent(address: String, nullifierHex: String) {
        val n = nullifierHex.lowercase()
        dataStore.edit { prefs ->
            prefs[key(address)] = prefs[key(address)].orEmpty() + n
        }
    }

    /**
     * Prune recorded nullifiers the chain has confirmed spent. [presentNullifiers]
     * is the set of nullifiers (lowercase hex) the freshly-synced dust state still
     * holds. A recorded nullifier is kept only while its UTXO is still present
     * (stream behind); once it's gone (stream caught up), it's dropped.
     */
    suspend fun retainPresent(address: String, presentNullifiers: Set<String>) {
        val present = presentNullifiers.map { it.lowercase() }.toSet()
        dataStore.edit { prefs ->
            val current = prefs[key(address)] ?: return@edit
            val kept = current intersect present
            if (kept.isEmpty()) prefs.remove(key(address)) else prefs[key(address)] = kept
        }
    }

    /** Drop all tracking for an address (e.g. wallet reset). */
    suspend fun clear(address: String) {
        dataStore.edit { prefs -> prefs.remove(key(address)) }
    }
}
