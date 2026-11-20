package com.midnight.kuira.core.grant

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Sigil issuing and revoking: every grant it makes verifies under the spec's verifier, every revocation it
 * signs takes effect there, and the roster it keeps holds public fields only.
 */
class GrantIssuerTest {
    private val seed = ByteArray(64) { it.toByte() }
    private val board = GrantClientID("rdns:city.midnight.board")
    private val other = GrantClientID("rdns:example.other.board")
    private val scope = listOf("city:offer:make", "city:offer:publish", "city:offer:withdraw")
    private val now = 1_800_000_000L
    private val directory: File = Files.createTempDirectory("grant-roster").toFile()
    private val derivation = Vectors["derivation"].list()

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    /** Hands out the same array every call, as a provider holding the seed in memory would. */
    private class HeldSeed(val seed: ByteArray) : GrantSeedSource {
        override suspend fun seed(): ByteArray = seed
    }

    private fun issuer(roster: String = "roster.json", at: Long = now, seeds: GrantSeedSource = HeldSeed(seed.copyOf())) =
        GrantRoster(File(directory, roster)).let { GrantIssuer(seeds, it) { at } to it }

    private fun request(signer: GranteeSigner, audience: GrantClientID = board, client: String = "self:norm-agent"): String =
        signer.request(audience, GrantClientID(client), scope, now + 86_400, now)

    private fun vectorRoot(index: Int): String = derivation[index].field("rootKey").text()

    // ---- The root ----

    @Test
    fun theDerivationEntriesAreTheVectorsSeedAndAudiences() {
        assertEquals(3, derivation.size)
        for (entry in derivation) assertEquals(hex(seed), entry.field("seed").text())
        assertEquals(listOf(0L, 1L, 0L), derivation.map { it.field("rotation").number() })
        assertEquals(listOf(board.value, board.value, other.value), derivation.map { it.field("audience").text() })
    }

    @Test
    fun theAudienceRootMatchesTheReferenceDerivation() {
        val node = Slip10Ed25519.derive(seed, Slip10Ed25519.GRANT_ROOT_PATH)

        assertEquals(derivation[0].field("rootSecret").text(), hex(GrantRoots.secret(node, board, 0)))
        val roots = GrantRoots(seed, board)
        assertEquals(vectorRoot(0), hex(roots.key(0).publicKey))
        assertEquals(vectorRoot(1), hex(roots.key(1).publicKey))
        assertEquals(vectorRoot(2), hex(GrantRoots(seed, other).key(0).publicKey))
    }

    /** SLIP-0010 test vector 1 for ed25519, chain m/0'/1'/2'/2'/1000000000'. */
    @Test
    fun slip10MatchesItsPublishedVector() {
        val node = Slip10Ed25519.derive(checkNotNull(hexBytes("000102030405060708090a0b0c0d0e0f")), listOf(0, 1, 2, 2, 1_000_000_000))

        assertEquals("8f94d394a8e8fd6b1bc2f3f49f5c47e385281d5c17e65324b0f62483e37e8793", hex(node.key))
        assertEquals("68789923a0cac2cd5a29172a475fe9e0fb14cd6adb5ad98a3fa70333e7afa230", hex(node.chainCode))
    }

    @Test
    fun theRootNodeIsZeroedWhenTheCeremonyEnds() {
        val roots = GrantRoots(seed, board)
        roots.key(0)

        roots.wipe()

        assertArrayEquals(ByteArray(32), roots.node.key)
        assertArrayEquals(ByteArray(32), roots.node.chainCode)
    }

    /** Kotlin hands arrays by reference: wiping the issuer's copy must leave the provider's seed intact. */
    @Test
    fun theProvidersSeedSurvivesTheCeremony() = runTest {
        val held = HeldSeed(seed.copyOf())
        val (issuer, _) = issuer(seeds = held)

        issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600)
        val second = issuer.rootKey(board)

