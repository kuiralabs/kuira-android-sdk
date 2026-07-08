package com.midnight.kuira.core.indexer.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.indexer.api.BlockHashLookup
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
 * Requires a running localnet (`mn localnet up`) — skipped (assumption) when unreachable, matching
 * the suite's convention for indexer-backed integration tests.
 */
@RunWith(AndroidJUnit4::class)
class BlockHashLookupIntegrationTest {

    private val client = IndexerClientImpl(
        baseUrl = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED).indexerBaseUrl,
        developmentMode = true,
    )

    private fun tipOrSkip(): Long = runBlocking {
        val tip = try {
            client.getCurrentBlockWithParams()
        } catch (e: Exception) {
            null
        }
        assumeTrue("localnet indexer not reachable — skipping", tip != null)
        tip!!.height
    }

    @Test
    fun existingHeight_returnsFound_withTheBlocksRealHash() = runBlocking<Unit> {
        val tip = tipOrSkip()
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
        val tip = tipOrSkip()
        // The reset signal: a valid response with no block. Must be NotOnChain — collapsing it
        // into Unavailable would turn every real reset into a silent skip.
        val lookup = client.getBlockHashAtHeight(tip + 1_000_000L)
        assertEquals(BlockHashLookup.NotOnChain, lookup)
    }

    @Test
    fun unreachableIndexer_returnsUnavailable_neverThrows() = runBlocking<Unit> {
        val dead = IndexerClientImpl(
            baseUrl = "http://10.0.2.2:59999/api/v3",
            developmentMode = true,
        )
        assertEquals(BlockHashLookup.Unavailable, dead.getBlockHashAtHeight(1L))
    }
}
