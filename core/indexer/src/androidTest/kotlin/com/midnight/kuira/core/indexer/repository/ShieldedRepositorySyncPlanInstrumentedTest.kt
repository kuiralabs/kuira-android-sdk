package com.midnight.kuira.core.indexer.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.shielded.ZswapLocalState
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.BlockInfo
import com.midnight.kuira.core.indexer.model.DustSyncResult
import com.midnight.kuira.core.indexer.model.NetworkState
import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * On-device proof of the shielded checkpoint/delta WIRING in
 * [ShieldedRepository.syncFromBlockchain] against real native [ZswapLocalState]:
 *
 *  - **AT_TIP** (tip == cursor): the genesis re-replay is skipped — only the tip probe is opened,
 *  never the delta subscription. This is the "slow even at the tip" fix.
 *  - **FULL** (no checkpoint): streams from genesis and writes a checkpoint at the last event id —
 *  with no wasteful tip probe.
 *  - **DELTA** (tip > cursor): opens the subscription at cursor+1, replays only the new events onto
 *  the restored checkpoint, and the resulting persisted state is **byte-identical to a genesis
 *  replay** of the whole prefix — end-to-end correctness through the repository.
 *
 * The native delta==genesis identity itself is proven exhaustively in `ZswapCheckpointResumeTest`
 * (core:crypto); the routing decision in `ShieldedSyncPlanTest` (pure). The event source is faked
 * (the production body opens a real WebSocket) via the `open` [ShieldedRepository.subscribeToZswapEvents].
 */
