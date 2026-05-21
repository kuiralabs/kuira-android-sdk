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

    // NetworkChoice tests removed — the type was deleted in the
    // standalone-Gradle split refactor (8404af3) but the tests
    // weren't updated. There's nothing to test here anymore.

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
