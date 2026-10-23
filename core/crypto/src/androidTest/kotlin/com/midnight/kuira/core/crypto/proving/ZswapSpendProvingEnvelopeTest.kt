package com.midnight.kuira.core.crypto.proving

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.shielded.ZswapLocalState
import com.midnight.kuira.core.crypto.shielded.ZswapTransferBuilder
import com.midnight.kuira.core.testing.ProofMemorySampler
import com.midnight.kuira.core.testing.ProofMemoryUse
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The proving envelope for a zswap SPEND — the most expensive circuit the wallet uses
 * (k=15, rows=17540, against output's k=14/8493 and the bboard contract's k=13/4569).
 *
 * It matters on its own and as the only like-for-like comparison available: the published
 * third-party figure people quote is a k=15 zswap spend on a 2019 Galaxy S10 at 11.46 s. Timing
 * our *output* circuit against that would flatter us, since output is the cheaper circuit — so a
 * spend number is what makes the comparison honest.
 *
 * Why a committed fixture. Coins only enter a [ZswapLocalState] through `replayEvents`; there is
 * no offline way to mint one, and the sibling `zswap_fixture/` holds the global PREPROD stream
 * under a synthetic seed, so it owns nothing to spend. `zswap_spend_fixture/` is 61 localnet
 * events in which the fixture seed owns one 5 NIGHT coin, which keeps this test offline,
 * deterministic and runnable on any device — no localnet, no funding, no proof server.
 *
 * All samples run in ONE process so the first is genuinely cold and the rest genuinely warm, and
 * only [LocalProver.proveTransaction] sits inside the timed region. Each sample rebuilds the
 * transaction from the same starting state: `buildTransfer` does not mutate the state it is
 * given, it returns a new pointer, so the coin stays spendable for the next sample.
 */
@RunWith(AndroidJUnit4::class)
class ZswapSpendProvingEnvelopeTest {

    @Test
    fun zswapSpendProvingEnvelope_coldThenWarm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyManager = ProvingKeyManager(context)
        runBlocking { if (!keyManager.hasWalletKeys()) keyManager.downloadWalletKeys() }
        assertTrue("Wallet proving keys are required to prove a spend", keyManager.hasWalletKeys())

        val events = readAsset(EVENTS_ASSET)
            ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
            ?: missingFixture("events")
        val seed = readAsset(SEED_ASSET)?.trim()?.takeIf { it.length == 64 }?.hexToBytes()
            ?: missingFixture("seed")

        val base = ZswapLocalState.create()
        assertNotNull("zswap state must be creatable", base)
        val state = base!!.replayEvents(seed, events.joinToString(""))
        assertNotNull("fixture events must replay", state)
        // Guards the fixture itself: without an owned coin there is nothing to spend, and the
        // failure would otherwise surface as an unexplained null from buildTransfer.
        assertTrue(
            "fixture must own a spendable coin (coins=${state!!.getCoinCount()}, " +
                "balances=${state.getBalances()})",
            state.getCoinCount() > 0,
        )
        Log.i(TAG, "fixture: ${events.size} events, ${state.getCoinCount()} coin(s), ${state.getBalances()}")

        val (coinPk, encPk) = recipientKeys()
        val elapsed = mutableListOf<Long>()
        val provenSizes = mutableListOf<Int>()
        val memUse = mutableListOf<ProofMemoryUse>()
        var unprovenSize = 0

        repeat(PROOF_SAMPLES) { sample ->
            val transfer = ZswapTransferBuilder.buildTransfer(
                state = state,
                seed = seed,
                recipientCoinPk = coinPk,
                recipientEncPk = encPk,
                tokenType = NIGHT_TOKEN,
                amount = SPEND_AMOUNT,
                networkId = NETWORK_ID,
                ttlMs = System.currentTimeMillis() + 3_600_000,
            )
            assertNotNull("sample $sample must build a spend transaction", transfer)
            val txHex = transfer!!.transactionHex
            unprovenSize = txHex.length / 2

            val sampler = ProofMemorySampler().also { it.start() }
            val start = System.currentTimeMillis()
            val provenTxHex = LocalProver.proveTransaction(
                unprovenTxHex = txHex,
                keysDir = keyManager.keysDir.absolutePath,
            )
            elapsed += System.currentTimeMillis() - start
            memUse += sampler.stopAndRead()

            assertNotNull("sample $sample must produce a proof", provenTxHex)
            assertTrue(
                "sample $sample proof must add bytes: unproven=${txHex.length / 2}B, " +
                    "proven=${provenTxHex!!.length / 2}B",
                provenTxHex.length > txHex.length,
            )
            provenSizes += provenTxHex.length / 2
            // The spend leaves a new native state behind; release it, or the loop leaks one per
            // sample. This is also the second caller of freeNativePtr, which used to throw
            // UnsatisfiedLinkError on every call -- see ZswapLocalStateFreePtrTest.
            ZswapLocalState.freeNativePtr(transfer.updatedStatePtr)
        }

        val cold = elapsed.first()
        val warm = elapsed.drop(1)
        Log.i(TAG, "=== zswap SPEND proving envelope (circuit k=15, rows=17540) ===")
        Log.i(TAG, "  cold (first proof in process): ${cold}ms")
        Log.i(
            TAG,
            "  warm: ${warm.joinToString(" / ") { "${it}ms" }} (mean ${warm.average().toInt()}ms)",
        )
        Log.i(TAG, "  tx: ${unprovenSize}B unproven -> ${provenSizes.first()}B proven")
        Log.i(TAG, "  native heap: ${memUse.joinToString(" | ") { it.nativeSummary() }}")
        Log.i(TAG, "  total PSS:   ${memUse.joinToString(" | ") { it.pssSummary() }}")
        Log.i(
            TAG,
            "  worst-case peak PSS observed: " +
                "%.0fMB".format(memUse.maxOf { it.peakPssKb } / 1024.0),
        )

