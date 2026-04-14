// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import android.content.Context
import com.midnight.kuira.core.network.MidnightNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Cached wallet addresses for one Midnight network.
 *
 * @property unshieldedAddress Bech32m-encoded unshielded public address
 *   (e.g., `mn_addr_undeployed1...`). Derived from the first NIGHT_EXTERNAL key.
 * @property shieldedAddress Bech32m-encoded shielded address
 *   (e.g., `mn_shield-addr_undeployed1...`). Derived from the first ZSWAP key +
 *   encryption public key. Null if shielded key derivation failed (ShieldedKeyDeriver
 *   is a native FFI call that can fail in unit-test environments without the
 *   native library loaded).
 */
data class WalletAddresses(
    val unshieldedAddress: String,
    val shieldedAddress: String?,
)

/**
 * Persistent, **per-network** store for the wallet's public addresses.
 *
 * The same BIP-39 seed produces different bech32m-encoded addresses on
 * different Midnight networks, because the network prefix (`mn_addr_preprod`
 * vs `mn_addr_undeployed` vs `mn_addr_preview`) is baked into the encoding.
 * This cache keeps one entry per network so the wallet can display the
 * correct address for whatever network the user has selected, without
 * needing to re-derive from the seed (which requires biometric auth).
 *
 * **Storage:**
 * JSON file at `<app filesDir>/kuira_wallet_address.json`. Layout:
 * ```json
 * {
 *   "UNDEPLOYED": {"unshielded": "...", "shielded": "..."},
 *   "PREPROD":    {"unshielded": "...", "shielded": "..."}
 * }
 * ```
 * Missing networks are absent from the map. Legacy single-network files
 * (from before per-network storage landed) are migrated on first read.
 *
 * **When is a network's entry populated?**
 * - At **onboarding** (new wallet or restore): the current network's entry
 *   is written after `storeSeed` succeeds.
 * - At **network switch**: if the target network isn't in the cache, the
 *   `MainActivity` switch flow prompts biometric, loads the seed, derives
 *   fresh addresses, saves them, then restarts the app. The cache is never
 *   populated without the user's seed being unlocked.
 *
 * **Security note:** addresses are public data. No encryption here — unlike
 * the seed, losing this file just means a one-time re-derivation (which
 * does require biometric). Storage lives in the same filesDir as the seed,
 * which is excluded from Auto Backup via `backup_rules.xml`.
 *
 * **Threading:** all I/O runs on `Dispatchers.IO`.
 */
class WalletAddressCache(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    /**
     * Persist addresses for [network]. Other networks' entries are
     * preserved — this call only updates (or inserts) the [network] entry.
     */
    suspend fun save(network: MidnightNetwork, addresses: WalletAddresses) =
        withContext(Dispatchers.IO) {
            val current = readAllOrEmpty()
            current.put(network.name, addressesToJson(addresses))
            file.writeText(current.toString())
        }

    /**
     * Load cached addresses for [network]. Returns null if this network has
     * no cached entry yet (e.g., onboarding ran on a different network and
     * no switch has happened).
     */
    suspend fun load(network: MidnightNetwork): WalletAddresses? =
        withContext(Dispatchers.IO) {
            val root = readAllOrEmpty()
            val networkEntry = root.optJSONObject(network.name) ?: return@withContext null
            runCatching { addressesFromJson(networkEntry) }.getOrNull()
        }

    /**
     * True if at least one network has cached addresses.
     *
     * Used by the Wallet Gate to distinguish "wallet doesn't exist" from
     * "wallet exists but hasn't derived addresses for the current network".
     */
    suspend fun hasAny(): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext false
        val root = readAllOrEmpty()
        root.length() > 0
    }

    /**
     * Delete all cached addresses across every network. Called during wallet
     * reset / wipe.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        if (file.exists()) file.delete()
    }

    /**
     * Read the root JSON object, migrating legacy single-network files into
     * the per-network map format if needed. Returns an empty object when the
     * file doesn't exist or is unreadable.
     *
     * MUST be called from a Dispatchers.IO context — callers are already
     * inside `withContext(Dispatchers.IO)`.
     */
    private fun readAllOrEmpty(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try {
            val raw = JSONObject(file.readText())
            migrateLegacyIfNeeded(raw)
        } catch (e: IOException) {
            JSONObject()
        } catch (e: JSONException) {
            JSONObject()
        }
    }

    /**
     * Legacy format was a single `{unshielded, shielded}` pair at the root,
     * no network key. Infer the network from the address prefix and lift the
     * entry under that network's key. Returns the (possibly-transformed)
     * JSON.
     */
    private fun migrateLegacyIfNeeded(raw: JSONObject): JSONObject {
        val legacyUnshielded = raw.optString(LEGACY_KEY_UNSHIELDED).takeIf { it.isNotBlank() }
            ?: return raw
        val inferredNetwork = inferNetworkFromAddress(legacyUnshielded) ?: return JSONObject()

        val legacyShielded = raw.optString(LEGACY_KEY_SHIELDED).takeIf { it.isNotBlank() }
        val addresses = WalletAddresses(
            unshieldedAddress = legacyUnshielded,
            shieldedAddress = legacyShielded,
        )
        val migrated = JSONObject().apply {
            put(inferredNetwork.name, addressesToJson(addresses))
        }
        // Persist the migrated layout so we don't pay the migration cost
        // on every load. Best-effort — a write failure here just means the
        // migration runs again next time, which is harmless.
        runCatching { file.writeText(migrated.toString()) }
        return migrated
    }

    private fun inferNetworkFromAddress(address: String): MidnightNetwork? =
        MidnightNetwork.entries.firstOrNull { network ->
            address.startsWith(network.addressPrefix + "1")
        }

    companion object {
        internal const val FILE_NAME = "kuira_wallet_address.json"
        private const val KEY_UNSHIELDED = "unshielded"
        private const val KEY_SHIELDED = "shielded"

        // Legacy single-entry format keys (pre per-network refactor).
        private const val LEGACY_KEY_UNSHIELDED = "unshielded"
        private const val LEGACY_KEY_SHIELDED = "shielded"

        private fun addressesToJson(addresses: WalletAddresses): JSONObject = JSONObject().apply {
            put(KEY_UNSHIELDED, addresses.unshieldedAddress)
            addresses.shieldedAddress?.let { put(KEY_SHIELDED, it) }
        }

        private fun addressesFromJson(json: JSONObject): WalletAddresses {
            val unshielded = json.optString(KEY_UNSHIELDED).takeIf { it.isNotBlank() }
                ?: throw JSONException("Missing '$KEY_UNSHIELDED'")
            val shielded = json.optString(KEY_SHIELDED).takeIf { it.isNotBlank() }
            return WalletAddresses(unshieldedAddress = unshielded, shieldedAddress = shielded)
        }
    }
}
