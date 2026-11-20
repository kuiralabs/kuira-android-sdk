package com.midnight.kuira.core.grant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 7 of the grant spec over the shared vectors: what verifies, and each refusal for the spec's reason,
 * including the curve edge cases where libraries disagree.
 */
class GrantVerifierVectorTest {
    private val audience = GrantClientID("rdns:city.midnight.board")
    private val grantEntry = Vectors["grant"]
    private val grantPreimage = grantEntry.field("preimage").bytes()
    private val grantSignature = grantEntry.field("signature").bytes()
    private val grantWire = GrantWire(grantPreimage, grantSignature).text
    private val issuedAt = grantEntry.field("fields").field("issuedAt").number()
    private val expiresAt = grantEntry.field("fields").field("expiresAt").number()
    private val root = grantPreimage.copyOfRange(34, 66)

    private fun wire(entry: GrantJson): String = GrantWire(entry.field("preimage").bytes(), entry.field("signature").bytes()).text

    private fun use(index: Int): Pair<String, Long> {
        val entry = Vectors["uses"].list()[index]
        return wire(entry) to entry.field("at").number()
    }

    private fun revocationWire(name: String): String = wire(Vectors["revocations"].field(name))

    // ---- Grants ----

    @Test
    fun theGrantVerifiesOfflineAtItsAudience() {
        val verified = GrantVerifier.grant(grantWire, audience, issuedAt + 100)

        assertEquals(grantEntry.field("id").text(), hex(verified.id))
        assertEquals("no list was consulted, so the result must say so", GrantCheck.Offline, verified.check)
    }

    @Test
    fun theGrantIsRefusedAtAnotherAudience() {
        assertRefused("wrongAudience") {
            GrantVerifier.grant(grantWire, GrantClientID("rdns:example.other.board"), issuedAt)
        }
    }

    @Test
    fun theTimeWindowAllowsSixtySecondsOfSkewAndNoMore() {
        assertRefused("valid") { GrantVerifier.grant(grantWire, audience, issuedAt - 60) }
        assertRefused("notYetValid") { GrantVerifier.grant(grantWire, audience, issuedAt - 61) }
        assertRefused("valid") { GrantVerifier.grant(grantWire, audience, expiresAt + 59) }
        assertRefused("expired") { GrantVerifier.grant(grantWire, audience, expiresAt + 60) }
        assertRefused("timeOutOfRange") { GrantVerifier.grant(grantWire, audience, 1L shl 40) }
    }

    @Test
    fun aChangedByteBreaksTheRootSignature() {
        val preimage = grantPreimage.copyOf()
        preimage[preimage.size - 1] = (preimage[preimage.size - 1].toInt() xor 1).toByte()

        assertRefused("badSignature") {
            GrantVerifier.grant(GrantWire(preimage, grantSignature).text, audience, issuedAt)
        }
    }

    /** `S + L` verifies in a library that reduces `S`; the spec refuses it. */
    @Test
    fun aNonCanonicalSIsRefused() {
        val signature = Vectors["rootKeyRefusals"].field("nonCanonicalS").bytes()

        assertRefused("badSignature") {
            GrantVerifier.grant(GrantWire(grantPreimage, signature).text, audience, issuedAt)
        }
    }

    /** Every small-order key, both `x = 0` encodings with the sign bit, and a key with a torsion part. */
    @Test
    fun everyRootKeyTheSpecRefusesIsRefused() {
        val refusals = Vectors["rootKeyRefusals"]
        val keys = refusals.field("smallOrder").list().map { it.text() } +
            refusals.field("signBitWithZeroX").list().map { it.text() } + refusals.field("torsioned").text()
        assertEquals(11, keys.size)
        assertTrue("the vectors' own root is valid", GrantCurves.ed25519KeyIsValid(root))

        for (key in keys) {
            assertFalse(key, GrantCurves.ed25519KeyIsValid(checkNotNull(hexBytes(key))))
        }
    }

    /** The identity key with `R = O, S = 0` verifies any message in a library that admits it. */
    @Test
    fun theIdentityKeyForgeryIsRefused() {
        val identity = ByteArray(32).also { it[0] = 1 }
        val preimage = grantPreimage.copyOf()
        System.arraycopy(identity, 0, preimage, 34, 32)

        assertRefused("badRootKey") {
            GrantVerifier.grant(GrantWire(preimage, identity + ByteArray(32)).text, audience, issuedAt)
        }
    }

    // ---- Uses ----

    @Test
    fun theLiveUseVerifiesOnceAndIsThenAReplay() = runTest {
        val (publish, at) = use(0)
        val mode = GrantVerifier.UseMode.Live("city:offer:publish", at + 30, InMemoryGrantReplayStore(0))

        val verified = GrantVerifier.use(publish, grantWire, audience, mode)

        assertEquals("city:offer:publish", verified.use.feature)
        assertEquals(at, verified.use.at)
        assertRefused("replayed") { GrantVerifier.use(publish, grantWire, audience, mode) }
    }

