package com.midnight.kuira.core.indexer.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `blocks`-subscription payload parse behind [IndexerClientImpl.subscribeToBlocks]. The
 * WS round-trip needs a live indexer to verify end-to-end, so the parse is split out and pinned
 * here: a well-formed payload → [com.midnight.kuira.core.indexer.model.BlockInfo]; a malformed /
 * partial one → null (so subscribeToBlocks skips it rather than crashing the stream).
 */
class ParseBlockInfoTest {

    private fun data(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `parses a well-formed blocks payload`() {
        // BlockInfo validates hash as pure hex, >= 32 chars (a real block hash).
        val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val block = parseBlockInfo(
            data("""{"blocks":{"height":42,"hash":"$hash","timestamp":1700000000}}"""),
        )!!
        assertEquals(42L, block.height)
        assertEquals(hash, block.hash)
        assertEquals(1_700_000_000L, block.timestamp)
    }

    @Test
    fun `returns null when the blocks object is absent`() {
        assertNull(parseBlockInfo(data("""{"somethingElse":1}""")))
    }

    @Test
    fun `returns null when a required field is missing (partial payload)`() {
        // no timestamp → skip, don't crash the subscription
        assertNull(parseBlockInfo(data("""{"blocks":{"height":42,"hash":"0xabc"}}""")))
    }
}
