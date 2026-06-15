package com.midnight.kuira.sdk

import com.midnight.kuira.sdk.MidnightSdk.SendResult
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Unit coverage for [MidnightSdk.validateSendRequest] — the chain-independent
 * front door of `sendNight` (#240). These are the user-facing rejections (bad
 * amount, malformed / wrong-network recipient) that must fail fast before any
 * balance, dust, signing, or consolidation work. Addresses are real localnet
 * Bech32m strings so the checksum + HRP parsing run for real.
 */
class MidnightSdkSendValidationTest {

    private val senderUndeployed =
        "mn_addr_undeployed19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqauuvtx"
    private val sameNetworkRecipient =
        "mn_addr_undeployed1t59y7jzk43vgf4hj8hpqdwh5s9ztvd5apk5jtuc4etmytmn2kvwq9vfred"
    private val wrongNetworkRecipient = // a PreProd address — different HRP
        "mn_addr_preprod19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpq88xj6h"
    private val shieldedRecipient = // shielded HRP, not a transferable unshielded target
        "mn_shield-addr_undeployed1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2czvqdfx"

    private val amount = BigInteger("1000000")

    @Test
    fun `valid same-network request passes (null)`() {
        assertNull(MidnightSdk.validateSendRequest(amount, sameNetworkRecipient, senderUndeployed))
    }

    @Test
    fun `zero amount is rejected as Failed`() {
        val result = MidnightSdk.validateSendRequest(BigInteger.ZERO, sameNetworkRecipient, senderUndeployed)
        assertTrue("zero → Failed, got $result", result is SendResult.Failed)
        assertTrue((result as SendResult.Failed).reason.contains("positive"))
    }

    @Test
    fun `negative amount is rejected as Failed`() {
        val result = MidnightSdk.validateSendRequest(BigInteger("-1"), sameNetworkRecipient, senderUndeployed)
        assertTrue("negative → Failed, got $result", result is SendResult.Failed)
    }

    @Test
    fun `malformed recipient is InvalidAddress`() {
        val result = MidnightSdk.validateSendRequest(amount, "totally-not-a-bech32-address", senderUndeployed)
        assertTrue("malformed → InvalidAddress, got $result", result is SendResult.InvalidAddress)
    }

    @Test
    fun `wrong-network recipient is InvalidAddress`() {
        val result = MidnightSdk.validateSendRequest(amount, wrongNetworkRecipient, senderUndeployed)
        assertTrue("preprod recipient on an undeployed sender → InvalidAddress, got $result", result is SendResult.InvalidAddress)
    }

    @Test
    fun `shielded recipient is InvalidAddress`() {
        val result = MidnightSdk.validateSendRequest(amount, shieldedRecipient, senderUndeployed)
        assertTrue("shielded HRP → InvalidAddress, got $result", result is SendResult.InvalidAddress)
    }
}
