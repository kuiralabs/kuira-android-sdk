package com.midnight.kuira.sdk

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * On-chain end-to-end test for [MidnightSdk.sendNight]'s **auto-consolidation**
 * (#240) against a running localnet.
 *
 * A fee-paying NIGHT transfer can carry at most [MidnightSdk] `MAX_TRANSFER_INPUTS`
 * (= 2) inputs before the ledger's time-to-dismiss budget rejects it (Custom error
 * 168 — measured by `SendNightTransferE2ETest`). When an amount needs more coins
 * than that, `sendNight` first merges the largest coins (a self-send, 2-in→1-out)
 * until the top two cover the amount, then sends. This test exercises exactly that
 * path: a wallet of four equal 2-NIGHT coins, sending 5 NIGHT (needs 3 coins) →
 * one consolidation merge → final 2-input send → both finalize on chain.
 *
 * **Dedicated test wallet:** a fresh localnet wallet with only small, uniform coins
 * (the standard "abandon … art" wallet has a ~1000-NIGHT coin, so its top-2 cover
 * any realistic amount and consolidation never triggers). Generated via
 * `mn wallet generate kuira240consol`; the 64-byte seed is pinned below so the test
 * and the mn CLI agree on the address.
 *
 * **Prerequisites (automated — see the funding block in the #240 work):**
 *  - Localnet running (node 9944, indexer 8088, proof server 6300).
 *  - The wallet funded with ≥3 small NIGHT coins (`mn airdrop 2 --wallet kuira240consol` ×4)
 *    and dust registered (`mn dust register --wallet kuira240consol`).
 *
 * Skips (not fails) via [assumeTrue] if localnet is unreachable or the wallet
 * isn't funded/registered yet — matching the convention in [SdkRegistrationE2ETest].
 *
 * Run on ONE emulator only:
 *   `ANDROID_SERIAL=emulator-5554 ./gradlew :sdk:midnight-sdk:connectedDebugAndroidTest \
 *      -Pandroid.testInstrumentationRunnerArguments.class=\
 *      com.midnight.kuira.sdk.SdkSendNightConsolidationE2ETest`
 */
@RunWith(AndroidJUnit4::class)
class SdkSendNightConsolidationE2ETest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sendNight_consolidatesThenSends_onChain(): Unit = runBlocking {
        val sdk = MidnightSdk.Builder(context)
            .network(MidnightNetwork.UNDEPLOYED)
            .seed(hexToBytes(CONSOL_WALLET_SEED_HEX))
            // REMOTE proving uses localnet's proof server (10.0.2.2:6300 on the
            // emulator); no on-device proving-key bundle needed for the dust fee.
            .provingMode(ProvingMode.REMOTE)
            .build()

        try {
            Log.i(TAG, "CONSOL_WALLET_ADDR=${sdk.walletAddress}")

            // Let the unshielded subscription connect + pull this address's coins,
            // then sync dust so the fee path can load it.
            val initial = withTimeoutOrNull(INITIAL_SETTLE_MS) { sdk.wallet.balance() }
            assumeTrue(
                "Localnet unreachable — sdk.wallet.balance() timed out (indexer http://10.0.2.2:8088 healthy?)",
                initial != null,
            )
            // The unshielded subscription delivers the wallet's coins over a few seconds, so a
            // single balance() read can undercount. Wait until enough NIGHT is visible (this
            // also forces a dust resync for the fee path) before sending.
            val funded = runCatching {
                sdk.wallet.waitForFunding(minNight = SEND_AMOUNT, timeoutMs = FUNDING_TIMEOUT_MS)
            }.getOrNull()
            assumeTrue(
                "Fund kuira240consol first: needs NIGHT >= $SEND_AMOUNT across small coins + registered dust",
                funded != null && funded.dustRegistered,
            )
            Log.i(TAG, "balance: night=${funded!!.unshieldedNight} dust=${funded.dust} dustRegistered=${funded.dustRegistered}")

            // Send 5 NIGHT from a wallet of 2-NIGHT coins → needs 3 coins → over the
            // 2-input ceiling → sendNight must consolidate before sending.
            val stages = CopyOnWriteArrayList<MidnightSdk.SendProgress>()
            val result = sdk.sendNight(
                toAddress = RECIPIENT_ADDR,
                amount = SEND_AMOUNT,
                onProgress = { stages.add(it) },
            )
            Log.i(TAG, "sendNight result=$result stages=$stages")

            assertTrue(
                "Consolidation must have run (CONSOLIDATING stage emitted): $stages",
                stages.contains(MidnightSdk.SendProgress.CONSOLIDATING),
            )
            assertTrue(
                "Consolidated send must finalize (or pend): $result",
                result is MidnightSdk.SendResult.Success || result is MidnightSdk.SendResult.Pending,
            )

            // #265: the send creates a fresh change UTXO with no dust backing, and
            // sendNight must auto-register it (REGISTERING stage) so the wallet keeps
            // generating dust without a manual tap. Assert the wallet reads as fully
            // registered again (accurate per-UTXO signal — every current NIGHT UTXO
            // is generating). Poll briefly: registration propagation lags finalization
            // by ~1 block.
            assertTrue(
                "Auto-register must have run after the send (REGISTERING stage): $stages",
                stages.contains(MidnightSdk.SendProgress.REGISTERING),
            )
            var allRegistered = false
            val deadline = System.currentTimeMillis() + POST_SEND_REGISTER_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                runCatching { sdk.wallet.refresh() }
                if (sdk.wallet.balance().dustRegistered) { allRegistered = true; break }
                delay(POST_SEND_REGISTER_POLL_MS)
            }
            assertTrue(
                "After a send, the change UTXO must be auto-registered (#265) — dustRegistered should return to true",
                allRegistered,
            )
        } finally {
            sdk.close()
        }
    }

    /**
     * Diagnostic: send the consol wallet's two registered coins (~8 NIGHT) to a
     * DIFFERENT recipient — a direct 2-input transfer, no consolidation. Isolates
     * whether spending dust-registered coins fails regardless of recipient (vs only
     * the self-send merge).
     */
    @Test
    fun registeredCoins_toOtherRecipient_diagnostic(): Unit = runBlocking {
        val sdk = MidnightSdk.Builder(context)
            .network(MidnightNetwork.UNDEPLOYED)
            .seed(hexToBytes(CONSOL_WALLET_SEED_HEX))
            .provingMode(ProvingMode.REMOTE)
            .build()
        try {
            withTimeoutOrNull(INITIAL_SETTLE_MS) { sdk.wallet.balance() }
                ?: return@runBlocking assumeTrue("localnet unreachable", false)
            runCatching { sdk.wallet.refresh() }
            val bal = sdk.wallet.balance()
            assumeTrue("needs NIGHT >= 8 + dust", bal.unshieldedNight >= BigInteger("8000000") && bal.dustRegistered)
            val stages = CopyOnWriteArrayList<MidnightSdk.SendProgress>()
            // 1 NIGHT: largest-first picks ONE coin (a ~4-NIGHT registered coin covers it) →
            // single input, no consolidation, direct send to a different wallet. Isolates
            // "can a dust-registered coin be spent at all".
            val result = sdk.sendNight(RECIPIENT_ADDR, BigInteger("1000000")) { stages.add(it) }
            Log.i(TAG, "registered->other (1-coin) result=$result stages=$stages")
            assertTrue("registered-coin send to other recipient should finalize: $result",
                result is MidnightSdk.SendResult.Success || result is MidnightSdk.SendResult.Pending)
        } finally {
            sdk.close()
        }
    }

    companion object {
        private const val TAG = "SdkSendConsolE2E"

        /**
         * 64-byte BIP-39 seed of the dedicated localnet wallet `kuira240c2`
         * (`mn wallet generate`). Localnet-only test funds — no real value. Address:
         * mn_addr_undeployed1t59y7jzk43vgf4hj8hpqdwh5s9ztvd5apk5jtuc4etmytmn2kvwq9vfred
         */
        private const val CONSOL_WALLET_SEED_HEX =
            "cc496b0d137e7a96b77eef44ef76dd50fa8a011701ae3bc029ba1c7efd40d8ff" +
                "e55145b8dd897e0e6ce05e2609e894a61b6feef327e6a18ec913f6d885196998"

        /** Recipient: the standard "abandon … art" test wallet (kuiratest), undeployed. */
        private const val RECIPIENT_ADDR =
            "mn_addr_undeployed19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqauuvtx"

        /**
         * 4 NIGHT (raw u128 base units; 1 NIGHT = 1_000_000). The wallet holds ~6 small
         * coins (a ~2-NIGHT registered one + several 1-NIGHT coins, ~7 NIGHT total). The
         * top two coins sum to < 4 NIGHT, so largest-first selection must take ≥3 coins —
         * over the 2-input ceiling → forces at least one consolidation merge before the send.
         */
        private val SEND_AMOUNT = BigInteger("4000000")

        private const val INITIAL_SETTLE_MS = 15_000L

        /** Wait budget for the unshielded subscription to surface the wallet's coins. */
        private const val FUNDING_TIMEOUT_MS = 90_000L

        /** Budget for the post-send change-UTXO auto-registration to propagate (#265). */
        private const val POST_SEND_REGISTER_TIMEOUT_MS = 45_000L
        private const val POST_SEND_REGISTER_POLL_MS = 3_000L

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
