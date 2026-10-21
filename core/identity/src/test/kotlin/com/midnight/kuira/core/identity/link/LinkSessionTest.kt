package com.midnight.kuira.core.identity.link

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ceremony's ORDER, which [DeviceLinkTest] deliberately does not cover: the bytes can be
 * correct while the sequence is wrong, and the sequence is what makes the verification code worth
 * having. A session that decrypted before showing the code would pass every crypto test here.
 */
class LinkSessionTest {

    private val entropy = ByteArray(32) { 0xA1.toByte() }

    /** Runs a source session far enough to hand back the response QR the new device must scan. */
    private suspend fun sourceResponseQr(requestQr: String, seed: ByteArray = entropy): String {
        val source = SourceLinkSession { seed }
        source.scan(requestQr)
        source.approve()
        return (source.state.value as SourceLinkSession.State.ShowingResponse).qrText
    }

    // MARK: the new device

    @Test
    fun `the new device starts by showing a code it can hand to the other phone`() {
        val session = NewDeviceLinkSession { }
        assertEquals(NewDeviceLinkSession.State.ShowingRequest, session.state.value)
        // Decodes as a request, so the other phone's scanner will accept it.
        LinkRequest.fromQrText(session.requestQrText)
    }

    @Test
    fun `showing and scanning are separate steps because one phone cannot do both at once`() {
        val session = NewDeviceLinkSession { }
        session.readyToScan()
        assertEquals(NewDeviceLinkSession.State.ScanningResponse, session.state.value)
        // And back, for someone who tapped on before the other phone was ready.
        session.showRequestAgain()
        assertEquals(NewDeviceLinkSession.State.ShowingRequest, session.state.value)
    }

    @Test
    fun `stray codes are rejected without ending the ceremony`() = runTest {
        val session = NewDeviceLinkSession { }
        session.readyToScan()
        // A camera pointed at a phone reads plenty of things on the way to the right one.
        assertThrows(LinkException::class.java) { session.scan("not a kuira code") }
        assertEquals(
            NewDeviceLinkSession.State.ScanningResponse,
            session.state.value,
            )
    }

    /**
     * THE ORDERING GUARANTEE. The code has to be showable before anything is opened, because it is
     * the only check that catches a substituted ciphertext — which, per
     * `a substituted response opens cleanly and is caught only by the code`, decrypts perfectly
     * into an attacker's wallet.
     */
    @Test
    fun `the code is shown before anything is decrypted or delivered`() = runTest {
        var delivered = false
        val session = NewDeviceLinkSession { delivered = true }
        session.readyToScan()
        session.scan(sourceResponseQr(session.requestQrText))

        val state = session.state.value
        assertTrue("expected confirmation, got $state", state is NewDeviceLinkSession.State.AwaitingConfirmation)
        assertTrue("nothing may be delivered before the person confirms", !delivered)
    }

    @Test
    fun `both phones compute the same six digits`() = runTest {
        val session = NewDeviceLinkSession { }
        session.readyToScan()
        val requestQr = session.requestQrText

        val source = SourceLinkSession { entropy }
        source.scan(requestQr)
        source.approve()
        val shown = source.state.value as SourceLinkSession.State.ShowingResponse

        session.scan(shown.qrText)
        val confirming = session.state.value as NewDeviceLinkSession.State.AwaitingConfirmation
        assertEquals(shown.code, confirming.code)
        assertEquals(6, confirming.code.length)
    }

    @Test
    fun `confirming delivers exactly the entropy the source holds`() = runTest {
        var received: ByteArray? = null
        val session = NewDeviceLinkSession { received = it.copyOf() }
        session.readyToScan()
        session.scan(sourceResponseQr(session.requestQrText))
        session.confirm()

        assertEquals(NewDeviceLinkSession.State.Linked, session.state.value)
        assertArrayEquals(entropy, received)
    }

    /**
     * Two taps on a button that has not visibly responded is ordinary on a phone. A second pass
     * would open the payload again and write the seed to the keystore twice.
     */
    @Test
    fun `confirming twice delivers once`() = runTest {
        var deliveries = 0
        val session = NewDeviceLinkSession { deliveries++ }
        session.readyToScan()
        session.scan(sourceResponseQr(session.requestQrText))
        session.confirm()
        session.confirm()
        assertEquals(1, deliveries)
    }