@RunWith(AndroidJUnit4::class)
class ShieldedRepositorySyncPlanInstrumentedTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dir: File
    private lateinit var dataStore: DataStore<Preferences>

    private val address = "mn_addr_shielded_plan_test"
    private val seed = ByteArray(32) { 3 } // matches the zswap_fixture seed; tree is seed-independent

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dir = Files.createTempDirectory("zswap_plan_test").toFile()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }
    }

    @After
    fun teardown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun repo(responder: (Long?) -> List<RawLedgerEvent>) =
        FakeStreamShieldedRepository(dataStore, NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED), responder)

    /** A genesis replay of [events] (the reference state), serialized. */
    private fun genesisSerialize(events: List<String>): String {
        val base = ZswapLocalState.create()!!
        val state = try { base.replayEvents(seed, events.joinToString(""))!! } finally { base.close() }
        return try { state.serialize()!! } finally { state.close() }
    }

    @Test
    fun atTipSkipsTheDeltaSubscription() = runBlocking<Unit> {
        // Persist a checkpoint at cursor 5; the probe reports the same tip (5) → caught up.
        seedCheckpoint(cursor = 5L)
        val indexer = repo { fromId -> if (fromId == null) listOf(ev(2, "00", maxId = 5)) else error("delta opened at tip") }

        val synced = indexer.syncFromBlockchain(address, seed)

        assertFalse("empty checkpoint reports no coins", synced)
        assertEquals("at the tip only the probe is opened, never the delta", listOf<Long?>(null), indexer.fromIds)
        val ckpt = readCheckpoint()
        assertNotNull("checkpoint still present", ckpt)
        assertEquals("cursor unchanged", 5L, ckpt!!.cursor)
        ckpt.state.close()
    }

    @Test
    fun fullSyncFromGenesisWritesACheckpoint() = runBlocking<Unit> {
        // No checkpoint → no probe → full genesis stream of the 4 real events.
        val indexer = repo { fromId -> if (fromId == null) realEvents(maxId = 5) else error("unexpected fromId $fromId") }

        val synced = indexer.syncFromBlockchain(address, seed)

        assertFalse("the test seed owns no coins", synced)
        assertEquals("full sync streams from genesis with no tip probe", listOf<Long?>(null), indexer.fromIds)
        val ckpt = readCheckpoint()
        assertNotNull("full sync wrote a checkpoint", ckpt)
        assertEquals("checkpoint cursor is the last applied event id", 5L, ckpt!!.cursor)
        assertEquals("persisted state equals a genesis replay", genesisSerialize(EVENTS), ckpt.state.serialize())
        ckpt.state.close()
    }

    @Test
    fun deltaAppliesOnlyNewEventsAndMatchesGenesis() = runBlocking<Unit> {
        // Checkpoint = genesis replay of the first two events (cursor 3). Then the chain is ahead
        // (tip 5), so the delta from cursor+1 (=4) replays events 4 and 5 onto the checkpoint.
        seedRealCheckpoint(prefix = 2, cursor = 3L)
        val indexer = repo { fromId ->
            when (fromId) {
                null -> listOf(ev(2, "00", maxId = 5)) // probe → tip 5
                4L -> realEvents(maxId = 5).drop(2) // delta → events 4, 5
                else -> error("unexpected fromId $fromId")
            }
        }

        indexer.syncFromBlockchain(address, seed)

        assertEquals("delta opens the probe then the subscription at cursor+1", listOf<Long?>(null, 4L), indexer.fromIds)
        val ckpt = readCheckpoint()
        assertNotNull("delta sync re-checkpoints", ckpt)
        assertEquals("cursor advanced to the last delta event", 5L, ckpt!!.cursor)
        assertEquals(
            "checkpoint+delta state equals a full genesis replay (end-to-end through the repo)",
            genesisSerialize(EVENTS),
            ckpt.state.serialize(),
        )
        ckpt.state.close()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun ev(id: Long, rawHex: String, maxId: Long) = RawLedgerEvent(id = id, rawHex = rawHex, maxId = maxId)

    /** The 4 real events (ids 2..5) as a fake stream with [maxId] on each (so the stream stops). */
    private fun realEvents(maxId: Long): List<RawLedgerEvent> =
        EVENTS.mapIndexed { i, raw -> RawLedgerEvent(id = (i + 2).toLong(), rawHex = raw, maxId = maxId) }

    private fun seedCheckpoint(cursor: Long) = runBlocking {
        val state = ZswapLocalState.create()!!
        try { plainRepo().saveCheckpoint(address, state, cursor) } finally { state.close() }
    }

    /** Seed a checkpoint that is a real genesis replay of EVENTS[0 until prefix], at [cursor]. */
    private fun seedRealCheckpoint(prefix: Int, cursor: Long) = runBlocking {
        val base = ZswapLocalState.create()!!
        val state = try { base.replayEvents(seed, EVENTS.take(prefix).joinToString(""))!! } finally { base.close() }
        try { plainRepo().saveCheckpoint(address, state, cursor) } finally { state.close() }
    }

    private fun readCheckpoint(): ZswapCheckpoint? = runBlocking { plainRepo().loadCheckpoint(address) }

    private fun plainRepo() =
        ShieldedRepository(dataStore, FakeIndexer(), NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED))

    /** [ShieldedRepository] with a deterministic, recording event source in place of the WebSocket. */
    private class FakeStreamShieldedRepository(
        dataStore: DataStore<Preferences>,
        networkConfig: NetworkConfig,
        private val responder: (Long?) -> List<RawLedgerEvent>,
    ) : ShieldedRepository(dataStore, FakeIndexer(), networkConfig) {
        val fromIds = mutableListOf<Long?>()
        override fun subscribeToZswapEvents(fromId: Long?): Flow<RawLedgerEvent> {
            fromIds.add(fromId)
            return responder(fromId).asFlow()
        }
    }

    /** Unused by the zswap sync path (it streams via subscribeToZswapEvents) — throws if touched. */
    private class FakeIndexer : IndexerClient {
        override fun subscribeToUnshieldedTransactions(address: String, transactionId: Int?): Flow<UnshieldedTransactionUpdate> = error("unused")
        override fun subscribeToZswapEvents(fromId: Long?): Flow<RawLedgerEvent> = error("unused")
        override fun subscribeToBlocks(): Flow<BlockInfo> = error("unused")
        override suspend fun getNetworkState(): NetworkState = error("unused")
        override suspend fun getCurrentBlockWithParams(): BlockInfo = error("unused")
        override suspend fun getEventsInRange(fromId: Long, toId: Long): List<RawLedgerEvent> = error("unused")
        override fun subscribeToDustEvents(fromId: Long?): Flow<RawLedgerEvent> = error("unused")
        override suspend fun queryDustEvents(maxBlocks: Int): String = error("unused")
        override suspend fun queryDustEventsDelta(fromId: Long, timeoutMs: Long): DustSyncResult = error("unused")
        override suspend fun queryZswapEvents(): String = error("unused")
        override suspend fun queryContractState(address: String): String = error("unused")
        override suspend fun isHealthy(): Boolean = error("unused")
        override fun close() = error("unused")
        override suspend fun resetConnection() = error("unused")
    }

    companion object {
        // Real PREPROD zswap events, ids 2..5 (a genesis-anchored prefix; captured once).
        private val EVENTS = listOf(
            "6d69646e696768743a6576656e745b76395d3a0400b904533c179b36d1e83247fe0ce5245781fe51240e7dc28f0faaf526573b5fadfb78000000000109b6cec2ad9868f5c90e74c8dc234dbb0ce3368bdcf6c3d9eeab338c6b6ba59f00a91c73b8fc1f60b80f0ee0acc90ce496e8b652fac63191578438265e9d9788cb73158704a7be2051701989628ad93ad4ec49a54ca8a35275d2ee76e61716d14847733a2f4828a407500dd4add27d425703d4c9760510d00fcd95ae9e2aface597b2373199b51af1bf039757a652255806fa2b6f432783e1d154b56d13655684a718d077331206271c77ffbac8ae4861ff40497c82987998bfce13224cf5fc2831a15e71d7359b8179457ce61d507c2639a31dbe2d27380d4eb295c3cb508cd3f298ac02d4573f89bc425e2514f3d8719c5473c0220cbe1864e8dbf8059314b41edb84c1baa150100",
            "6d69646e696768743a6576656e745b76395d3a0400b904533c179b36d1e83247fe0ce5245781fe51240e7dc28f0faaf526573b5fadfb78000000000110c6ae16a85e23e74f8a5f87d661118ee2b1acea3eaefaab3450a5595e300fc900ef1fedcc557ec5abbf80be1f26369d10beefde5972149cf69dd69878020283a77315ef0b9fb07a53412f06b126161677a9c5e2d772606cf5fa074478fd6e92673273dd627f7dea257ab3b8600c015f60d6f104fdbd113202a141dcac7cba3654b420736bc82ada37d7c8d3b0b314410d2eee438bc4bcd891552f086ca3341eeaa9c45473a68f3a34cbd821e0b2fde27c1d33acc6a8c28675dec47428ec036449e670830f739e130bd2763adcfdd76b22d774cecc9c36a871cdcd4d7fccff27c3c91158f91773dec59f15d242611306fee89f1b1be5e7cc89ba46a224d0f1be87c6924e5ad0120104",
            "6d69646e696768743a6576656e745b76395d3a0400b504533c179b36d1e83247fe0ce5245781fe51240e7dc28f0faaf526573b5fadfb78000000000112df71800deac752bc70143a05b91c4da7cedfdfe77b31b9a943115845a96d530058301a152d6ac87ec6e406a27c8e5479b55952062e014afa5400abf8aa41fbbc73262e8b22a12407c9b95da5cd982442f0f4a4ccd695fb54e352167863142d124373288bd65dcdbb65e0b38355f318b43d07ba02cac47b05332c49714889ad05f066736b4b822d8a6e7596030f58aecd7ee02e4bb81b0ce503fe9218d1a1cac0693e676fc20101e5469899f304c6d043a9d28d6157e21738a0b007a89d18736766e18d733cc9409ed780a5d5a37f36d0c1698b67262f0a9cad10b8b6d95fbb66e87921337303aa5aeb1dbd75f1139305a90b6c04794a9428c9a610fbf631d4be186b139a5b0108",
            "6d69646e696768743a6576656e745b76395d3a0400b904533c179b36d1e83247fe0ce5245781fe51240e7dc28f0faaf526573b5fadfb78000000000113c2c80fb29482dc4a380e1281aab803e21887af3b34f7e48fed73b335a1edc400a788897b40bc031acd8fe9eb22bef7604a1330682eac8af52ecd28a99b1905bb737e3072b3d73b69fa012687a6d9770b1bff6483bb4326a1cbf892ee94e6045006735b822c0b822f1a5e752b5b9cc40d06ae31fe27202af98df55d74b6bb7c1fa11973f7d5dcb7a12d17bc8856ac102f158d8e55d060c66d80dc83c960b4a91bde36617343a41d52dc6e72243572cd8b21459023de48b9a7fe730cf6ee3d1ae9a5c5e90a7387035472eef13017ecf785600855bbbb3934b5a82567120e8321f9468613c65b7387d6de60d9d4ead54cf8e6d31ac80c7f434310790f30111ad5e82a8e97943b2e010c",
        )
    }
}
