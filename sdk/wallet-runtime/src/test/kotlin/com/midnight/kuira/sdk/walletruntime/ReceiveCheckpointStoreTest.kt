package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.sdk.walletruntime.ReceiveCheckpointStore.Companion.shouldAnnounce
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [ReceiveCheckpointStore]'s announce cursor — the idempotency guard that
 * makes a per-transaction receipt announce at most once across the live observer and the
 * background poll (and across process death). Provenance + amount are decided upstream; this is
 * purely "have we already announced this transaction id?".
 */
class ReceiveCheckpointStoreTest {

    @Test
    fun `first ever receipt (null cursor) is announced`() {
        assertTrue(shouldAnnounce(lastAnnouncedTxId = null, txId = 100))
        assertTrue("even tx id 0 is announceable on a fresh cursor", shouldAnnounce(lastAnnouncedTxId = null, txId = 0))
    }

    @Test
    fun `a newer transaction id is announced`() {
        assertTrue(shouldAnnounce(lastAnnouncedTxId = 100, txId = 101))
        assertTrue(shouldAnnounce(lastAnnouncedTxId = 100, txId = 5000))
    }

    @Test
    fun `the already-announced transaction id is not re-announced`() {
        // The other path beat us, or the same event arrived twice.
        assertFalse(shouldAnnounce(lastAnnouncedTxId = 100, txId = 100))
    }

    @Test
    fun `an older transaction id (replay or resync) is not announced`() {
        // A first sync / resync replays history; those ids are at or below the cursor.
        assertFalse(shouldAnnounce(lastAnnouncedTxId = 100, txId = 70))
    }
}
