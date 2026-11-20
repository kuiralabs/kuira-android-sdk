package com.midnight.kuira.core.grant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The grant spec's structures against the shared vectors: each decodes to the reference's fields, encodes back
 * to the same bytes, and each refusal fails for the spec's reason.
 */
class GrantCodecVectorTest {
    private val grantEntry = Vectors["grant"]

    @Test
    fun theGrantDecodesToTheReferenceFieldsAndEncodesBack() {
        val fields = grantEntry.field("fields")
        val preimage = grantEntry.field("preimage").bytes()

        val grant = Grant.decode(preimage)

        assertArrayEquals(fields.field("root").bytes(), grant.root)
        assertEquals(fields.field("audience").text(), grant.audience.value)
        assertEquals(fields.field("granteeKey").text(), grant.grantee.wire)
        assertEquals(fields.field("client").text(), grant.client.value)
        assertEquals(fields.field("scope").list().map { it.text() }, grant.scope)
        assertEquals(fields.field("issuedAt").number(), grant.issuedAt)
        assertEquals(fields.field("expiresAt").number(), grant.expiresAt)
        assertEquals(0L, grant.generation)
        assertArrayEquals(fields.field("issuance").bytes(), grant.issuance)
        assertArrayEquals("the grant must encode back to the reference's bytes", preimage, grant.preimage)
        assertEquals(grantEntry.field("id").text(), hex(grant.id))
    }

    @Test
    fun theWireFormRoundTrips() {
        val wire = GrantWire(grantEntry.field("preimage").bytes(), grantEntry.field("signature").bytes())

        val read = GrantWire.parse(wire.text)

        assertEquals(wire, read)
        assertFalse("base64url without padding", wire.text.contains("="))
    }

    @Test
    fun eachUseDecodesAndEncodesBack() {
        val uses = Vectors["uses"].list()
        assertEquals(2, uses.size)
        val grantID = grantEntry.field("id").bytes()

        for (entry in uses) {
            val preimage = entry.field("preimage").bytes()

            val use = GrantUse.decode(preimage)

            assertArrayEquals(grantID, use.grantID)
            assertEquals(entry.field("feature").text(), use.feature)
            assertArrayEquals(entry.field("subject").bytes(), use.subject)
            assertArrayEquals(entry.field("nonce").bytes(), use.nonce)
            assertEquals(entry.field("at").number(), use.at)
            assertArrayEquals(preimage, use.preimage)
            assertEquals(entry.field("digest").text(), hex(use.digest))
        }
    }

    @Test
    fun eachRevocationDecodesAndEncodesBack() {
        val entries = Vectors["revocations"]
        val root = grantEntry.field("fields").field("root").bytes()
        val grantID = grantEntry.field("id").bytes()
        val audience = GrantClientID("rdns:city.midnight.board")
        val expected = mapOf(
            "revoke" to GrantRevocation.Revoke(root, grantID, 1_800_000_200),
            "revokeAll" to GrantRevocation.RevokeAll(root, audience, 1, 1_800_000_300),
            "retire" to GrantRevocation.Retire(root, audience, 1_800_000_400),
        )

        for ((name, revocation) in expected) {
            val preimage = entries.field(name).field("preimage").bytes()

            assertEquals(name, revocation, GrantRevocation.decode(preimage))
            assertArrayEquals(name, preimage, revocation.preimage)
        }
    }

    @Test
    fun theRequestDecodesAndEncodesBack() {
        val entry = Vectors["request"]
        val preimage = entry.field("preimage").bytes()

        val request = GrantRequest.decode(preimage)

        assertEquals("rdns:city.midnight.board", request.audience.value)
        assertEquals("self:norm-agent", request.client.value)
        assertEquals(600L, request.expiresAt - request.issuedAt)
        assertArrayEquals(preimage, request.preimage)
        assertEquals(entry.field("digest").text(), hex(request.digest))
    }

    @Test
    fun eachRefusalFailsForTheSpecsReason() {
        val refusals = Vectors["refusals"].list()
        assertEquals(14, refusals.size)

        for (entry in refusals) {
            val name = entry.field("name").text()

            val result = outcome { Grant.decode(entry.field("preimage").bytes()) }

            assertEquals(name, entry.field("error").text(), result)
        }
    }

    @Test
    fun aUseOrRequestOutsideItsBoundsIsRefused() {
        val preimage = Vectors["uses"].list().first().field("preimage").bytes()
        val request = Vectors["request"].field("preimage").bytes()

        assertRefused("malformed") { GrantUse.decode(preimage + byteArrayOf(0)) }
        assertRefused("malformed") { GrantUse.decode(preimage.copyOfRange(0, preimage.size - 1)) }
        assertRefused("unknownTag") { GrantRevocation.decode(preimage) }
        val longRequest = request.copyOfRange(0, request.size - 8) + GrantBytes.u64(1_800_000_601)
        assertRefused("malformed") { GrantRequest.decode(longRequest) }
    }

    @Test
    fun base64URLRefusesWhatItWouldNotWrite() {
        for (text in listOf("AA==", "A", "AA+A", "AA/A", "AB")) {
            assertRefused("malformed") { Base64URL.decode(text) }
        }
        val empty = runCatching { GrantWire.parse("") }.exceptionOrNull()
        assertEquals("an empty wire form has no signature", "malformed: wire size", empty?.message)
    }
}
