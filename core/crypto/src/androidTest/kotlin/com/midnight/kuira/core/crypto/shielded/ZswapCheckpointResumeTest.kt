// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.shielded

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the correctness claim the shielded *incremental* (checkpoint + delta) sync depends
 * on — the gate for #279 (#1) before [com.midnight.kuira.core.indexer.repository.ShieldedRepository]
 * may resume from a saved checkpoint instead of re-replaying every zswap event from genesis on
 * each refresh.
 *
 *  1. **checkpoint + delta == genesis** — replaying a delta on top of a
 *     serialize→deserialize-restored [ZswapLocalState] lands on the IDENTICAL state (same
 *     serialized bytes, `firstFree`, coin count, balances) as replaying every event from
 *     genesis in one pass. If the native `replayEvents` rebuilt from scratch (instead of
 *     appending onto the restored tree), or appended at the wrong frontier, this fails —
 *     exactly the `NonLinearInsertion`/wrong-root bug class the dust side hit (#236). This is
 *     the experiment that decides whether the delta is safe to wire.
 *  2. **serialize/deserialize is lossless** on non-empty state.
 *  3. **genesis replay is deterministic** — replaying the same events twice yields identical
 *     serialized bytes (so the byte-equality used in #1 is a valid full-state comparison).
 *  4. **a torn (misaligned) resume is rejected**, not silently accepted wrong.
 *
 * Runs against a bundled zswap event fixture (real PREPROD events captured once + a fixed
 * test seed — the commitment tree is seed-independent, so the state-equality property holds
 * for any seed). If the fixture is absent the tests skip. The events are the global zswap
 * stream in id order, so a contiguous prefix is genesis-anchored; a missing event would make
 * the genesis replay itself fail.
 */
@RunWith(AndroidJUnit4::class)
class ZswapCheckpointResumeTest {

    @Test
    fun checkpointPlusDeltaEqualsGenesisReplay() {
        val events = loadFixtureEvents() ?: return skip("events fixture")
        val seed = loadFixtureSeed() ?: return skip("seed fixture")
        assumeTrue("fixture needs at least 2 events to split", events.size >= 2)

        // (A) Genesis-equivalent: replay every event in one pass.
        val genesis = replayAll(seed, events)
        val genSerialized = genesis.serialize()
        val genFirstFree = genesis.getFirstFree()
        val genCoins = genesis.getCoinCount()
        val genBalances = genesis.getBalances()
        assertNotNull("genesis serializes", genSerialized)
        genesis.close()

        // (B) Checkpoint + delta: replay the first half, persist through the real checkpoint
        //     path (serialize → deserialize), then replay the remaining events on top.
        val mid = events.size / 2
        val checkpoint = replayAll(seed, events.subList(0, mid))
        val serialized = checkpoint.serialize()
        assertNotNull("checkpoint serializes", serialized)
        checkpoint.close()

        val restored = ZswapLocalState.deserialize(serialized!!)
        assertNotNull("checkpoint deserializes", restored)
        val resumed = replayOnto(restored!!, seed, events.subList(mid, events.size))

        // (C) The identity: restored + delta must equal genesis, exactly. Byte-equal serialized
        //     state is the strongest proof (it captures the full commitment tree, for which the
        //     wrapper exposes no root accessor); the derived values are belt-and-suspenders.
        assertEquals("firstFree (tree position)", genFirstFree, resumed.getFirstFree())
        assertEquals("coin count", genCoins, resumed.getCoinCount())
        assertEquals("balances", genBalances, resumed.getBalances())
        assertEquals("serialized state (full tree) is byte-identical", genSerialized, resumed.serialize())
        resumed.close()
    }

    @Test
    fun serializeDeserializeIsLossless() {
        val events = loadFixtureEvents() ?: return skip("events fixture")
        val seed = loadFixtureSeed() ?: return skip("seed fixture")

        val state = replayAll(seed, events)
        val firstFree = state.getFirstFree()
        val coins = state.getCoinCount()
        val balances = state.getBalances()
        val bytes = state.serialize()
        assertNotNull("non-empty state serializes", bytes)
        assertTrue("replaying the fixture advances the tree", firstFree > 0)
        state.close()

        val restored = ZswapLocalState.deserialize(bytes!!)
        assertNotNull(restored)
        assertEquals("firstFree survives round-trip", firstFree, restored!!.getFirstFree())
        assertEquals("coin count survives round-trip", coins, restored.getCoinCount())
        assertEquals("balances survive round-trip", balances, restored.getBalances())
        assertEquals("re-serialization is identical", bytes, restored.serialize())
        restored.close()
    }

    @Test
    fun genesisReplayIsDeterministic() {
        val events = loadFixtureEvents() ?: return skip("events fixture")
        val seed = loadFixtureSeed() ?: return skip("seed fixture")

        val a = replayAll(seed, events)
        val b = replayAll(seed, events)
        // Validates that serialize() is canonical, so the byte-equality assertion in
        // checkpointPlusDeltaEqualsGenesisReplay is a sound full-state comparison.
        assertEquals("two genesis replays serialize identically", a.serialize(), b.serialize())
        a.close()
        b.close()
    }

    @Test
    fun tornCheckpointResumeIsRejectedNotSilentlyWrong() {
        val events = loadFixtureEvents() ?: return skip("events fixture")
        val seed = loadFixtureSeed() ?: return skip("seed fixture")
        assumeTrue("fixture needs at least 1 event", events.isNotEmpty())

        // Fully sync, then serialize→deserialize (the real checkpoint path).
        val full = replayAll(seed, events)
        val serialized = full.serialize()
        assertNotNull("checkpoint serializes", serialized)
        full.close()
        val restored = ZswapLocalState.deserialize(serialized!!)
        assertNotNull("checkpoint deserializes", restored)

        // Re-feed the whole event set onto a state that already consumed it: the first
        // commitment targets an index far below the frontier → a non-contiguous append, which
        // must be rejected (null), never silently applied to produce a divergent state.
        val resumed = restored!!.replayEvents(seed, events.joinToString(""))
        resumed?.close()
        restored.close()
        assertNull("a torn/misaligned resume must be rejected, not silently wrong", resumed)
    }

    /**
     * #290: the streamed, 500-event-chunked file replay
     * ([ZswapLocalState.replayEventsFromFile]) must land on the IDENTICAL state as the
     * in-memory single-pass [ZswapLocalState.replayEvents]. This is the device-level proof of
     * the cold-shielded-sync rewrite that removed the giant-hex-String GC freeze: the events
     * are streamed to a file (one hex per line — the form `ShieldedRepository` writes), then
     * replayed back. Byte-identical serialized state is the strongest check (full commitment
     * tree). The chunk-boundary crossing itself (>500 events) is proven in the Rust unit test
     * `test_zswap_chunked_and_file_replay_match_single_pass`; this asserts the on-device
     * native + JNI + file-parse path agrees with the historical in-memory path.
     */
    @Test
    fun replayFromFileEqualsGenesisReplay() {
        val events = loadFixtureEvents() ?: return skip("events fixture")
        val seed = loadFixtureSeed() ?: return skip("seed fixture")

        // Reference: in-memory genesis replay (concatenated hex → single replayEvents).
        val genesis = replayAll(seed, events)
        val genSerialized = genesis.serialize()
        val genFirstFree = genesis.getFirstFree()
        val genCoins = genesis.getCoinCount()
        val genBalances = genesis.getBalances()
        assertNotNull("genesis serializes", genSerialized)
        genesis.close()

        // Under test: stream events to a file (one hex per line) and replay via the file path.
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val file = File.createTempFile("zswap_replay_from_file", ".hex", ctx.cacheDir)
        try {
            file.bufferedWriter().use { w -> events.forEach { w.write(it); w.newLine() } }

            val base = ZswapLocalState.create() ?: error("create() returned null")
            val fromFile = try {
                base.replayEventsFromFile(seed, file.absolutePath)
                    ?: error("replayEventsFromFile returned null")
            } finally {
                base.close()
            }

            assertEquals("firstFree (tree position)", genFirstFree, fromFile.getFirstFree())
            assertEquals("coin count", genCoins, fromFile.getCoinCount())
            assertEquals("balances", genBalances, fromFile.getBalances())
            assertEquals(
                "serialized state (full tree) is byte-identical to genesis replay",
                genSerialized,
                fromFile.serialize(),
            )
            fromFile.close()
        } finally {
            file.delete()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Replay all [events] into a fresh state (genesis), returning the resulting (new) state. */
    private fun replayAll(seed: ByteArray, events: List<String>): ZswapLocalState {
        val base = ZswapLocalState.create() ?: error("create() returned null")
        return try {
            base.replayEvents(seed, events.joinToString("")) ?: error("replayEvents (genesis) returned null")
        } finally {
            base.close()
        }
    }

    /** Replay [events] on top of [base] (delta resume); closes [base], returns the new state. */
    private fun replayOnto(base: ZswapLocalState, seed: ByteArray, events: List<String>): ZswapLocalState {
        return try {
            base.replayEvents(seed, events.joinToString("")) ?: error("replayEvents (delta) returned null")
        } finally {
            base.close()
        }
    }

    private fun loadFixtureEvents(): List<String>? = readAsset(EVENTS_ASSET)
        ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
        ?.takeIf { it.isNotEmpty() }

    private fun loadFixtureSeed(): ByteArray? = readAsset(SEED_ASSET)
        ?.trim()?.takeIf { it.length == 64 }?.let { hex -> hex.hexToBytes() }

    private fun readAsset(path: String): String? = try {
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(path).bufferedReader().use { it.readText() }
    } catch (_: java.io.FileNotFoundException) {
        null
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun skip(what: String) {
        assumeTrue("zswap $what not bundled (drop it under androidTest/assets/$FIXTURE_DIR/) — skipping", false)
    }

    companion object {
        private const val FIXTURE_DIR = "zswap_fixture"
        private const val EVENTS_ASSET = "$FIXTURE_DIR/events.hex"
        private const val SEED_ASSET = "$FIXTURE_DIR/seed.hex"
    }
}
