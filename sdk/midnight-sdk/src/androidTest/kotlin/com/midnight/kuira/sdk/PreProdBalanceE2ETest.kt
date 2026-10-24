package com.midnight.kuira.sdk

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.midnight.kuira.core.crypto.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reads a funded PreProd wallet and asserts the balance matches the chain.
 *
 * This is the test that was missing. PreProd's indexer was decommissioned on 2026-10-09 and every
 * PreProd balance silently became 0 — not an error, a zero, labelled "Synced" — and nothing caught
 * it. Unit tests asserted URL shapes, kuiraDoctor validated the credential, and every runtime run
 * was on localnet. All green, all blind to the one thing that mattered.
 *
 * Zero is a legitimate balance, so a test that only asserts "no exception" proves nothing here.
 * This asserts a MINIMUM against a wallet funded out of band, which is the only assertion a dead
 * indexer cannot satisfy.
 *
 * ```
 * ./gradlew :sdk:midnight-sdk:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.midnight.kuira.sdk.PreProdBalanceE2ETest \
 *   -Pandroid.testInstrumentationRunnerArguments.seedHex=<128 hex> \
 *   -Pandroid.testInstrumentationRunnerArguments.blockfrostProjectId=<nightpreprod...> \
 *   -Pandroid.testInstrumentationRunnerArguments.minNight=<whole NIGHT>
 * ```
 *
 * Ground truth comes from `mn balance <address> --network preprod`, which takes the address
 * positionally and needs no seed.
 */
@RunWith(AndroidJUnit4::class)
class PreProdBalanceE2ETest {

    @Test
    fun aFundedPreProdWalletReportsItsBalance() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val seedHex = args.getString("seedHex")?.trim().orEmpty()
        val projectId = args.getString("blockfrostProjectId")?.trim().orEmpty()
        val minNight = args.getString("minNight")?.trim()?.toBigIntegerOrNull()

        // Fail, never skip. A skipped run reads exactly like a passing one in the gate's counts,
        // and that is the failure mode this whole test exists to close.
        assertEquals("seedHex must be 128 hex chars (64 bytes)", SEED_HEX_LENGTH, seedHex.length)
        assertTrue(
            "blockfrostProjectId is required — PreProd's indexer authenticates every request",
            projectId.startsWith("nightpreprod"),
        )
        assertTrue("minNight must be a positive whole number of NIGHT", (minNight ?: BigInteger.ZERO) > BigInteger.ZERO)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seed = seedHex.hexToBytes()
        val sdk = MidnightSdk.Builder(context)
            .network(MidnightNetwork.PREPROD)
            .blockfrostProjectId(projectId)
            .seed(seed)
            .provingMode(ProvingMode.REMOTE)
            .build()
        seed.fill(0)

