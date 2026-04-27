package com.midnight.example.bboard

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for BBoard state model and connection modes.
 */
class BBoardStateTest {

    // ── Connected state tracks standalone flag ──

    @Test
    fun `Connected state defaults to non-standalone`() {
        val state = BBoardState.Connected(
            networkId = "undeployed",
            contractAddress = "a".repeat(64),
            boardState = BoardState.Vacant,
        )
        assertFalse("Default should be non-standalone", state.standalone)
    }

    @Test
    fun `Connected state can be standalone`() {
        val state = BBoardState.Connected(
            networkId = "preprod",
            contractAddress = "b".repeat(64),
            boardState = BoardState.Vacant,
            standalone = true,
        )
        assertTrue("Should be standalone when set", state.standalone)
    }

    // ── NetworkChoice values ──

    @Test
    fun `NetworkChoice has correct network IDs`() {
        assertEquals("undeployed", NetworkChoice.LOCALNET.networkId)
        assertEquals("preview", NetworkChoice.PREVIEW.networkId)
        assertEquals("preprod", NetworkChoice.PREPROD.networkId)
    }

    @Test
    fun `NetworkChoice LOCALNET uses localhost URLs`() {
        assertTrue(
            "Indexer should use localhost",
            NetworkChoice.LOCALNET.indexerUrl.contains("10.0.2.2"),
        )
        assertTrue(
            "Wallet should use localhost",
            NetworkChoice.LOCALNET.walletUrl.contains("10.0.2.2"),
        )
    }

    // ── Board state transitions ──

    @Test
    fun `BoardState types are distinct`() {
        val vacant = BoardState.Vacant
        val working = BoardState.Working("Proving...")
        val occupied = BoardState.Occupied("Hello")
        val error = BoardState.CallError("Failed")

        assertNotEquals(vacant, working)
        assertNotEquals(working, occupied)
        assertNotEquals(occupied, error)
    }
}
