package com.midnight.kuira.sdk.e2e

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * On-chain e2e for the PUBLIC [MidnightSdk.sendShieldedNight] money-path, on a fresh per-test
 * localnet wallet ([IsolatedWalletE2E]). Proves the shielded send end-to-end: the SDK scans a genesis
 * shielded airdrop, builds the zswap offer, binds a dust fee, proves (REMOTE — localnet proof server),
 * submits, and finalizes.
 *
 * Funding needs BOTH pools: shielded NIGHT to SEND, plus unshielded NIGHT + registered dust to PAY the
 * fee (the shielded fee is a dust spend, and dust is backed by unshielded NIGHT). The host harness
 * services `mn airdrop [--shielded]` for the per-test wallet's respective addresses.
 *
 * Run via the funding host harness:
 *  `ANDROID_SERIAL=emulator-5554 TEST_CLASS=com.midnight.kuira.sdk.e2e.SdkSendShieldedIsolatedE2ETest ./sdk/midnight-sdk/run-sdk-e2e.sh`
 *
 * SKIPs (never fails) when localnet/funding is absent — see [IsolatedWalletE2E].
 *
 * Localnet shielded funding IS supported: `mn airdrop --shielded` credits recipient-scannable zswap
 * coins (verified end-to-end — the SDK's ShieldedBalanceTracker scans them in seconds, see
 * [SdkShieldedScanDiagnosticTest]). The airdrop is a ~90s zswap+proof, so the servicer runs it
 * synchronously; the device then waits on `balance().shieldedNight` before sending.
 */
@RunWith(AndroidJUnit4::class)
class SdkSendShieldedIsolatedE2ETest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wallet = IsolatedWalletE2E(context)

    @After
    fun tearDown() = runBlocking { wallet.close() }

    @Test
    fun shieldedSend_finalizes(): Unit = runBlocking {
        // Unshielded 5 NIGHT + registered dust (to pay the fee) and 3 shielded NIGHT (to send).
        val sdk = wallet.start(nightWhole = 5, smallUtxos = 1, registerDust = true, shieldedWhole = 3)
        val stages = CopyOnWriteArrayList<MidnightSdk.SendProgress>()

        val result = sdk.sendShieldedNight(SHIELDED_RECIPIENT, night(1)) { stages.add(it) }
        Log.i(TAG, "shielded result=$result stages=$stages")

        assertTrue(
            "shielded send must finalize: $result",
            result is MidnightSdk.SendResult.Success || result is MidnightSdk.SendResult.Pending,
        )
        assertTrue(
            "shielded send must emit PROVING: $stages",
            stages.contains(MidnightSdk.SendProgress.PROVING),
        )
    }

    companion object {
        private const val TAG = "SdkSendShieldedE2E"

        /** A valid undeployed SHIELDED recipient — we only ever SEND to it, so we needn't control it. */
        private const val SHIELDED_RECIPIENT =
            "mn_shield-addr_undeployed12n5z6rnkkcq00rv770adgahaf3mmmxd0nff6d9gry583vck4vfw6207yenplugrlzegew32lz6q8svf76sxu56eld5f38f5mdhvhecgzktmek"

        /** Whole-NIGHT → raw base units (1 NIGHT = 1e6). */
        private fun night(n: Int): BigInteger = BigInteger.valueOf(n.toLong() * 1_000_000L)
    }
}
