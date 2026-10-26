package com.midnight.kuira.sdk.e2e

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
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
 *  `ANDROID_SERIAL=emulator-5554./sdk/midnight-sdk/run-sdk-e2e.sh`
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
            "a single coin covers it → no consolidation: $stages",
            !stages.contains(MidnightSdk.SendProgress.CONSOLIDATING),
        )

        assertFinalizedOnChain(result)
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
        assertFinalizedOnChain(result)

        // : the send's change UTXO has no dust backing; sendNight must auto-register it (in the
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

    /** A finalized send, whose hash and block height are the ones the chain recorded. */
    private suspend fun assertFinalizedOnChain(result: MidnightSdk.SendResult) {
        val success = result as? MidnightSdk.SendResult.Success
        val txHash = success?.txHash.orEmpty()
        assertTrue("the send must finalize with a 64-hex transaction hash: $result", TX_HASH.matches(txHash))
        val indexedHeight = awaitIndexedBlockHeight(txHash)
        assertTrue("the indexer must know the returned hash as a transaction: $txHash", indexedHeight != null)
        assertEquals("the send's block height must be the block the chain recorded it in", indexedHeight, success?.blockHeight)
    }

    /** The height of the block the indexer records [hash] in, or null if it never shows up. */
    private suspend fun awaitIndexedBlockHeight(hash: String): Long? {
        val deadline = System.currentTimeMillis() + INDEXED_TX_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val height = runCatching { indexedBlockHeight(hash) }
                .onFailure { Log.w(TAG, "indexer lookup failed, retrying: ${it.message}") }
                .getOrNull()
            if (height != null) return height
            delay(INDEXED_TX_POLL_MS)
        }
        return null
    }

    private suspend fun indexedBlockHeight(hash: String): Long? = withContext(Dispatchers.IO) {
        val url = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED).indexerGraphqlUrl
        val body = JSONObject()
            .put("query", "query(${'$'}h: HexEncoded!) { transactions(offset: { hash: ${'$'}h }) { hash block { height } } }")
            .put("variables", JSONObject().put("h", hash))
            .toString()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = INDEXER_IO_TIMEOUT_MS
            connection.readTimeout = INDEXER_IO_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val transactions = response.optJSONObject("data")?.optJSONArray("transactions")
                ?: return@withContext null
            (0 until transactions.length())
                .map { transactions.getJSONObject(it) }
                .firstOrNull { it.optString("hash") == hash }
                ?.optJSONObject("block")
                ?.optLong("height")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "SdkSendIsolatedE2E"

        private val TX_HASH = Regex("^[0-9a-f]{64}$")
        private const val INDEXED_TX_TIMEOUT_MS = 60_000L
        private const val INDEXED_TX_POLL_MS = 2_000L
        private const val INDEXER_IO_TIMEOUT_MS = 10_000

        /** A valid undeployed recipient (the canonical test wallet). We only ever SEND to it. */
        private const val RECIPIENT_ADDR =
            "mn_addr_undeployed19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqauuvtx"

        private const val POST_SEND_REGISTER_TIMEOUT_MS = 45_000L
        private const val POST_SEND_REGISTER_POLL_MS = 3_000L

        /** Whole-NIGHT → raw base units (1 NIGHT = 1e6). */
        private fun night(n: Int): BigInteger = BigInteger.valueOf(n.toLong() * 1_000_000L)
    }
}