    @Test
    fun aLiveUseOutsideSixtySecondsOfNowIsStale() = runTest {
        val (publish, at) = use(0)

        assertRefused("staleUse") {
            GrantVerifier.use(
                publish, grantWire, audience,
                GrantVerifier.UseMode.Live("city:offer:publish", at + 61, InMemoryGrantReplayStore(0)),
            )
        }
    }

    /** After losing its store, a verifier cannot know which nonces it saw in the last minute. */
    @Test
    fun aStoreThatStartedAfterTheUseRefusesIt() = runTest {
        val (publish, at) = use(0)

        assertRefused("staleUse") {
            GrantVerifier.use(
                publish, grantWire, audience,
                GrantVerifier.UseMode.Live("city:offer:publish", at, InMemoryGrantReplayStore(at - 59)),
            )
        }
    }

    @Test
    fun aRecordUseVerifiesAfterItsGrantExpired() = runTest {
        val (make, _) = use(1)

        val verified = GrantVerifier.use(make, grantWire, audience, GrantVerifier.UseMode.Record("city:offer:make"))

        assertEquals("city:offer:make", verified.use.feature)
        assertEquals(GrantCheck.Offline, verified.grant.check)
    }

    @Test
    fun aUseOfAnotherFeatureThanExpectedIsRefused() = runTest {
        val (make, _) = use(1)

        assertRefused("featureUnknown") {
            GrantVerifier.use(make, grantWire, audience, GrantVerifier.UseMode.Record("city:offer:publish"))
        }
    }

    /** Both `s` and `n − s` verify; the spec accepts high `s`, so a replay check never keys on signature bytes. */
    @Test
    fun highAndLowSBothVerify() {
        val ecdsa = Vectors["ecdsa"]
        val key = Vectors["grantee"].field("key").bytes()
        val digest = ecdsa.field("digest").bytes()

        assertEquals(GrantSignatureCheck.VALID, GrantCurves.k1Verify(key, digest, ecdsa.field("lowS").bytes()))
        assertEquals(GrantSignatureCheck.VALID, GrantCurves.k1Verify(key, digest, ecdsa.field("highS").bytes()))
    }

    /** `r` or `s` equal to `n`, or zero, is refused rather than reduced. */
    @Test
    fun scalarsOutsideOneToNMinusOneAreRefused() {
        val ecdsa = Vectors["ecdsa"]
        val key = Vectors["grantee"].field("key").bytes()
        val digest = ecdsa.field("digest").bytes()
        val low = ecdsa.field("lowS").bytes()
        val n = ecdsa.field("n").bytes()
        val zero = ByteArray(32)
        val r = low.copyOfRange(0, 32)
        val s = low.copyOfRange(32, 64)

        for (signature in listOf(n + s, r + n, zero + s, r + zero)) {
            assertEquals(GrantSignatureCheck.BAD_SIGNATURE, GrantCurves.k1Verify(key, digest, signature))
        }
    }

    @Test
    fun aGranteeKeyOffTheCurveIsRefused() {
        val request = Vectors["request"]
        val preimage = request.field("preimage").bytes()
        val keyStart = 32 + 1 + 2 + "rdns:city.midnight.board".length + 2
        preimage[keyStart] = (preimage[keyStart].toInt() xor 1).toByte()

        assertRefused("badGranteeKey") {
            GrantVerifier.request(GrantWire(preimage, request.field("signature").bytes()).text, issuedAt)
        }
    }

    // ---- Revocations ----

    @Test
    fun eachRevocationVerifiesAndAppliesToTheGrant() {
        val expected = listOf("revoke" to "revoked", "revokeAll" to "revoked", "retire" to "retired")

        for ((name, refusal) in expected) {
            val list = GrantRevocationList(root, listOf(revocationWire(name)), issuedAt + 500)

            assertEquals(name, refusal, outcome { GrantVerifier.grant(grantWire, audience, issuedAt + 600, list) })
        }
    }

    @Test
    fun anEmptyListSaysItWasConsulted() {
        val list = GrantRevocationList.fromJson(root, """{"revocations": []}""", issuedAt + 500)

        val verified = GrantVerifier.grant(grantWire, audience, issuedAt + 600, list)

        assertEquals(GrantCheck.WithRevocations(issuedAt + 500), verified.check)
    }

    @Test
    fun aRevocationWithABrokenSignatureIsRefused() {
        val entry = Vectors["revocations"].field("revoke")
        val signature = entry.field("signature").bytes()
        signature[0] = (signature[0].toInt() xor 1).toByte()

        assertRefused("badSignature") {
            GrantVerifier.revocation(GrantWire(entry.field("preimage").bytes(), signature).text)
        }
    }

    // ---- Requests ----

    @Test
    fun theRequestVerifiesWithinItsWindowAndNotAfter() {
        val request = wire(Vectors["request"])

        assertEquals("self:norm-agent", GrantVerifier.request(request, issuedAt + 10).client.value)
        assertRefused("expired") { GrantVerifier.request(request, issuedAt + 600 + 60) }
    }
}
