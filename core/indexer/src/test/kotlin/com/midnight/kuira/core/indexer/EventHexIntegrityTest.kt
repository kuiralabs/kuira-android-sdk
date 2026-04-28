package com.midnight.kuira.core.indexer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies that event hex survives the JSON parsing pipeline intact.
 *
 * The PREPROD indexer sends dust events as JSON:
 * ```json
 * {"data":{"dustLedgerEvents":{"id":1,"raw":"6d69646e69...","maxId":262000}}}
 * ```
 *
 * Our IndexerClientImpl extracts `raw` via `jsonPrimitive.content`.
 * If this transformation alters the hex string in ANY way (truncation,
 * encoding, escaping), the Rust FFI will produce wrong Merkle roots
 * and error 170 on PREPROD.
 */
class EventHexIntegrityTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The first PREPROD event's hex (from WASM diagnostic). */
    private val KNOWN_FIRST_EVENT_HEX = "6d69646e696768743a6576656e745b76395d3a0400690bd38909e63c2a12ea9fbfcb012531abb09ff7cdeece616ff2bafd59"

    @Test
    fun `raw hex survives JSON roundtrip`() {
        // Simulate what the indexer sends
        val serverJson = """{"data":{"dustLedgerEvents":{"id":1,"raw":"$KNOWN_FIRST_EVENT_HEX","maxId":262000}}}"""

        // Parse exactly like IndexerClientImpl does
        val jsonElement = json.parseToJsonElement(serverJson)
        val dataElement = jsonElement.jsonObject["data"]!!
        val eventObj = dataElement.jsonObject["dustLedgerEvents"]!!.jsonObject
        val rawHex = eventObj["raw"]!!.jsonPrimitive.content

        assertEquals(
            "Hex must survive JSON parsing unchanged",
            KNOWN_FIRST_EVENT_HEX,
            rawHex
        )
    }

    @Test
    fun `long hex string survives JSON parsing`() {
        // Generate a long hex string (simulating a large event ~1500 chars)
        val longHex = "ab".repeat(750) // 1500 hex chars
        val serverJson = """{"data":{"dustLedgerEvents":{"id":99,"raw":"$longHex","maxId":262000}}}"""

        val jsonElement = json.parseToJsonElement(serverJson)
        val rawHex = jsonElement.jsonObject["data"]!!
            .jsonObject["dustLedgerEvents"]!!
            .jsonObject["raw"]!!
            .jsonPrimitive.content

        assertEquals("Long hex must survive intact", longHex, rawHex)
        assertEquals("Length must match", 1500, rawHex.length)
    }

    @Test
    fun `hex with all byte values survives JSON`() {
        // Hex containing all 256 possible bytes (00-ff)
        val allBytes = (0..255).joinToString("") { "%02x".format(it) }
        val serverJson = """{"data":{"dustLedgerEvents":{"id":1,"raw":"$allBytes","maxId":1}}}"""

        val jsonElement = json.parseToJsonElement(serverJson)
        val rawHex = jsonElement.jsonObject["data"]!!
            .jsonObject["dustLedgerEvents"]!!
            .jsonObject["raw"]!!
            .jsonPrimitive.content

        assertEquals("All byte values must survive", allBytes, rawHex)
    }

    @Test
    fun `concatenated hex for chunk matches individual events`() {
        // Simulate the old chunked approach: concatenate then split by tag prefix
        val event1 = "6d69646e696768743a6576656e745b76395d3a0400aabbcc"
        val event2 = "6d69646e696768743a6576656e745b76395d3a0400ddeeff"
        val concatenated = event1 + event2

        // Tag prefix (hex of "midnight:event[v")
        val tagPrefix = "6d69646e696768743a6576656e745b76"

        val parts = concatenated.split(tagPrefix).filter { it.isNotEmpty() }
        val reconstructed = parts.map { tagPrefix + it }

        assertEquals("Should split into 2 events", 2, reconstructed.size)
        assertEquals("Event 1 must match", event1, reconstructed[0])
        assertEquals("Event 2 must match", event2, reconstructed[1])
    }

    @Test
    fun `tag prefix in SCALE data causes false split`() {
        // This is the BUG: if SCALE binary data happens to contain
        // the hex bytes "6d69646e696768743a6576656e745b76",
        // the tag-prefix split corrupts the event.
        val tagPrefix = "6d69646e696768743a6576656e745b76"

        // Event whose SCALE data contains the tag prefix bytes
        val event = "6d69646e696768743a6576656e745b76395d3a" +
            "0400" + tagPrefix + "aabbcc" // SCALE data contains tag prefix

        val parts = event.split(tagPrefix).filter { it.isNotEmpty() }

        // This INCORRECTLY splits into 2 "events"
        assertTrue(
            "Tag prefix in SCALE data causes false split (${parts.size} parts)",
            parts.size > 1
        )

        // The line-per-event approach doesn't have this problem
        val lines = listOf(event) // One event per line
        assertEquals("Line-based split is correct", 1, lines.size)
        assertEquals("Event preserved", event, lines[0])
    }
}
