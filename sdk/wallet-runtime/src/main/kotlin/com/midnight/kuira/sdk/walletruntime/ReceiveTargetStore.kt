package com.midnight.kuira.sdk.walletruntime

import android.content.Context
import com.midnight.kuira.core.network.MidnightNetwork

/**
 * The active (network, unshielded address) the background receive poll should check.
 *
 * Written by [MidnightSdkProvider] whenever it builds / switches the SDK (it has both the
 * config's network and the wallet address); read by [ReceivePollWorker], which runs when
 * the live SDK may be gone (session locked / process backgrounded) and so can't ask it.
 *
 * Public data (a network name + a Bech32m receive address) — plain prefs, no secret.
 */
class ReceiveTargetStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Record the wallet's current network + receive address as the poll target. */
    fun setTarget(network: MidnightNetwork, address: String) {
        prefs.edit()
            .putString(KEY_NETWORK, network.name)
            .putString(KEY_ADDRESS, address)
            .apply()
    }

    /** The current target, or null if no wallet has been set up on this device. */
    fun target(): Target? {
        val network = prefs.getString(KEY_NETWORK, null)
            ?.let { runCatching { MidnightNetwork.valueOf(it) }.getOrNull() } ?: return null
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        return Target(network, address)
    }

    data class Target(val network: MidnightNetwork, val address: String)

    companion object {
        private const val PREFS = "kuira_receive_target"
        private const val KEY_NETWORK = "network"
        private const val KEY_ADDRESS = "address"
    }
}