    /**
     * The ceremony was correct and this phone's storage was not. Reporting a crypto failure would
     * send someone back to the other device over a keystore problem.
     */
    @Test
    fun `storage refusing is not reported as a broken seal`() = runTest {
        val session = NewDeviceLinkSession { throw IllegalStateException("keystore locked") }
        session.readyToScan()
        session.scan(sourceResponseQr(session.requestQrText))
        session.confirm()

        val state = session.state.value as NewDeviceLinkSession.State.Failed
        assertTrue("expected StorageFailed, got ${state.cause}", state.cause is LinkException.StorageFailed)
    }

    @Test
    fun `declining the codes stores nothing and does not read as a failure`() = runTest {
        var delivered = false
        val session = NewDeviceLinkSession { delivered = true }
        session.readyToScan()
        session.scan(sourceResponseQr(session.requestQrText))
        session.abort()

        assertEquals(NewDeviceLinkSession.State.Cancelled, session.state.value)
        assertTrue(!delivered)
        // Cancelled is terminal: a later confirm must not resurrect the ceremony.
        session.confirm()
        assertEquals(NewDeviceLinkSession.State.Cancelled, session.state.value)
    }

    // MARK: the source device

    /**
     * Reading the seed is what raises the biometric prompt, so reading it before the person has
     * approved would ask for a fingerprint before saying what it is for — and would put the
     * wallet's entropy in memory for every stray QR the camera happens to catch.
     */
    @Test
    fun `the seed is not read until the copy is approved`() = runTest {
        var reads = 0
        val session = SourceLinkSession { reads++; entropy }
        session.scan(NewDeviceLinkSession { }.requestQrText)

        assertEquals(SourceLinkSession.State.AwaitingApproval, session.state.value)
        assertEquals("scanning must not touch the seed", 0, reads)

        session.approve()
        assertEquals(1, reads)
    }

    @Test
    fun `approving twice reads the seed once`() = runTest {
        var reads = 0
        val session = SourceLinkSession { reads++; entropy }
        session.scan(NewDeviceLinkSession { }.requestQrText)
        session.approve()
        session.approve()
        assertEquals(1, reads)
    }

    /** A declined prompt is a choice, not a fault, and must not read as the crypto having broken. */
    @Test
    fun `a declined biometric prompt is reported as the seed being unavailable`() = runTest {
        val session = SourceLinkSession { throw SecurityException("user cancelled") }
        session.scan(NewDeviceLinkSession { }.requestQrText)
        session.approve()

        val state = session.state.value as SourceLinkSession.State.Failed
        assertTrue("expected SeedUnavailable, got ${state.cause}", state.cause is LinkException.SeedUnavailable)
    }

    @Test
    fun `declining the copy reads nothing and seals nothing`() = runTest {
        var reads = 0
        val session = SourceLinkSession { reads++; entropy }
        session.scan(NewDeviceLinkSession { }.requestQrText)
        session.abort()

        assertEquals(SourceLinkSession.State.Cancelled, session.state.value)
        session.approve()
        assertEquals(SourceLinkSession.State.Cancelled, session.state.value)
        assertEquals(0, reads)
    }

    @Test
    fun `stray codes do not move the source device off scanning`() {
        val session = SourceLinkSession { entropy }
        assertThrows(LinkException::class.java) { session.scan("https://example.com") }
        assertEquals(SourceLinkSession.State.Scanning, session.state.value)
    }

    /**
     * Each ceremony gets a fresh ephemeral key, so a response captured from one cannot be replayed
     * into another — the sessions are single-use by construction rather than by convention.
     */
    @Test
    fun `a response from one ceremony does not open in another`() = runTest {
        val first = NewDeviceLinkSession { }
        first.readyToScan()
        val responseForFirst = sourceResponseQr(first.requestQrText)

        val second = NewDeviceLinkSession { }
        second.readyToScan()
        second.scan(responseForFirst)

        // It parses and reaches confirmation — but with a code that cannot match the other phone,
        // which is exactly the substitution the check exists to catch.
        val secondCode = (second.state.value as NewDeviceLinkSession.State.AwaitingConfirmation).code
        first.scan(responseForFirst)
        val firstCode = (first.state.value as NewDeviceLinkSession.State.AwaitingConfirmation).code
        assertNotEquals(firstCode, secondCode)

        // And confirming anyway fails, because the payload was sealed to the other ephemeral key.
        second.confirm()
        assertTrue(second.state.value is NewDeviceLinkSession.State.Failed)
    }
}
