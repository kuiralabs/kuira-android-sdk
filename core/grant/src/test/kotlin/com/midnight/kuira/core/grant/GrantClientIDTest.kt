package com.midnight.kuira.core.grant

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Client ids in exactly the normalised forms of the scoped-grants MIP, section 4.4; anything else is refused, not
 * normalised, because its bytes are hashed as given.
 */
class GrantClientIDTest {
    @Test
    fun eachFormIsAcceptedAndNamed() {
        val accepted = listOf(
            "https://bank.example" to GrantClientID.Kind.ORIGIN,
            "https://bank.example:8443" to GrantClientID.Kind.ORIGIN,
            "http://localhost:3000" to GrantClientID.Kind.ORIGIN,
            "http://localhost" to GrantClientID.Kind.ORIGIN,
            "http://localhost:443" to GrantClientID.Kind.ORIGIN,
            "https://bank.example:80" to GrantClientID.Kind.ORIGIN,
            "https://bank.example:65535" to GrantClientID.Kind.ORIGIN,
            "https://127.0.0.1" to GrantClientID.Kind.ORIGIN,
            "https://10.0.0.255:8443" to GrantClientID.Kind.ORIGIN,
            "https://" + "a".repeat(63) + ".example" to GrantClientID.Kind.ORIGIN,
            "https://xn--bcher-kva.example" to GrantClientID.Kind.ORIGIN,
            "rdns:network.midnight.city" to GrantClientID.Kind.REVERSE_DNS,
            "mais:agent-norm.city_1~a" to GrantClientID.Kind.AGENT,
            "self:gateway_1" to GrantClientID.Kind.OWNER,
        )

        for ((value, kind) in accepted) assertEquals(value, kind, GrantClientID(value).kind)
    }

    @Test
    fun anythingNotInNormalFormIsRefused() {
        val refused = listOf(
            "",
            "https://Bank.example",
            "HTTPS://bank.example",
            "https://bank.example/",
            "https://bank.example/path",
            "https://bank.example:443",
            "http://bank.example:80",
            "https://bank.example:0443",
            "https://bank.example:70000",
            "https://bank.example:",
            "https://-bank.example",
            "https://bank..example",
            "https://bänk.example",
            "https://bank_.example",
            "https://" + "a".repeat(64) + ".example",
            "https://" + List(4) { "a".repeat(63) }.joinToString("."),
            "https://bank-.example",
            "https://bank.example:65536",
            "https://user@bank.example",
            "https://bank.example?x=1",
            "https://bank.example#x",
            "http://bank.example",
            "http://10.0.0.5:8080",
            "https://0x7f.0.0.1",
            "https://127.1",
            "https://2130706433",
            "https://010.0.0.1",
            "https://256.0.0.1",
            "https://bank.123",
            "https://[::1]:3000",
            "rdns:network.midnight-",
            "ftp://bank.example",
            "rdns:",
            "rdns:Network.midnight",
            "rdns:network..midnight",
            "mais:",
            "mais:Agent",
            "mais:agent/one",
            "mais:" + "a".repeat(129),
            "self:",
            "self:gate.way",
            "self:" + "a".repeat(65),
            "self:gate way",
        )

        for (value in refused) {
            try {
                GrantClientID(value)
                fail("`$value` was accepted")
            } catch (error: GrantError.InvalidClientID) {
                assertEquals(value, error.value)
            }
        }
    }

    @Test
    fun theLengthLimitsAreInclusive() {
        GrantClientID("mais:" + "a".repeat(128))
        GrantClientID("self:" + "a".repeat(64))
        val longRDNS = List(4) { "a".repeat(63) }.joinToString(".")
        assertEquals(255, longRDNS.length)
        try {
            GrantClientID("rdns:$longRDNS")
            fail("a 255-byte rdns: host was accepted")
        } catch (error: GrantError.InvalidClientID) {
            assertEquals("rdns:$longRDNS", error.value)
        }
        GrantClientID("rdns:" + longRDNS.drop(2))
    }
}