        val spread = provenSizes.max() - provenSizes.min()
        assertTrue(
            "proof size for one circuit spread ${spread}B across samples " +
                "(> $PROOF_SIZE_TOLERANCE_B): $provenSizes",
            spread <= PROOF_SIZE_TOLERANCE_B,
        )
        assertTrue("every sample must be timed: $elapsed", elapsed.all { it > 0 })
        assertEquals("every sample must be measured", PROOF_SAMPLES, elapsed.size)
        // Sanity on the sampler, not on the prover: a proof of the largest circuit cannot run
        // without allocating, so a zero peak would mean the sampler never observed anything and
        // the number above is meaningless.
        assertTrue(
            "the sampler must observe native allocation during a k=15 proof: $memUse",
            memUse.all { it.nativeDeltaKb > 0 },
        )
        // The number that decides whether a k=15 proof fits on a smaller device is the ABSOLUTE
        // peak, not the per-proof delta: the first proof allocates most of it and the allocator
        // keeps it, so later deltas look small against an already-raised baseline. Measured
        // ~650MB on this device; the ceiling is set to catch a doubling rather than to pin a
        // device-specific figure, since PSS varies with OS and hardware.
        val worstPssKb = memUse.maxOf { it.peakPssKb }
        assertTrue(
            "peak PSS during a k=15 proof was ${worstPssKb / 1024}MB, over the " +
                "${PEAK_PSS_CEILING_KB / 1024}MB ceiling — proving this circuit may no longer " +
                "fit on a mid-range device",
            worstPssKb <= PEAK_PSS_CEILING_KB,
        )
        state.close()
    }

    /** Any valid shielded recipient will do; the proof cost is the sender's, not the recipient's. */
    private fun recipientKeys(): Pair<String, String> {
        val (_, payload) = Bech32m.decode(RECIPIENT_SHIELDED_ADDR)
        assertEquals("shielded payload should be 64 bytes", 64, payload.size)
        return payload.sliceArray(0 until 32).toHex() to payload.sliceArray(32 until 64).toHex()
    }

    private fun readAsset(path: String): String? = try {
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(path).bufferedReader().use { it.readText() }
    } catch (_: java.io.FileNotFoundException) {
        null
    }

    /**
     * These assets ARE committed under `androidTest/assets/$FIXTURE_DIR/`, so a missing one is a
     * packaging regression. Failing beats skipping: a skipped proving test reads exactly like a
     * passing one in the gate's counts.
     */
    private fun missingFixture(what: String): Nothing =
        throw AssertionError(
            "zswap spend $what missing from androidTest/assets/$FIXTURE_DIR/ — it is committed " +
                "there, so this is a packaging regression, not a reason to skip.",
        )

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes() =
        ByteArray(length / 2) { ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte() }

    /**
     * Peak native-heap usage observed while a proof runs.
     *
     * Native heap, not the JVM heap: the prover is Rust, so `Runtime.totalMemory` sees almost
     * none of it. Sampled from a thread because the proof is one blocking call, so there is no
     * point inside it to read a high-water mark from, and
     * [android.os.Debug.getNativeHeapAllocatedSize] reports the instantaneous figure rather than
     * a peak.
     *
     * This is a floor on real usage, not a ceiling: a sampler can miss a spike between polls, and
     * it does not see memory the allocator has returned to its pools. Good enough to answer "does
     * this fit on a smaller device", which is what the figure is for.
     */
    private companion object {
        private const val TAG = "ZswapSpendEnvelope"

        private const val FIXTURE_DIR = "zswap_spend_fixture"
        private const val EVENTS_ASSET = "$FIXTURE_DIR/events.hex"
        private const val SEED_ASSET = "$FIXTURE_DIR/seed.hex"

        private const val NIGHT_TOKEN =
            "0000000000000000000000000000000000000000000000000000000000000000"
        private const val NETWORK_ID = "undeployed"

        /** The fixture coin is 5 NIGHT, so this spends part of it and takes change. */
        private val SPEND_AMOUNT = BigInteger("1000000")

        private const val RECIPIENT_SHIELDED_ADDR =
            "mn_shield-addr_undeployed1ys3ucc6ly48wtqekax3ehp3qwdfc257tcd2uaqp8vf3kvm6wggccgjhsxtsuw983n80t28fk3ncdtk2dcxn268f4mxrrhaqmkwt7ajs4fj9f5"

        /** One cold proof plus three warm ones — the shape the other envelopes were measured in. */
        private const val PROOF_SAMPLES = 4

        /**
         * How far one circuit's proof size may legitimately move between samples. The contract
         * path measured a 2 B swing from variable-length encoding inside the serialized proof;
         * this leaves room for that without tolerating real growth.
         */
        private const val PROOF_SIZE_TOLERANCE_B = 64

        /**
         * Ceiling on peak total PSS for one k=15 proof. Measured ~650 MB on a Galaxy Z Fold 7, so
         * this is roughly 2x headroom: it catches a regression that would double the footprint
         * without failing on ordinary variation between devices and OS versions.
         */
        private const val PEAK_PSS_CEILING_KB = 1_500L * 1024
    }
}