        try {
            Log.i(TAG, "wallet ${sdk.walletAddress}")
            sdk.wallet.refresh()
            val balance = sdk.wallet.balance()
            val whole = balance.unshieldedNight.divide(NIGHT_UNIT)
            Log.i(TAG, "unshielded=${balance.unshieldedNight} ($whole NIGHT), expected >= $minNight")

            assertTrue(
                "PreProd reported $whole NIGHT for ${sdk.walletAddress}, expected at least " +
                    "$minNight. A dead or unreachable indexer shows up here as ZERO rather than " +
                    "as an error — cross-check with: mn balance ${sdk.walletAddress} " +
                    "--network preprod",
                whole >= minNight,
            )
        } finally {
            runCatching { sdk.close() }
        }
    }

    /**
     * An unreachable indexer must leave [SyncStatus.Failed], never UpToDate.
     *
     * This is the OTHER half of the PreProd outage: the balance read as 0 and the panel labelled it
     * "Synced", because every sync leg swallowed its exception and the tracked span settled on
     * UpToDate regardless. A well-formed but wrong project id reproduces that shape — Blockfrost
     * answers 403 — and needs no credentials and no funded wallet, so it runs everywhere.
     */
    @Test
    fun anUnreachableIndexerLeavesSyncFailedNotUpToDate() = runBlocking {
        val seed = ByteArray(SEED_BYTES) { WRONG_ID_SEED_BYTE }
        val sdk = MidnightSdk.Builder(InstrumentationRegistry.getInstrumentation().targetContext)
            .network(MidnightNetwork.PREPROD)
            .blockfrostProjectId(WRONG_PROJECT_ID)
            .seed(seed)
            .provingMode(ProvingMode.REMOTE)
            .build()
        seed.fill(0)

        try {
            withTimeout(UNREACHABLE_TIMEOUT_MS) { sdk.wallet.refresh() }
            val status = sdk.wallet.syncStatus.value
            Log.i(TAG, "syncStatus after a 403 indexer: $status")
            assertTrue(
                "A rejected indexer left syncStatus on $status. UpToDate here is the defect that " +
                    "made the PreProd outage invisible: a balance of 0 labelled \"Synced\".",
                status is SyncStatus.Failed,
            )
        } finally {
            runCatching { sdk.close() }
        }
    }

    /**
     * The action a user actually lost: a send on PreProd.
     *
     * The balance arrives over the WebSocket; a send needs HTTP. When our client appended
     * `/graphql` to Blockfrost — which serves GraphQL at its API root — every HTTP query 404'd
     * while balances kept arriving, so the wallet looked healthy and only Send failed. No test
     * issued an HTTP query against PreProd, so nothing caught it.
     *
     * Sends to index 1 of the SAME seed: a fixed address, same owner, funds never leave.
     */
    @Test
    fun aPreProdSendFinalizes() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val seedHex = args.getString("seedHex")?.trim().orEmpty()
        val projectId = args.getString("blockfrostProjectId")?.trim().orEmpty()
        val recipient = args.getString("recipient")?.trim().orEmpty()

        assertEquals("seedHex must be 128 hex chars (64 bytes)", SEED_HEX_LENGTH, seedHex.length)
        assertTrue("blockfrostProjectId is required", projectId.startsWith("nightpreprod"))
        assertTrue(
            "recipient must be a PreProd address — the gate derives it from the same seed at index 1",
            recipient.startsWith("mn_addr_preprod1"),
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seed = seedHex.hexToBytes()
        val sdk = MidnightSdk.Builder(context)
            .network(MidnightNetwork.PREPROD)
            .blockfrostProjectId(projectId)
            .seed(seed)
            .provingMode(ProvingMode.REMOTE)
            .build()
        seed.fill(0)

        try {
            // Register BEFORE the first refresh. Without registered dust the chain releases no
            // fee token, and a refresh then replays the whole chain only to find nothing.
            // Registration is idempotent.
            val registration = sdk.registerForDustGeneration()
            Log.i(TAG, "dust registration: $registration")

            sdk.wallet.refresh()
            val before = sdk.wallet.balance().unshieldedNight
            Log.i(TAG, "sender ${sdk.walletAddress} holds $before, sending to $recipient")
            assertTrue(
                "the gate wallet must hold more than the $SEND_WHOLE NIGHT it is about to send, " +
                    "had $before — fund ${sdk.walletAddress}",
                before > NIGHT_UNIT.multiply(BigInteger.valueOf(SEND_WHOLE)),
            )

            val stages = mutableListOf<MidnightSdk.SendProgress>()
            val result = sdk.sendNight(recipient, NIGHT_UNIT.multiply(BigInteger.valueOf(SEND_WHOLE))) {
                stages += it
            }
            Log.i(TAG, "send result=$result stages=$stages")

            assertTrue(
                "the send did not go through: $result (stages=$stages). An HTTP 404 here means " +
                    "the GraphQL endpoint is wrong for this provider; a 403 means the credential " +
                    "did not reach it.",
                result is MidnightSdk.SendResult.Success || result is MidnightSdk.SendResult.Pending,
            )
        } finally {
            runCatching { sdk.close() }
        }
    }

    private fun String.hexToBytes() = ByteArray(length / 2) {
        ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte()
    }

    private fun String.toBigIntegerOrNull() = runCatching { BigInteger(this) }.getOrNull()

    private companion object {
        private const val TAG = "PreProdBalance"
        private const val SEED_HEX_LENGTH = 128
        private const val SEED_BYTES = 64
        private val NIGHT_UNIT = BigInteger("1000000")

        // Well-formed (so NetworkConfig accepts it) but not a real project — Blockfrost 403s it,
        // which is the same shape as the decommissioned indexer's 410.
        private const val WRONG_PROJECT_ID = "nightpreprod0000000000000000000000000000"
        private const val WRONG_ID_SEED_BYTE: Byte = 0x11
        private const val UNREACHABLE_TIMEOUT_MS = 180_000L
        private const val SEND_WHOLE = 1L
    }
}
