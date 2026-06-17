package com.midnight.kuira.sdk.walletruntime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.network.MidnightNetwork
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented durability coverage for [NetworkPreferenceStore] on a REAL device, against the
 * real on-disk SharedPreferences (Robolectric can't prove the actual commit-to-disk). A fresh
 * store instance models a NEW PROCESS reading the choice after a session-lock kill — the
 * regression where a PreProd selection silently reverted to the localnet default on cold start.
 */
@RunWith(AndroidJUnit4::class)
class NetworkPreferenceStoreInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun clearPrefs() {
        context.getSharedPreferences("kuira_network_pref", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Before fun setUp() = clearPrefs()
    @After fun tearDown() = clearPrefs()

    @Test
    fun defaultsToUndeployedWhenUnset() {
        assertEquals(MidnightNetwork.UNDEPLOYED, NetworkPreferenceStore(context).selected.value)
    }

    @Test
    fun choiceSurvivesIntoAFreshInstance() {
        NetworkPreferenceStore(context).set(MidnightNetwork.PREPROD)
        // Brand-new instance = a new process: it reads PreProd from disk, not from memory.
        assertEquals(MidnightNetwork.PREPROD, NetworkPreferenceStore(context).selected.value)
    }

    @Test
    fun userChoiceWinsOverALaterSeed() {
        NetworkPreferenceStore(context).set(MidnightNetwork.PREPROD)
        NetworkPreferenceStore(context).seedDefaultIfUnset(MidnightNetwork.UNDEPLOYED)
        assertEquals(MidnightNetwork.PREPROD, NetworkPreferenceStore(context).selected.value)
    }
}
