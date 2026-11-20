package com.midnight.kuira.core.grant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrantJsonTest {
    @Test
    fun anIntegerAbove2To63KeepsItsExactValue() {
        val json = GrantJson.parse("""{"now": 18446744073709551615, "small": 1800000000}""")

        assertEquals(-1L, json["now"]?.unsignedLong)
        assertNull("above 2^63 is not a signed Long", json["now"]?.long)
        assertEquals(1_800_000_000L, json["small"]?.long)
    }

    @Test
    fun textRoundTripsThroughEscapes() {
        val value = GrantJson.Obj(linkedMapOf("a\"b" to GrantJson.Str("line\nquote\" back\\ \u0001")))

        assertEquals(value, GrantJson.parse(value.encode()))
    }

    @Test
    fun malformedJsonIsRefused() {
        for (text in listOf("", "{", "{\"a\":}", "[1,]", "01", "{\"a\":1,\"a\":2}", "{} x", "\"\\q\"", "tru")) {
            assertRefused("malformed") { GrantJson.parse(text) }
        }
    }
}
