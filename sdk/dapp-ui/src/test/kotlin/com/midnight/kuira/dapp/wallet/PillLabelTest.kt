package com.midnight.kuira.dapp.wallet

import com.midnight.kuira.core.ledger.ui.BalanceFormatter
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.WalletBalance
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/**
 * Unit tests for the wallet pill's label formatter.
 *
 * The pill is the most-visible artifact of the panel — every host app
 * surfaces it on its main screen. The label is a pure function of (status,
 * network, balance), so it locks down nicely with table-driven tests; the
 * harder UI states (sheet, receive screen) are covered by androidTest.
 *
 * Conventions exercised here:
 *  - Network prefix is the asymmetric, lower-case short name (`localnet`,
 *    `preview`, `preprod`) — never the full enum constant or "UNDEPLOYED".
 *  - NIGHT is abbreviated via [BalanceFormatter.formatAbbreviated] (K/M/B/T
 *    suffixes, integer below 1K). DUST gets a "D" suffix.
 *  - Unshielded NIGHT always takes its own slot; shielded NIGHT gets a
 *    second slot prefixed with `🛡` **only when non-zero** — so users
 *    without any shielded balance don't pay UI tax for it.
 *  - `dustRegistered = true` adds a trailing `✓`.
 *  - None / Loading / Error states still carry the network prefix so the
 *    user knows which chain the failure is on.
 */
class PillLabelTest {

    private val formatter = BalanceFormatter()

    // ── Network prefix ──

    @Test
    fun `network pillName — UNDEPLOYED is localnet`() {
        assertEquals("localnet", MidnightNetwork.UNDEPLOYED.pillName)
    }

    @Test
    fun `network pillName — PREVIEW is preview`() {
        assertEquals("preview", MidnightNetwork.PREVIEW.pillName)
    }

    @Test
    fun `network pillName — PREPROD is preprod`() {
        assertEquals("preprod", MidnightNetwork.PREPROD.pillName)
    }

    // ── None state ──

    @Test
    fun `none — shows network prefix and 'wallet' placeholder`() {
        val label = pillLabel(WalletStatus.None, MidnightNetwork.UNDEPLOYED, formatter)
        assertEquals("localnet · wallet", label)
    }

    @Test
    fun `none on preprod — prefix updates`() {
        val label = pillLabel(WalletStatus.None, MidnightNetwork.PREPROD, formatter)
        assertEquals("preprod · wallet", label)
    }

    // ── Loading state ──

    @Test
    fun `loading — network prefix plus loading marker`() {
        val label = pillLabel(WalletStatus.Loading("Bootstrapping"), MidnightNetwork.PREVIEW, formatter)
        assertEquals("preview · loading…", label)
    }

    // ── Error state ──

    @Test
    fun `error — network prefix plus error marker`() {
        val label = pillLabel(
            WalletStatus.Error("Connection refused"),
            MidnightNetwork.UNDEPLOYED,
            formatter,
        )
        assertEquals("localnet · error", label)
    }

    // ── Ready: unshielded-only ──

    @Test
    fun `ready — fresh wallet, no shield glyph, no checkmark`() {
        val label = pillLabel(
            ready(unshieldedNight = nightOf(10_000), dust = BigInteger.ZERO, dustRegistered = false),
            MidnightNetwork.UNDEPLOYED,
            formatter,
        )
        // 10_000 NIGHT abbreviates to "10K"; dust 0 stays "0D"; no ✓ yet.
        assertEquals("localnet · 10K · 0D", label)
    }

    @Test
    fun `ready — funded with dust registered, shows checkmark`() {
        val label = pillLabel(
            ready(
                unshieldedNight = nightOf(10_000),
                dust = dustOf(364),
                dustRegistered = true,
            ),
            MidnightNetwork.UNDEPLOYED,
            formatter,
        )
        assertEquals("localnet · 10K · 364D · ✓", label)
    }

    // ── Ready: shielded glyph ──

    @Test
    fun `ready — shielded portion shows as a separate slot, not aggregated`() {
        val label = pillLabel(
            ready(
                unshieldedNight = nightOf(10_000),
                shieldedNight = nightOf(2_500),
                dust = dustOf(364),
                dustRegistered = true,
            ),
            MidnightNetwork.UNDEPLOYED,
            formatter,
        )
        // Unshielded ("10K") stays as the primary slot; shielded ("2.5K") gets
        // its own slot prefixed with the shield glyph. User can see at a
        // glance how much of their NIGHT is private without doing arithmetic.
        assertEquals("localnet · 10K · 🛡 2.5K · 364D · ✓", label)
    }

    @Test
    fun `ready — shielded zero does NOT add shield glyph`() {
        val label = pillLabel(
            ready(
                unshieldedNight = nightOf(10_000),
                shieldedNight = BigInteger.ZERO,
                dust = dustOf(364),
                dustRegistered = true,
            ),
            MidnightNetwork.PREPROD,
            formatter,
        )
        assertEquals("preprod · 10K · 364D · ✓", label)
    }

    // ── Ready: K/M/T abbreviation cascade ──

    @Test
    fun `ready — multi-million NIGHT abbreviates with M`() {
        val label = pillLabel(
            ready(
                unshieldedNight = nightOf(2_300_000),
                dust = dustOf(1_500),
                dustRegistered = true,
            ),
            MidnightNetwork.PREVIEW,
            formatter,
        )
        // 2.3M NIGHT, 1.5K DUST.
        assertEquals("preview · 2.3M · 1.5KD · ✓", label)
    }

    // ── Helpers ──

    /** 1 NIGHT = 10^6 base units. */
    private fun nightOf(whole: Long): BigInteger =
        BigInteger.valueOf(whole) * BigInteger.TEN.pow(6)

    /** 1 DUST = 10^15 base units (Specks). */
    private fun dustOf(whole: Long): BigInteger =
        BigInteger.valueOf(whole) * BigInteger.TEN.pow(15)

    private fun ready(
        unshieldedNight: BigInteger = BigInteger.ZERO,
        shieldedNight: BigInteger = BigInteger.ZERO,
        dust: BigInteger = BigInteger.ZERO,
        dustRegistered: Boolean = false,
        address: String = "mn_addr_undeployed1test",
        shieldedAddress: String = "mn_shield-addr_undeployed1test",
    ) = WalletStatus.Ready(
        address = address,
        shieldedAddress = shieldedAddress,
        balance = WalletBalance(
            unshieldedNight = unshieldedNight,
            shieldedNight = shieldedNight,
            dust = dust,
            dustRegistered = dustRegistered,
        ),
    )
}
