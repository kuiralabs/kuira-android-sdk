package com.midnight.kuira.dapp.wallet

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Send wizard's soft, mode-aware recipient check ([recipientErrorOrNull]).
 *
 * This is only the client-side hint — the SDK does the authoritative bech32m validation on submit
 * (covered by `MidnightSdkSendValidationTest`). What matters here is that each privacy mode expects
 * its OWN address family and calls out the cross-family mistake, so the wizard can't wave through a
 * shielded address for an unshielded send (or vice-versa) before the SDK rejects it. Blank input is
 * "no error yet" (the Next button stays disabled via a separate readiness check), not a red error.
 */
class SendRecipientValidationTest {

    private val unshielded = "mn_addr_test1qqqqexampleexample"
    private val shielded = "mn_shield-addr_test1qqqqexampleexample"

    @Test
    fun unshieldedMode_acceptsUnshieldedAddress() {
        assertNull(recipientErrorOrNull(unshielded, SendMode.UNSHIELDED))
    }

    @Test
    fun shieldedMode_acceptsShieldedAddress() {
        assertNull(recipientErrorOrNull(shielded, SendMode.SHIELDED))
    }

    @Test
    fun unshieldedMode_rejectsShieldedAddress() {
        val error = recipientErrorOrNull(shielded, SendMode.UNSHIELDED)
        assertTrue("expected an unshielded-address hint, got: $error", error?.contains("unshielded") == true)
    }

    @Test
    fun shieldedMode_rejectsUnshieldedAddress() {
        val error = recipientErrorOrNull(unshielded, SendMode.SHIELDED)
        assertTrue("expected a shielded-address hint, got: $error", error?.contains("shielded") == true)
    }

    @Test
    fun garbageAddress_isRejectedInBothModes() {
        assertTrue(recipientErrorOrNull("not-an-address", SendMode.UNSHIELDED) != null)
        assertTrue(recipientErrorOrNull("not-an-address", SendMode.SHIELDED) != null)
    }

    @Test
    fun blankInput_isNoErrorYet() {
        assertNull(recipientErrorOrNull("", SendMode.UNSHIELDED))
        assertNull(recipientErrorOrNull("   ", SendMode.SHIELDED))
    }
}
