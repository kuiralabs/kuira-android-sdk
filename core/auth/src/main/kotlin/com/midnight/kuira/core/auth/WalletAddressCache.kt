// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Cached wallet addresses derived at onboarding time.
 *
 * Addresses are public information — unlike the seed, they don't need to be
 * biometric-gated or encrypted. Caching them lets read-only features (like
 * BalanceScreen) show the user's wallet without requiring a biometric prompt
 * every time they open the app.
 *
 * @property unshieldedAddress The Bech32m-encoded unshielded (public) address
 *   (e.g., `mn_addr_undeployed1...`). Derived from the first NIGHT_EXTERNAL key.
 * @property shieldedAddress The Bech32m-encoded shielded (private) address
 *   (e.g., `mn_shield-addr_undeployed1...`). Derived from the first ZSWAP key
 *   combined with encryption public key. Null if shielded key derivation failed.
 */
data class WalletAddresses(
    val unshieldedAddress: String,
    val shieldedAddress: String?,
)

/**
 * Persistent store for the wallet's public addresses.
 *
 * Stored as JSON in `<app filesDir>/kuira_wallet_address.json`. Written once
 * at wallet creation time (by the onboarding flow) and read thereafter by
 * any feature that needs to display the address without triggering biometric.
 *
 * **Design rationale:**
 * - Public data → no encryption needed
 * - JSON → human-inspectable during development (can `cat` the file to verify)
 * - Same filesDir as the encrypted seed → excluded from Auto Backup (see
 *   `backup_rules.xml` — the exclude pattern is for the seed file; the
 *   address file is fine to back up because it's public, but we err on the
 *   side of keeping all wallet metadata together)
 *
 * **Threading:** All I/O runs on `Dispatchers.IO`.
 */
class WalletAddressCache(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    /**
     * Persist the derived addresses. Called by the onboarding flow immediately
     * after wallet creation / restore, before the plaintext seed is wiped.
     */
    suspend fun save(addresses: WalletAddresses) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put(KEY_UNSHIELDED, addresses.unshieldedAddress)
            addresses.shieldedAddress?.let { put(KEY_SHIELDED, it) }
        }
        file.writeText(json.toString())
    }

    /**
     * Load the cached addresses. Returns null if the wallet hasn't been
     * onboarded yet (file doesn't exist) or if the file is corrupted.
     */
    suspend fun load(): WalletAddresses? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            val json = JSONObject(file.readText())
            val unshielded = json.optString(KEY_UNSHIELDED).takeIf { it.isNotBlank() }
                ?: return@withContext null
            val shielded = json.optString(KEY_SHIELDED).takeIf { it.isNotBlank() }
            WalletAddresses(unshieldedAddress = unshielded, shieldedAddress = shielded)
        } catch (e: IOException) {
            // File disappeared / unreadable between exists() and readText(),
            // or some other I/O failure. Treat as "no cached addresses yet".
            null
        } catch (e: JSONException) {
            // Cache file exists but is malformed — best-effort: skip it.
            null
        }
    }

    /**
     * Delete the cache (called during wallet reset).
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        if (file.exists()) file.delete()
    }

    companion object {
        internal const val FILE_NAME = "kuira_wallet_address.json"
        private const val KEY_UNSHIELDED = "unshielded"
        private const val KEY_SHIELDED = "shielded"
    }
}
