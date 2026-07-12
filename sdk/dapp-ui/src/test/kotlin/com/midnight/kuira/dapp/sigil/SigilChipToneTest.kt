package com.midnight.kuira.dapp.sigil

import com.midnight.kuira.dapp.wallet.SigilChipUi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the sigil chip's status-dot color to the actual identity state.
 *
 * Regression guard for the "green while disconnected" bug: the floating chip's
 * status dot (and Card/Panel status text) was hardcoded to the reserved green
 * regardless of state, so an absent / initializing / failed sigil still read as
 * "protected". The dot color now derives from [SigilChipUi.tone], so only a
 * forged, active identity may be [SigilChipUi.Tone.Protected]. These tests fail
 * against the pre-fix mapping (every state → Protected) and pass after it.
 */
class SigilChipToneTest {

    @Test
    fun `forged is the only protected (green) tone`() {
        assertEquals(
            SigilChipUi.Tone.Protected,
            SigilStatus.Forged(did = "did:key:zTest", credentialId = "c", publicKeyHex = "ab").toSigilChipUi().tone,
        )
    }

    @Test
    fun `error maps to the error tone`() {
        assertEquals(SigilChipUi.Tone.Error, SigilStatus.Error("boom").toSigilChipUi().tone)
    }

    @Test
    fun `states without an active sigil are never protected`() {
        // The heart of the bug: none of these may signal "protected" (green).
        val nonActive = listOf(
            SigilStatus.None,
            SigilStatus.BackupAvailable,
            SigilStatus.Creating(stage = "forging"),
            SigilStatus.Initializing,
        )
        nonActive.forEach { status ->
            val tone = status.toSigilChipUi().tone
            assertEquals("$status must be neutral, not protected", SigilChipUi.Tone.Neutral, tone)
        }
    }
}
