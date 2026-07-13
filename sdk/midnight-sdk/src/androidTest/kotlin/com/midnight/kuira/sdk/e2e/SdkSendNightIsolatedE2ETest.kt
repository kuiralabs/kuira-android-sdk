package com.midnight.kuira.sdk.e2e

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * On-chain e2e for the PUBLIC [MidnightSdk.sendNight] money-path, each test on its OWN fresh
 * localnet wallet ([IsolatedWalletE2E]). Because no two tests share an address, the StaleUtxo(195)/
 * DustDoubleSpend(196) cross-contamination of the old shared-wallet tests cannot occur (storage is
 * keyed per-address).
 *
 * Covers both money-path shapes on fresh per-test wallets — single-input (no consolidation) and
 * multi-input (consolidation). Run via the funding host harness
 * (it services the per-test `KUIRA_FUND_REQ` markers):
 *   `ANDROID_SERIAL=emulator-5554 ./sdk/midnight-sdk/run-sdk-e2e.sh`
 *
 * SKIPs (never fails) when localnet/funding is absent — see [IsolatedWalletE2E].
 */
@RunWith(AndroidJUnit4::class)
class SdkSendNightIsolatedE2ETest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wallet = IsolatedWalletE2E(context)

    @After
    fun tearDown() = runBlocking { wallet.close() }

    /** Single-input: one coin covers the amount → no consolidation. */
    @Test
    fun singleInputSend_finalizes(): Unit = runBlocking {
        val sdk = wallet.start(nightWhole = 5, smallUtxos = 1, registerDust = true)
        val stages = CopyOnWriteArrayList<MidnightSdk.SendProgress>()
        val result = sdk.sendNight(RECIPIENT_ADDR, night(1)) { stages.add(it) }
        Log.i(TAG, "single-input result=$result stages=$stages")
        assertTrue(
            "single-input send must finalize: $result",
            result is MidnightSdk.SendResult.Success || result is MidnightSdk.SendResult.Pending,
        )
        assertTrue(
            "a single coin covers it → no consolidation: $stages",
            !stages.contains(MidnightSdk.SendProgress.CONSOLIDATING),
        )
    }

    /** Multi-input: the top two coins can't cover the amount → sendNight consolidates, then sends. */
    @Test
    fun multiInputSend_consolidatesThenFinalizes(): Unit = runBlocking {
        // 5 coins of 2 NIGHT each; send 5 NIGHT → top-2 (4) < 5 → needs 3 coins → over the
        // 2-input ceiling → sendNight must consolidate before sending.
        val sdk = wallet.start(nightWhole = 10, smallUtxos = 5, registerDust = true)
        val stages = CopyOnWriteArrayList<MidnightSdk.SendProgress>()
        val result = sdk.sendNight(RECIPIENT_ADDR, night(5)) { stages.add(it) }
        Log.i(TAG, "multi-input result=$result stages=$stages")
        assertTrue(
            "consolidation must run (CONSOLIDATING emitted): $stages",
            stages.contains(MidnightSdk.SendProgress.CONSOLIDATING),
        )
        assertTrue(
            "consolidated send must finalize: $result",
            result is MidnightSdk.SendResult.Success || result is MidnightSdk.SendResult.Pending,
        )

        // #265: the send's change UTXO has no dust backing; sendNight must auto-register it (in the
        // background) so the wallet keeps generating dust. Poll until it reads fully registered.
        var registered = false
        val deadline = System.currentTimeMillis() + POST_SEND_REGISTER_TIMEOUT_MS
        while (!registered && System.currentTimeMillis() < deadline) {
            runCatching { sdk.wallet.refresh() }
            registered = sdk.wallet.balance().dustRegistered
            if (!registered) delay(POST_SEND_REGISTER_POLL_MS)
        }
        assertTrue("post-send change UTXO must auto-register (#265)", registered)
    }

    companion object {
        private const val TAG = "SdkSendIsolatedE2E"

        /** A valid undeployed recipient (the canonical test wallet). We only ever SEND to it. */
        private const val RECIPIENT_ADDR =
            "mn_addr_undeployed19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqauuvtx"

        private const val POST_SEND_REGISTER_TIMEOUT_MS = 45_000L
        private const val POST_SEND_REGISTER_POLL_MS = 3_000L

        /** Whole-NIGHT → raw base units (1 NIGHT = 1e6). */
        private fun night(n: Int): BigInteger = BigInteger.valueOf(n.toLong() * 1_000_000L)
    }
}
