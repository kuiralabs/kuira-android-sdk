package com.midnight.kuira.dapp.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.compact.proving.ProvingMode
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.testing.MainDispatcherRule
import com.midnight.kuira.sdk.walletseed.WalletSeedSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [WalletPanelViewModel]'s UI-translation contract.
 *
 * After the WalletSeedSource extraction the VM is intentionally thin:
 *  - delegate seed bootstrap to [WalletSeedSource]
 *  - translate [SigilRequiredException] thrown by the seed source into
 *    [WalletStatus.SigilRequired]
 *
 * The seed-source state machine itself (cache hit / cache miss /
 * legacy vault / dev override / sigil gate) lives in
 * `sdk:wallet-seed` and is tested there directly. What stays here
 * is the panel-level translation — that the action handlers convert
 * the typed exception into the right user-visible status, not a
 * generic Error.
 */
@RunWith(RobolectricTestRunner::class)
class WalletPanelViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val walletSeedSource: WalletSeedSource = mockk(relaxed = true)
    private val activity: FragmentActivity = mockk(relaxed = true)

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `refreshBalance with no sigil emits SigilRequired status`() = runTest {
        // The seed source's contract: throw SigilRequiredException when
        // no passkey has been forged. The panel must translate that to
        // SigilRequired, NOT a generic Error — otherwise the host can't
        // render the "forge sigil first" affordance.
        coEvery { walletSeedSource.ensureSeedReady(activity) } throws SigilRequiredException()

        val vm = newVm()
        vm.refreshBalance(devConfig(), activity)

        // UnconfinedTestDispatcher runs viewModelScope.launch eagerly,
        // so by the time refreshBalance returns the catch block has
        // fired and the terminal state is observable.
        assertEquals(
            "Action handler must translate the exception, not let it surface as a generic Error",
            WalletStatus.SigilRequired,
            vm.status.value,
        )
    }

    @Test
    fun `registerDust with no sigil emits SigilRequired status`() = runTest {
        coEvery { walletSeedSource.ensureSeedReady(activity) } throws SigilRequiredException()

        val vm = newVm()
        vm.registerDust(devConfig(), activity)

        assertEquals(
            WalletStatus.SigilRequired,
            vm.status.value,
        )
    }

    // ── Helpers ──

    private fun devConfig(): WalletConfig = WalletConfig(
        network = MidnightNetwork.UNDEPLOYED,
        provingMode = ProvingMode.DEFAULT,
    )

    private fun newVm(): WalletPanelViewModel = WalletPanelViewModel(
        context = context,
        walletSeedSource = walletSeedSource,
    )
}
