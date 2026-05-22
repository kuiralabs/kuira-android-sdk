package com.midnight.kuira.core.identity.sigil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the deterministic half of [Ed25519PrfSigilProvider] — the
 * (Ed25519 seed → did:key) mapping. The PRF half
 * (`PasskeyManager.authenticateWithPrf`) needs a real Credential
 * Manager so it's covered by the in-app probe + integration tests,
 * not here.
 *
 * What matters at unit-test scope: given the same 32-byte PRF
 * output, every Kuira ecosystem app produces the same DID. If this
 * mapping ever drifts, every passkey-derived sigil on every device
 * silently changes identity.
 */
class Ed25519PrfSigilProviderTest {

    private val provider = Ed25519PrfSigilProvider()

    @Test
    fun `ed25519DidFromSeed is deterministic`() {
        val seed = ByteArray(32) { (it * 11).toByte() }
        val a = provider.ed25519DidFromSeed(seed.copyOf())
        val b = provider.ed25519DidFromSeed(seed.copyOf())
        assertEquals(
            "Same PRF output must yield the same DID — cross-app/cross-device sigil portability depends on this",
            a, b,
        )
    }

    @Test
    fun `different seeds yield different dids`() {
        val a = provider.ed25519DidFromSeed(ByteArray(32) { 0x01 })
        val b = provider.ed25519DidFromSeed(ByteArray(32) { 0x02 })
        assertNotEquals(a, b)
    }

    @Test
    fun `ed25519DidFromSeed produces a z6Mk-prefix did-key`() {
        // Ed25519 multicodec (0xed01) + base58btc deterministically lands
        // on `did:key:z6Mk…`. SigilStateStore's migration detector relies
        // on this prefix to distinguish post-migration DIDs from legacy
        // P-256 ones (`did:key:zDn…`).
        val seed = ByteArray(32) { (it * 7 + 3).toByte() }
        val did = provider.ed25519DidFromSeed(seed)
        assertTrue("expected did:key:z6Mk prefix, got ${did.take(16)}", did.startsWith("did:key:z6Mk"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ed25519DidFromSeed rejects wrong-size seed`() {
        provider.ed25519DidFromSeed(ByteArray(16))
    }
}
