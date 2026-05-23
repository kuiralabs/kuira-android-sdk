package com.midnight.kuira.dapp.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.compact.proving.ProvingMode
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.testing.MainDispatcherRule
import com.midnight.kuira.sdk.walletseed.WalletSeedSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.launch
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
        // Robolectric reuses SharedPreferences across tests in the
        // same VM; clear so each test starts with an empty sigil state
        // (the VM's snapshotFlow observer reacts to writes during the
        // test).
        context.getSharedPreferences(SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
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

    // ── Auto-recovery when sigil arrives ──

    @Test
    fun `sigil arriving while status is SigilRequired auto-clears to None`() = runTest {
        // Reproduce the user-reported regression: user signs in via the
        // sigil panel AFTER the wallet landed on SigilRequired. The
        // wallet should not stay stuck on that status — observing
        // SigilStateStore.snapshotFlow is the seam that closes it.
        coEvery { walletSeedSource.ensureSeedReady(activity) } throws SigilRequiredException()

        val store = SigilStateStore(context)
        val vm = newVmWithStore(store)
        vm.refreshBalance(devConfig(), activity)
        assertEquals(WalletStatus.SigilRequired, vm.status.value)

        // Simulate sign-in landing — SigilSession would call this in
        // production. snapshotFlow emits; VM's observer reacts.
        store.persistSigil(
            did = "did:key:z6MkTest",
            credentialId = "cred-test",
            publicKeyHex = "",
        )

        assertEquals(
            "Sigil arrival must clear the sticky SigilRequired status",
            WalletStatus.None,
            vm.status.value,
        )
    }

    @Test
    fun `sigil arrival fires retryRequests when a config was previously requested`() = runTest {
        // The auto-clear is half the fix — the other half is firing
        // a retry event so the host re-runs refreshBalance with the
        // user's last config. Without this the wallet stays at None
        // until the user manually re-taps balance.
        coEvery { walletSeedSource.ensureSeedReady(activity) } throws SigilRequiredException()

        val store = SigilStateStore(context)
        val vm = newVmWithStore(store)
        val collectedEvents = mutableListOf<Unit>()
        // Collect on Dispatchers.Main (the rule's UnconfinedTestDispatcher
        // = eager) so the subscription is established BEFORE the emit
        // fires. Default runTest dispatcher is StandardTestDispatcher
        // which queues the launch — the emit would land before the
        // collector subscribed, and SharedFlow(replay=0) doesn't
        // replay to late subscribers.
        val collectJob = launch(kotlinx.coroutines.Dispatchers.Main) {
            vm.retryRequests.collect { collectedEvents.add(it) }
        }

        vm.refreshBalance(devConfig(), activity)
        // SigilRequired now; the user's config is captured.
        store.persistSigil("did:key:z6MkTest", "cred-test", "")
        // Let the VM's snapshotFlow observer + the collector run.
        kotlinx.coroutines.yield()

        assertEquals(
            "Exactly one retry event must fire after sigil arrival",
            1,
            collectedEvents.size,
        )
        collectJob.cancel()
    }

    @Test
    fun `sigil arrival without prior wallet action emits no retry event`() = runTest {
        // The user opens the sigil panel and signs in WITHOUT having
        // tapped any wallet action first. The wallet view model has
        // no SigilRequired status to clear and no config to retry —
        // emitting a retry would prompt biometric for a user who
        // didn't ask for one.
        val store = SigilStateStore(context)
        val vm = newVmWithStore(store)
        val collectedEvents = mutableListOf<Unit>()
        // Collect on Dispatchers.Main (the rule's UnconfinedTestDispatcher
        // = eager) so the subscription is established BEFORE the emit
        // fires. Default runTest dispatcher is StandardTestDispatcher
        // which queues the launch — the emit would land before the
        // collector subscribed, and SharedFlow(replay=0) doesn't
        // replay to late subscribers.
        val collectJob = launch(kotlinx.coroutines.Dispatchers.Main) {
            vm.retryRequests.collect { collectedEvents.add(it) }
        }

        store.persistSigil("did:key:z6MkTest", "cred-test", "")
        kotlinx.coroutines.yield()

        assertEquals(
            "No retry event when the user didn't previously try a wallet action",
            0,
            collectedEvents.size,
        )
        // Status was never SigilRequired in the first place.
        assertEquals(WalletStatus.None, vm.status.value)
        collectJob.cancel()
    }

    // ── Helpers ──

    private fun devConfig(): WalletConfig = WalletConfig(
        network = MidnightNetwork.UNDEPLOYED,
        provingMode = ProvingMode.DEFAULT,
    )

    private fun newVm(): WalletPanelViewModel = newVmWithStore(SigilStateStore(context))

    /**
     * Build the VM with a specific [SigilStateStore] instance so the
     * test can write to the same store and observe the VM's reaction.
     * Cross-VM Hilt singleton behavior is approximated by passing one
     * instance to the test and to the constructor.
     */
    private fun newVmWithStore(store: SigilStateStore): WalletPanelViewModel = WalletPanelViewModel(
        context = context,
        walletSeedSource = walletSeedSource,
        sigilStateStore = store,
    )
}
