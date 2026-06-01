// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.dust

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the two correctness claims dust *incremental* sync depends on, on real
 * (non-empty) state, on-device:
 *
 *  1. **checkpoint + delta == genesis** — replaying a delta on top of a
 *     serialize→deserialize-restored state lands on the IDENTICAL Merkle roots
 *     (and balance / UTXO set) as replaying every event from genesis in one
 *     pass. This is what lets `DustRepository` resume from a saved checkpoint
 *     instead of re-syncing ~900k PREPROD events on every cold start.
 *
 *  2. **DustLocalState serialize/deserialize is root-lossless** on non-empty
 *     state — closing the gap left by the unit-level
 *     `serialize_deserialize_reproduces_root` (which proved it at the MerkleTree
 *     level only). Refutes the old "SDK-001 corrupts roots" belief end-to-end.
 *
 * Both run against a bundled event fixture (real PREPROD dust events captured
 * once + the matching testnet dust seed). If the fixture is absent the tests
 * skip — so the suite stays green until the fixture lands, then proves the
 * property for real. The fixture is intentionally small (CI speed); the
 * no-OOM property at full 900k scale is verified separately on a real device.
 */
@RunWith(AndroidJUnit4::class)
class DustCheckpointResumeTest {

    @Test
    fun checkpointPlusDeltaEqualsGenesisReplay() {
        val events = loadFixtureEvents() ?: return skip("events fixture")
        val seed = loadFixtureSeed() ?: return skip("seed fixture")
        assumeTrue("fixture needs at least 2 events to split", events.size >= 2)

        // (A) Genesis-equivalent: replay every event in one pass.
        val genesis = replayAll(seed, events)
        val genRootC = genesis.commitmentRoot()
        val genRootG = genesis.generationRoot()
        val genBalance = genesis.getBalance(BALANCE_TS)
        val genUtxos = genesis.getUtxoCount()
        assertNotNull("genesis commitment root", genRootC)
        assertNotNull("genesis generation root", genRootG)
        genesis.close()

        // (B) Checkpoint + delta: replay the first half, persist through the
        //     real checkpoint path (serialize → deserialize), then replay the
        //     remaining events on top of the restored state.
        val mid = events.size / 2
        val checkpoint = replayAll(seed, events.subList(0, mid))
        val serialized = checkpoint.serialize()
        assertNotNull("checkpoint serializes", serialized)
        checkpoint.close()

        val restored = DustLocalState.deserialize(serialized!!)
        assertNotNull("checkpoint deserializes", restored)
        val resumed = replayOnto(restored!!, seed, events.subList(mid, events.size))

        // (C) The identity: restored + delta must equal genesis, exactly.
        assertEquals("commitment root", genRootC, resumed.commitmentRoot())
        assertEquals("generation root", genRootG, resumed.generationRoot())
        assertEquals("balance", genBalance, resumed.getBalance(BALANCE_TS))
        assertEquals("utxo count", genUtxos, resumed.getUtxoCount())
        resumed.close()
    }

    @Test
    fun serializeDeserializeIsRootLosslessOnNonEmptyState() {
        val events = loadFixtureEvents() ?: return skip("events fixture")
        val seed = loadFixtureSeed() ?: return skip("seed fixture")

        val state = replayAll(seed, events)
        val rootC = state.commitmentRoot()
        val rootG = state.generationRoot()
        val balance = state.getBalance(BALANCE_TS)
        val utxos = state.getUtxoCount()
        assertNotNull("non-empty state has a commitment root", rootC)

        val bytes = state.serialize()
        assertNotNull(bytes)
        state.close()

        val restored = DustLocalState.deserialize(bytes!!)
        assertNotNull(restored)
        assertEquals("commitment root survives round-trip", rootC, restored!!.commitmentRoot())
        assertEquals("generation root survives round-trip", rootG, restored.generationRoot())
        assertEquals("balance survives round-trip", balance, restored.getBalance(BALANCE_TS))
        assertEquals("utxo count survives round-trip", utxos, restored.getUtxoCount())
        restored.close()
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Replay all events into a fresh state, returning the resulting (new) state. */
    private fun replayAll(seed: ByteArray, events: List<String>): DustLocalState {
        val base = DustLocalState.create() ?: error("create() returned null")
        return try {
            replayOnto(base, seed, events)
        } finally {
            base.close()
        }
    }

    /** Replay [events] on top of [base] via the file path the production path uses. */
    private fun replayOnto(base: DustLocalState, seed: ByteArray, events: List<String>): DustLocalState {
        val file = File.createTempFile("dust_test_events_", ".hex")
        return try {
            file.bufferedWriter().use { w -> events.forEach { w.write(it); w.newLine() } }
            base.replayEventsFromFile(seed, file.absolutePath) ?: error("replayEventsFromFile returned null")
        } finally {
            file.delete()
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
        assumeTrue("dust $what not bundled (drop it under androidTest/assets/$FIXTURE_DIR/) — skipping", false)
    }

    companion object {
        private const val FIXTURE_DIR = "dust_fixture"
        private const val EVENTS_ASSET = "$FIXTURE_DIR/events.hex"
        private const val SEED_ASSET = "$FIXTURE_DIR/seed.hex"

        // Fixed reference timestamp (ms) so dust balance is comparable between
        // the two replay paths — dust value grows with time, so both must read
        // it at the same instant.
        private const val BALANCE_TS = 1_780_000_000_000L
    }
}
