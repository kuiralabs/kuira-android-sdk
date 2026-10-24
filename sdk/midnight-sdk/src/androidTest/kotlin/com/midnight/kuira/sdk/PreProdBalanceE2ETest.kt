package com.midnight.kuira.sdk

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.midnight.kuira.core.crypto.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
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

    private fun String.hexToBytes() = ByteArray(length / 2) {
        ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte()
    }

    private fun String.toBigIntegerOrNull() = runCatching { BigInteger(this) }.getOrNull()

    private companion object {
        private const val TAG = "PreProdBalance"
        private const val SEED_HEX_LENGTH = 128
        private val NIGHT_UNIT = BigInteger("1000000")
    }
}
