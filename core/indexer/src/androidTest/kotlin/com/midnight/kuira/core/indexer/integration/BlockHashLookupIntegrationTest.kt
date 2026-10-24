package com.midnight.kuira.core.indexer.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.indexer.api.BlockHashLookup
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [IndexerClientImpl.getBlockHashAtHeight] against the REAL localnet indexer — the chain-reset
 * guard's discriminator, exercised end-to-end through the actual GraphQL transport.
 *
 * **Why this test exists (the regression it pins):** the first implementation sent `height` as a
 * JSON STRING variable into a GraphQL `Int!` parameter. Standard GraphQL does not coerce strings to
 * Int, so EVERY lookup came back as a GraphQL error → [BlockHashLookup.Unavailable] → the guard
 * skipped → the entire chain-reset detection was a silent production no-op (a stale checkpoint
 * pinned at the old chain's height 7140 proved it on-device). Unit tests could not catch this —
 * they cover the pure decision functions — and no instrumented test drove the real query. This one
 * does: if the transport mistypes the height again, `Found` never comes back and the first
 * assertion fails.
 *
 * Requires a running localnet. When the indexer is unreachable this FAILS with that reason
 * rather than skipping — a skipped assertion is indistinguishable from a verified one in the gate.
 */
@RunWith(AndroidJUnit4::class)
class BlockHashLookupIntegrationTest {

    private val client = IndexerClientImpl(
        config = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
    )

    /**
     * The chain tip, or a failure that says what to fix.
     *
     * This used to skip on an unreachable indexer. It must not: a skip reads as a pass in the
     * gate, and that masked the real problem for as long as it existed — instrumented tests
     * never got the adb reverse tunnel (it was wired to `installDebug` only), so on a physical
     * device a perfectly healthy indexer looked unreachable and this test quietly opted out of
     * the regression it exists to pin.
     */
    private fun requireTip(): Long = runBlocking {
        val tip = try {
            client.getCurrentBlockWithParams()
        } catch (e: Exception) {
            throw AssertionError(
                "localnet indexer unreachable at ${NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED).indexerGraphqlUrl}. " +
                    "This assertion must RUN, not be skipped: start the localnet, and on a physical " +
                    "device establish the tunnel (`./gradlew adbReverseLocalnet`).",
                e,
            )
        }
        tip.height
    }

    @Test
    fun existingHeight_returnsFound_withTheBlocksRealHash() = runBlocking<Unit> {
        val tip = requireTip()
        // Height 1 exists on any live chain. THE regression case: a mistyped height variable
        // yields a GraphQL error → Unavailable, and this assertion names it.
        val lookup = client.getBlockHashAtHeight(1L)
        assertTrue(
            "expected Found for height 1 (tip=$tip) but got $lookup — a GraphQL transport/typing " +
                "error surfaces here as Unavailable",
            lookup is BlockHashLookup.Found,
        )
        val hash = (lookup as BlockHashLookup.Found).hash
        assertTrue("blank hash from a Found lookup", hash.isNotBlank())
        // The SAME height must round-trip identically (hash is stable within a chain).
        assertEquals(lookup, client.getBlockHashAtHeight(1L))
    }

    @Test
    fun heightBeyondTip_returnsNotOnChain_notUnavailable() = runBlocking<Unit> {
        val tip = requireTip()
        // The reset signal: a valid response with no block. Must be NotOnChain — collapsing it
        // into Unavailable would turn every real reset into a silent skip.
        val lookup = client.getBlockHashAtHeight(tip + 1_000_000L)
        assertEquals(BlockHashLookup.NotOnChain, lookup)
    }

    @Test
    fun unreachableIndexer_returnsUnavailable_neverThrows() = runBlocking<Unit> {
        val dead = IndexerClientImpl(
            config = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED)
                .copy(indexerGraphqlUrl = "http://10.0.2.2:59999/api/v3/graphql"),
        )
        assertEquals(BlockHashLookup.Unavailable, dead.getBlockHashAtHeight(1L))
    }
}
