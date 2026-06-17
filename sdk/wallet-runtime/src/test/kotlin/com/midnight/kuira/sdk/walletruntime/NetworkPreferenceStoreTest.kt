package com.midnight.kuira.sdk.walletruntime

import android.content.Context
import com.midnight.kuira.core.network.MidnightNetwork
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The network choice must survive process death. A FRESH [NetworkPreferenceStore] instance
 * models a cold start (a new process reading from disk) — the regression that a session-lock
 * kill reverted a PreProd selection back to the localnet default.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkPreferenceStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `defaults to UNDEPLOYED when nothing is persisted`() {
        assertEquals(MidnightNetwork.UNDEPLOYED, NetworkPreferenceStore(context).selected.value)
    }

    @Test
    fun `set is durable — a fresh instance (cold start) reads the choice back`() {
        NetworkPreferenceStore(context).set(MidnightNetwork.PREPROD)
        // New instance = new process: it reads from disk, not from the previous StateFlow.
        assertEquals(MidnightNetwork.PREPROD, NetworkPreferenceStore(context).selected.value)
    }

    @Test
    fun `selected StateFlow reflects the latest set within an instance`() {
        val store = NetworkPreferenceStore(context)
        store.set(MidnightNetwork.PREPROD)
        assertEquals(MidnightNetwork.PREPROD, store.selected.value)
        store.set(MidnightNetwork.PREVIEW)
        assertEquals(MidnightNetwork.PREVIEW, store.selected.value)
    }

    @Test
    fun `seedDefaultIfUnset seeds only on first run`() {
        NetworkPreferenceStore(context).seedDefaultIfUnset(MidnightNetwork.PREPROD)
        assertEquals(MidnightNetwork.PREPROD, NetworkPreferenceStore(context).selected.value)
    }

    @Test
    fun `a user choice survives a later seed attempt — selection wins over host default`() {
        NetworkPreferenceStore(context).set(MidnightNetwork.PREPROD)
        // A host re-seeding its default on next launch must NOT clobber the user's choice.
        NetworkPreferenceStore(context).seedDefaultIfUnset(MidnightNetwork.UNDEPLOYED)
        assertEquals(MidnightNetwork.PREPROD, NetworkPreferenceStore(context).selected.value)
    }

    @Test
    fun `a corrupt persisted value falls back to the default`() {
        context.getSharedPreferences("kuira_network_pref", Context.MODE_PRIVATE)
            .edit().putString("selected_network", "NOT_A_NETWORK").commit()
        assertEquals(MidnightNetwork.UNDEPLOYED, NetworkPreferenceStore(context).selected.value)
    }
}
