package com.midnight.kuira.sdk.walletruntime

import android.content.Context
import com.midnight.kuira.core.network.MidnightNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single, DURABLE source of truth for the user's selected network.
 *
 * **Why this exists.** The selected network must survive process death. The wallet panel's
 * in-memory selection dies with the process, and a per-app SharedPreferences mirror written
 * with `apply()` (asynchronous) can be LOST if the OS reclaims the process before the write
 * flushes — the exact failure when a session lock backgrounds the app: a switch to PreProd
 * was silently reverted to the localnet default on the next cold start, so a PreProd send
 * bounced as an UNDEPLOYED wallet.
 *
 * **The fix.** Every change is committed SYNCHRONOUSLY ([android.content.SharedPreferences.Editor.commit] —
 * on disk before [set] returns, so it survives an immediate kill) and published on the
 * observable [selected] flow so the panel, the SDK provider, and the host all read ONE value.
 * Process-wide [Singleton] → exactly one [selected] StateFlow, so there is no second source to
 * drift out of sync.
 *
 * Plain prefs: the chosen network is public, not a secret.
 */
@Singleton
class NetworkPreferenceStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _selected = MutableStateFlow(loadPersisted() ?: DEFAULT)

    /** The selected network — the value the whole app builds the wallet on. Survives a kill. */
    val selected: StateFlow<MidnightNetwork> = _selected.asStateFlow()

    /**
     * Persist [network] DURABLY and publish it. Uses `commit()` (synchronous), NOT `apply()`,
     * so the choice is on disk before this returns — it cannot be lost to a process kill that
     * lands between the switch and the next launch.
     */
    fun set(network: MidnightNetwork) {
        prefs.edit().putString(KEY, network.name).commit()
        _selected.value = network
    }

    /**
     * Seed the FIRST-RUN network (e.g. a host's preferred starting chain), but only when the
     * user has never chosen one. A no-op once any choice is persisted — the user's selection
     * always wins over a host default.
     */
    fun seedDefaultIfUnset(network: MidnightNetwork) {
        if (!prefs.contains(KEY)) set(network)
    }

    private fun loadPersisted(): MidnightNetwork? =
        prefs.getString(KEY, null)?.let { runCatching { MidnightNetwork.valueOf(it) }.getOrNull() }

    companion object {
        private const val PREFS = "kuira_network_pref"
        private const val KEY = "selected_network"

        /** The network when the user has never chosen one. */
        val DEFAULT = MidnightNetwork.UNDEPLOYED
    }
}
