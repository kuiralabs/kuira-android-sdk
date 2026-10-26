package com.midnight.kuira.core.ledger.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each `author_submitAndWatchExtrinsic` status, in the wire shapes a Substrate node sends, and
 * what the watch loop must make of it.
 */
class TransactionStatusParserTest {

    @Test
    fun `pool statuses report nothing yet`() {
        assertEquals(WatchedStatus.Pooled, parse("\"future\""))
        assertEquals(WatchedStatus.Pooled, parse("\"ready\""))
    }

    @Test
    fun `broadcast is broadcast in both of its wire shapes, and never a block`() {
        // The object form is what the node sends; reading it as a block fired IN_BLOCK too early.
        assertEquals(WatchedStatus.Broadcast, parse("""{"broadcast":["12D3KooWPeer"]}"""))
        assertEquals(WatchedStatus.Broadcast, parse("\"broadcast\""))
    }

    @Test
    fun `inBlock carries its block hash without the 0x prefix`() {
        assertEquals(WatchedStatus.InBlock(BLOCK), parse("""{"inBlock":"0x$BLOCK"}"""))
    }

    @Test
    fun `finalized carries its block hash without the 0x prefix`() {
        assertEquals(WatchedStatus.Finalized(BLOCK), parse("""{"finalized":"0x$BLOCK"}"""))
    }

    @Test
    fun `a block status with no block hash is a malformed status, not a block`() {
        val status = parse("""{"finalized":""}""")

        assertTrue("a finalized status must name its block: $status", status is WatchedStatus.Ended)
        assertTrue((status as WatchedStatus.Ended).result is TransactionFinalizationResult.Invalid)
    }

    @Test
    fun `retracted puts the transaction back in the pool`() {
        assertEquals(WatchedStatus.Retracted, parse("""{"retracted":"0x$BLOCK"}"""))
    }

    @Test
    fun `dropped and invalid end the watch as failures`() {
        assertEquals(
            WatchedStatus.Ended(TransactionFinalizationResult.Dropped("Transaction dropped from pool")),
            parse("\"dropped\""),
        )
        assertEquals(
            WatchedStatus.Ended(TransactionFinalizationResult.Invalid("Transaction is invalid")),
            parse("\"invalid\""),
        )
    }

    @Test
    fun `usurped ends the watch naming the replacing transaction`() {
        val replacer = "e".repeat(64)

        val status = parse("""{"usurped":"0x$replacer"}""")

        assertEquals(WatchedStatus.Ended(TransactionFinalizationResult.Usurped(replacedBy = replacer)), status)
        assertEquals("Transaction replaced by $replacer", (status as WatchedStatus.Ended).result.let {
            (it as TransactionFinalizationResult.Failure).reason
        })
    }

    @Test
    fun `finality timeout ends the watch as dropped, naming the block`() {
        assertEquals(
            WatchedStatus.Ended(TransactionFinalizationResult.Dropped("Finality timeout at block $BLOCK")),
            parse("""{"finalityTimeout":"0x$BLOCK"}"""),
        )
    }

    @Test
    fun `an unknown status ends the watch as invalid rather than waiting forever`() {
        val status = parse("\"somethingNew\"")

        assertTrue(status is WatchedStatus.Ended)
        assertTrue((status as WatchedStatus.Ended).result is TransactionFinalizationResult.Invalid)
    }

    @Test
    fun `a chain_getHeader number is a hex height`() {
        assertEquals(57_908L, parseBlockNumber("0xe234"))
        assertEquals(0L, parseBlockNumber("0x0"))
    }

    @Test
    fun `an empty or non-hex header number is no height`() {
        assertNull(parseBlockNumber("0x"))
        assertNull(parseBlockNumber("0xzz"))
    }

    private fun parse(wire: String): WatchedStatus = parseWatchedStatus(Json.parseToJsonElement(wire))

    private companion object {
        val BLOCK = "ad44bf3a736563e858c41cf747549e3f009e6996c1a76d272bba5f7b39ea344b"
    }
}