        assertArrayEquals(seed, held.seed)
        assertEquals(vectorRoot(0), hex(second))
    }

    @Test
    fun aSeedThatIsNot64BytesIsRefused() = runTest {
        val (issuer, _) = issuer(seeds = HeldSeed(ByteArray(32) { 7 }))

        assertThrowsExactly(GrantIssueRefusal.InvalidSeedLength(32)) { issuer.rootKey(board) }
    }

    // ---- Issuing ----

    @Test
    fun anIssuedGrantVerifiesAndItsUsesVerify() = runTest {
        val (issuer, _) = issuer()
        val signer = GranteeSigner.generate()

        val issued = issuer.issue(request(signer), listOf("city:offer:publish"), now + 3_600)

        val verified = GrantVerifier.grant(issued.wire, board, now + 10)
        assertEquals(vectorRoot(0), hex(verified.grant.root))
        assertEquals(signer.key, verified.grant.grantee)
        assertEquals(listOf("city:offer:publish"), verified.grant.scope)
        assertEquals(0L, verified.grant.generation)
        val subject = ByteArray(48) { 9 }
        val use = signer.use(issued.grant, "city:offer:publish", subject, now + 20)
        val checked = GrantVerifier.use(
            use, issued.wire, board,
            GrantVerifier.UseMode.Live("city:offer:publish", now + 20, InMemoryGrantReplayStore(0)),
        )
        assertArrayEquals(ByteArray(48) { 9 }, checked.use.subject)
    }

    @Test
    fun theRosterStoresOnlyPublicFields() = runTest {
        val (issuer, _) = issuer()
        val issued = issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600)

        val text = File(directory, "roster.json").readText()
        val stored = GrantJson.parse(text)
        val record = checkNotNull(stored.field("records").list().first().obj)

        assertEquals(setOf("records", "audiences", "nonces"), stored.obj?.keys)
        val publicFields = setOf(
            "id", "wire", "audience", "rotation", "root", "client", "grantee", "scope", "issuedAt", "expiresAt", "generation",
        )
        assertEquals(publicFields, record.keys)
        assertEquals(issued.wire, record["wire"]?.string)
        val secret = derivation[0].field("rootSecret").text()
        assertFalse("the root secret must never be stored", text.contains(secret))
        assertFalse("the seed must never be stored", text.contains(hex(seed)))
        val node = Slip10Ed25519.derive(seed, Slip10Ed25519.GRANT_ROOT_PATH)
        assertFalse("the root node must never be stored", text.contains(hex(node.key)) || text.contains(hex(node.chainCode)))
    }

    @Test
    fun theRosterReadsBackWhatItWrote() = runTest {
        val (issuer, roster) = issuer()
        val issued = issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600)
        issuer.revoke(hex(issued.grant.id))

        val reopened = GrantRoster(File(directory, "roster.json"))

        assertEquals(roster.records(), reopened.records())
        assertEquals(roster.state(board), reopened.state(board))
        assertEquals(mapOf(0L to vectorRoot(0)), reopened.state(board).roots)
    }

    @Test
    fun theOwnerCannotGrantMoreThanWasAsked() = runTest {
        val (issuer, _) = issuer()
        val signer = GranteeSigner.generate()
        val narrow = signer.request(board, GrantClientID("self:norm-agent"), listOf("city:offer:publish"), now + 3_600, now)

        assertThrowsExactly(GrantIssueRefusal.ScopeNotRequested("city:offer:withdraw")) {
            issuer.issue(narrow, listOf("city:offer:publish", "city:offer:withdraw"), now + 60)
        }
        assertThrowsExactly(GrantIssueRefusal.ExpiryNotAllowed) {
            issuer.issue(narrow, listOf("city:offer:publish"), now + 3_601)
        }
    }

    @Test
    fun aRequestIsGrantedOnce() = runTest {
        val (issuer, _) = issuer()
        val wire = request(GranteeSigner.generate())
        issuer.issue(wire, scope, now + 60)

        assertThrowsExactly(GrantIssueRefusal.RequestReplayed) { issuer.issue(wire, scope, now + 60) }
    }

    @Test
    fun anExpiredRequestIsRefused() = runTest {
        val (issuer, _) = issuer(at = now + 600 + 60)

        assertRefused("expired") { issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600) }
    }

    @Test
    fun aKeyOrLabelIsNeverGrantedAtTwoAudiences() = runTest {
        val (issuer, _) = issuer()
        val signer = GranteeSigner.generate()
        issuer.issue(request(signer), scope, now + 60)

        assertThrowsExactly(GrantIssueRefusal.KeyGrantedAtAnotherAudience) {
            issuer.review(request(signer, other, "self:fresh-label"))
        }
        assertThrowsExactly(GrantIssueRefusal.ClientGrantedAtAnotherAudience) {
            issuer.review(request(GranteeSigner.generate(), other))
        }
    }

    @Test
    fun aClientWithALiveGrantGetsNoSecondKey() = runTest {
        val (issuer, _) = issuer()
        issuer.issue(request(GranteeSigner.generate()), scope, now + 60)

        assertThrowsExactly(GrantIssueRefusal.ClientHasAnotherKey) { issuer.review(request(GranteeSigner.generate())) }
    }

    // ---- Revoking ----

    @Test
    fun aRevokedGrantIsRefusedWhereTheListIsRead() = runTest {
        val (issuer, roster) = issuer()
        val issued = issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600)

        val revocation = issuer.revoke(hex(issued.grant.id))

        val list = GrantRevocationList(issued.grant.root, listOf(revocation), now)
        assertRefused("revoked") { GrantVerifier.grant(issued.wire, board, now, list) }
        assertEquals(now, roster.records().first().revokedAt)
    }

    @Test
    fun revokingAGrantTheRosterDoesNotHoldIsRefused() = runTest {
        val (issuer, _) = issuer()

        assertThrowsExactly(GrantIssueRefusal.UnknownGrant) { issuer.revoke("ab".repeat(32)) }
    }

    @Test
    fun revokeAllRaisesTheGenerationAndTheNextGrantSurvivesIt() = runTest {
        val (issuer, _) = issuer()
        val first = issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600)

        val revocation = issuer.revokeAll(board)
        val second = issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600)

        val list = GrantRevocationList(first.grant.root, listOf(revocation), now)
        assertRefused("revoked") { GrantVerifier.grant(first.wire, board, now, list) }
        assertEquals(1L, second.grant.generation)
        assertRefused("valid") { GrantVerifier.grant(second.wire, board, now, list) }
    }

    @Test
    fun aRetiredRootIsReplacedByTheNextRotation() = runTest {
        val (issuer, _) = issuer()
        val first = issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600)

        val retirement = issuer.retire(board)
        val second = issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600)

        val list = GrantRevocationList(first.grant.root, listOf(retirement), now)
        assertRefused("retired") { GrantVerifier.grant(first.wire, board, now, list) }
        assertEquals(vectorRoot(1), hex(second.grant.root))
    }

    /** A restored device has an empty roster; the audience's lists move it past what the first device did. */
    @Test
    fun aRestoredDeviceIssuesPastARetiredRootAndARevokedGeneration() = runTest {
        val (first, _) = issuer(roster = "first.json")
        first.issue(request(GranteeSigner.generate()), scope, now + 3_600)
        val retirement = first.retire(board)
        val revokeAll = first.revokeAll(board)
        val published = PublishedRevocations(mapOf(vectorRoot(0) to listOf(retirement), vectorRoot(1) to listOf(revokeAll)), now)
        val (restored, _) = issuer(roster = "restored.json")

        val wire = request(GranteeSigner.generate(), client = "self:other-agent")
        val issued = restored.issue(wire, scope, now + 3_600, published)

        assertEquals(vectorRoot(1), hex(issued.grant.root))
        assertEquals(1L, issued.grant.generation)
        val list = published.list(issued.grant.root)
        assertRefused("valid") { GrantVerifier.grant(issued.wire, board, now, list) }
    }

    @Test
    fun unlinkingRevokesEveryAudience() = runTest {
        val (issuer, roster) = issuer()
        issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600)
        issuer.issue(request(GranteeSigner.generate(), other, "self:other-label"), scope, now + 3_600)

        val wires = issuer.revokeEverything()

        assertEquals(setOf(board.value, other.value), wires.keys)
        assertTrue(roster.records().all { it.revokedAt == now })
        for (wire in wires.values) {
            val revocation = GrantVerifier.revocation(wire)
            assertTrue("a revoke-all at generation 1", revocation is GrantRevocation.RevokeAll && revocation.generation == 1L)
        }
    }

    @Test
    fun aGrantTheAudienceReportsThatThisDeviceNeverIssuedIsFlagged() = runTest {
        val (issuer, roster) = issuer()
        val issued = issuer.issue(request(GranteeSigner.generate()), scope, now + 3_600)
        val stranger = "ab".repeat(32)

        val unknown = roster.unknown(board, listOf(hex(issued.grant.id), stranger))

        assertEquals(listOf(stranger), unknown)
    }

    // ---- The grantee ----

    /** RFC 6979 nonces make the grantee's signature the reference's own bytes. */
    @Test
    fun theGranteeSignsTheReferenceBytes() {
        val grantee = Vectors["grantee"]
        val ecdsa = Vectors["ecdsa"]
        val secret = grantee.field("secret").bytes()

        val signer = GranteeSigner(secret)

        assertEquals(grantee.field("key").text(), signer.key.wire)
        assertEquals(ecdsa.field("lowS").text(), hex(GrantCurves.k1Sign(secret, ecdsa.field("digest").bytes())))
    }

    /** An audience's published lists, as its `.well-known` path would serve them: real signed revocations. */
    private class PublishedRevocations(private val lists: Map<String, List<String>>, private val readAt: Long) :
        GrantRevocationSource {
        override suspend fun list(root: ByteArray): GrantRevocationList =
            GrantRevocationList(root, lists[hex(root)] ?: emptyList(), readAt)
    }
}
