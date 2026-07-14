package com.midnight.kuira.sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The envelope's whole job is migration safety: every blob uploaded BEFORE the envelope
 * existed is a raw host payload and must decode exactly as-is (prefs = null) — a decode
 * that ate or reshaped a legacy Kicks match blob would corrupt restored app state.
 */
class AppStateEnvelopeTest {

    @Test
    fun `roundtrip carries prefs and host payload`() {
        val prefs = WalletBackupPrefs(dustBackupEnabled = true, dustBackupOptedOut = false)
        val host = byteArrayOf(1, 2, 3, 4, 5)

        val decoded = AppStateEnvelope.decode(AppStateEnvelope.encode(prefs, host))

        assertEquals(prefs, decoded.prefs)
        assertArrayEquals(host, decoded.hostPayload)
    }

    @Test
    fun `roundtrip preserves all pref combinations`() {
        for (enabled in listOf(true, false)) {
            for (optedOut in listOf(true, false)) {
                val prefs = WalletBackupPrefs(enabled, optedOut)
                val decoded = AppStateEnvelope.decode(AppStateEnvelope.encode(prefs, ByteArray(0)))
                assertEquals(prefs, decoded.prefs)
            }
        }
    }

    @Test
    fun `null prefs encode as prefs-absent, not defaults`() {
        val decoded = AppStateEnvelope.decode(AppStateEnvelope.encode(null, byteArrayOf(9)))
        // Absent must stay distinguishable from any real value — the restore flow treats
        // "unknown" differently from "disabled" (unknown may still prompt via the
        // identity-restored signal; a real opted-out never prompts).
        assertNull(decoded.prefs)
        assertArrayEquals(byteArrayOf(9), decoded.hostPayload)
    }

    @Test
    fun `legacy raw host payload decodes untouched with null prefs`() {
        val legacy = byteArrayOf(0x7B, 0x22, 0x76, 0x22, 0x3A, 0x31, 0x7D) // arbitrary host bytes
        val decoded = AppStateEnvelope.decode(legacy)
        assertNull(decoded.prefs)
        assertArrayEquals(legacy, decoded.hostPayload)
    }

    @Test
    fun `legacy payload that happens to start with the magic is not misparsed`() {
        // "KAS1" + bytes whose length field does NOT match the blob size → legacy.
        val unlucky = "KAS1-some-legacy-host-data".toByteArray(Charsets.US_ASCII)
        val decoded = AppStateEnvelope.decode(unlucky)
        assertNull(decoded.prefs)
        assertArrayEquals(unlucky, decoded.hostPayload)
    }

    @Test
    fun `empty and sub-header blobs decode as legacy`() {
        assertArrayEquals(ByteArray(0), AppStateEnvelope.decode(ByteArray(0)).hostPayload)
        val tiny = byteArrayOf(0x4B, 0x41, 0x53) // shorter than the header, magic prefix
        assertArrayEquals(tiny, AppStateEnvelope.decode(tiny).hostPayload)
        assertNull(AppStateEnvelope.decode(tiny).prefs)
    }

    @Test
    fun `empty host payload roundtrips (the sigil-exists sentinel)`() {
        val decoded = AppStateEnvelope.decode(
            AppStateEnvelope.encode(WalletBackupPrefs(dustBackupEnabled = false, dustBackupOptedOut = true), ByteArray(0)),
        )
        assertArrayEquals(ByteArray(0), decoded.hostPayload)
        assertEquals(WalletBackupPrefs(dustBackupEnabled = false, dustBackupOptedOut = true), decoded.prefs)
    }
}
