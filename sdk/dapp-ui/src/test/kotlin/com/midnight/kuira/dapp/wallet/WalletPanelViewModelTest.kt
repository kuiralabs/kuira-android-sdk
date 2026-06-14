package com.midnight.kuira.dapp.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.compact.proving.ProvingMode
import com.midnight.kuira.core.identity.backup.DriveAuthManager
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.testing.MainDispatcherRule
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.sdk.MidnightWallet
import com.midnight.kuira.sdk.WalletBalance
import com.midnight.kuira.sdk.walletruntime.MidnightSdkProvider
import com.midnight.kuira.sdk.walletruntime.SessionLock
import com.midnight.kuira.sdk.walletruntime.WalletConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [WalletPanelViewModel]'s UI-translation contract.
 *
 * After the SDK-ownership extraction the VM is intentionally thin:
 *  - drive [MidnightSdkProvider.ensureSdk] with the panel's config
 *  - translate [SigilRequiredException] (propagated from the provider's
 *    seed bootstrap) into [WalletStatus.SigilRequired]
 *
 * The SDK lifecycle + seed state machine (build-once / rebuild / cache /
 * sigil gate) lives in `sdk:wallet-runtime` + `sdk:wallet-seed` and is
 * tested there directly. What stays here is the panel-level translation —
 * that the action handlers convert the typed exception into the right
 * user-visible status, not a generic Error.
 */
@RunWith(RobolectricTestRunner::class)
class WalletPanelViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val sdkProvider: MidnightSdkProvider = mockk(relaxed = true)
    private val activity: FragmentActivity = mockk(relaxed = true)
    private val driveAuth: DriveAuthManager = mockk(relaxed = true)
    private val sessionLock: SessionLock = mockk(relaxed = true)

    private fun dustPrefs() =
        context.getSharedPreferences("kuira_dust_backup", Context.MODE_PRIVATE)

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
        // Same for the dust-backup opt-out prefs so each test starts opted-in.
        dustPrefs().edit().clear().commit()
        // Real (empty) SDK flow — the relaxed mock's .value returns an uncastable
        // Object, which setDustBackup's `sdk.value?.wallet` would choke on.
        every { sdkProvider.sdk } returns MutableStateFlow<MidnightSdk?>(null)
        // The VM collects sessionLock.locked in init; give it a real flow.
        every { sessionLock.locked } returns MutableStateFlow(false)
    }

    @Test
    fun `disabling dust backup persists the opt-out and makes no Drive call`() = runTest {
        val vm = newVm()

        vm.setDustBackup(enabled = false, config = devConfig(), activity = activity)

        // Persisted so the disable survives restarts / SDK rebuilds…
        assertEquals(true, dustPrefs().getBoolean("dust_backup_opted_out", false))
        // …and turning it OFF is purely local — no Drive round-trip.
        coVerify(exactly = 0) { driveAuth.authorize() }
    }

    @Test
    fun `enabling dust backup clears the opt-out and starts the Drive consent flow`() = runTest {
        dustPrefs().edit().putBoolean("dust_backup_opted_out", true).commit()
        val vm = newVm()

        vm.setDustBackup(enabled = true, config = devConfig(), activity = activity)

        assertEquals(false, dustPrefs().getBoolean("dust_backup_opted_out", true))
        coVerify { driveAuth.authorize() }
    }

    @Test
    fun `refreshBalance with no sigil emits SigilRequired status`() = runTest {
        // The seed source's contract: throw SigilRequiredException when
        // no passkey has been forged. The panel must translate that to
        // SigilRequired, NOT a generic Error — otherwise the host can't
        // render the "forge sigil first" affordance.
        coEvery { sdkProvider.ensureSdk(activity, any()) } throws SigilRequiredException()

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
        coEvery { sdkProvider.ensureSdk(activity, any()) } throws SigilRequiredException()

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
        coEvery { sdkProvider.ensureSdk(activity, any()) } throws SigilRequiredException()

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
    fun `sigil arrival fires retryRequests carrying the last requested config`() = runTest {
        // The auto-clear is half the fix — the other half is firing
        // a retry event so the host re-runs refreshBalance with the
        // user's last config. The event MUST carry the config so the
        // Compose collector doesn't re-fire with a stale captured
        // value (regression class: user toggles network between
        // hitting SigilRequired and signing in → wrong-network retry).
        coEvery { sdkProvider.ensureSdk(activity, any()) } throws SigilRequiredException()

        val store = SigilStateStore(context)
        val vm = newVmWithStore(store)
        val collectedConfigs = mutableListOf<WalletConfig>()
        // Collect on Dispatchers.Main (the rule's UnconfinedTestDispatcher
        // = eager) so the subscription is established BEFORE the emit
        // fires. Default runTest dispatcher is StandardTestDispatcher
        // which queues the launch — the emit would land before the
        // collector subscribed, and SharedFlow(replay=0) doesn't
        // replay to late subscribers.
        val collectJob = launch(kotlinx.coroutines.Dispatchers.Main) {
            vm.retryRequests.collect { collectedConfigs.add(it) }
        }

        val initialConfig = devConfig()
        vm.refreshBalance(initialConfig, activity)
        // SigilRequired now; the user's config is captured.
        store.persistSigil("did:key:z6MkTest", "cred-test", "")
        // Let the VM's snapshotFlow observer + the collector run.
        kotlinx.coroutines.yield()

        assertEquals(
            "Exactly one retry event must fire after sigil arrival",
            1,
            collectedConfigs.size,
        )
        assertEquals(
            "Retry must carry the LAST refreshBalance config — not the original-only or some other version",
            initialConfig,
            collectedConfigs.first(),
        )
        collectJob.cancel()
    }

    @Test
    fun `network toggle before sign-in makes the retry carry the NEW config`() = runTest {
        // Regression test for the config-capture bug. Sequence:
        //  1. User taps balance → refreshBalance(UNDEPLOYED) → SigilRequired
        //  2. User toggles network → Compose re-fires refreshBalance(PREPROD)
        //     (auto-bootstrap LaunchedEffect re-keys on config change)
        //  3. Still SigilRequired (sigil not forged); lastRequestedConfig
        //     is now PREPROD (latest value the user actually asked for)
        //  4. User signs in → retry should fire with PREPROD, not the
        //     stale UNDEPLOYED captured by Compose at LaunchedEffect launch
        coEvery { sdkProvider.ensureSdk(activity, any()) } throws SigilRequiredException()

        val store = SigilStateStore(context)
        val vm = newVmWithStore(store)
        val collectedConfigs = mutableListOf<WalletConfig>()
        val collectJob = launch(kotlinx.coroutines.Dispatchers.Main) {
            vm.retryRequests.collect { collectedConfigs.add(it) }
        }

        val initialConfig = WalletConfig(
            network = MidnightNetwork.UNDEPLOYED,
            provingMode = ProvingMode.DEFAULT,
        )
        val toggledConfig = WalletConfig(
            network = MidnightNetwork.PREPROD,
            provingMode = ProvingMode.DEFAULT,
        )

        vm.refreshBalance(initialConfig, activity)
        // User toggles network — auto-bootstrap LaunchedEffect re-fires.
        vm.refreshBalance(toggledConfig, activity)

        store.persistSigil("did:key:z6MkTest", "cred-test", "")
        kotlinx.coroutines.yield()

        assertEquals(1, collectedConfigs.size)
        assertEquals(
            "Retry must carry the latest config the user asked for (PREPROD), not the initial (UNDEPLOYED)",
            toggledConfig,
            collectedConfigs.first(),
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
        val collectedConfigs = mutableListOf<WalletConfig>()
        val collectJob = launch(kotlinx.coroutines.Dispatchers.Main) {
            vm.retryRequests.collect { collectedConfigs.add(it) }
        }

        store.persistSigil("did:key:z6MkTest", "cred-test", "")
        kotlinx.coroutines.yield()

        assertEquals(
            "No retry event when the user didn't previously try a wallet action",
            0,
            collectedConfigs.size,
        )
        // Status was never SigilRequired in the first place.
        assertEquals(WalletStatus.None, vm.status.value)
        collectJob.cancel()
    }

    @Test
    fun `panel auto-updates when balanceFlow emits — no manual refresh needed`() = runTest {
        // The reactive end of the auto-update fix: the SDK's background
        // subscription emits a new balance on balanceFlow → the VM's collector
        // pushes it into Ready, preserving the addresses. Without this the
        // logged balance updated but the panel didn't.
        val initial = WalletBalance(
            unshieldedNight = BigInteger.ZERO,
            shieldedNight = BigInteger.ZERO,
            dust = BigInteger.ZERO,
            dustRegistered = false,
        )
        val credited = WalletBalance(
            unshieldedNight = BigInteger.valueOf(500), // airdrop landed
            shieldedNight = BigInteger.ZERO,
            dust = BigInteger.ZERO,
            dustRegistered = false,
        )
        val live = MutableStateFlow(initial)

        val wallet = mockk<MidnightWallet>(relaxed = true)
        coEvery { wallet.balance() } returns initial
        coEvery { wallet.refresh() } returns Unit
        every { wallet.balanceFlow() } returns live

        val builtSdk = mockk<MidnightSdk>(relaxed = true)
        every { builtSdk.wallet } returns wallet
        every { builtSdk.walletAddress } returns "addr"
        every { builtSdk.shieldedWalletAddress } returns "shielded_addr"

        coEvery { sdkProvider.ensureSdk(activity, any()) } returns builtSdk

        val vm = newVm()
        vm.refreshBalance(devConfig(), activity)

        // Initial bootstrap reflects the snapshot.
        val ready0 = vm.status.value as WalletStatus.Ready
        assertEquals(initial, ready0.balance)

        // The subscription emits a credit — no manual refresh.
        live.value = credited

        val ready1 = vm.status.value as WalletStatus.Ready
        assertEquals(
            "panel must reflect balanceFlow emissions automatically",
            credited,
            ready1.balance,
        )
        assertEquals("address preserved across the live update", "addr", ready1.address)
    }

    // ── Heavy-resync throttle ──

    @Test
    fun `repeated menu visits trigger only one heavy resync within the interval`() = runTest {
        // Returning to the menu re-fires refreshBalance. The live observer keeps
        // the balance current, so the expensive wallet.refresh() must NOT run on
        // every visit — only the first (wallet just bootstrapped) within the window.
        val wallet = stubReadyWallet()

        val vm = newVm()
        vm.refreshBalance(devConfig(), activity) // bootstrap → resync
        vm.refreshBalance(devConfig(), activity) // same wallet, within window → throttled
        vm.refreshBalance(devConfig(), activity) // still throttled

        coVerify(exactly = 1) { wallet.refresh() }
    }

    @Test
    fun `explicit force bypasses the throttle`() = runTest {
        // The user tapping the "balance" button is an intentional "sync now" —
        // force=true must run a real resync even though we're inside the window.
        val wallet = stubReadyWallet()

        val vm = newVm()
        vm.refreshBalance(devConfig(), activity) // bootstrap → resync
        vm.refreshBalance(devConfig(), activity, force = true) // explicit tap → resync

        coVerify(exactly = 2) { wallet.refresh() }
    }

    @Test
    fun `a different wallet re-syncs even within the interval`() = runTest {
        // A network switch yields a new wallet address. walletChanged must force a
        // resync (and re-arm the observer on the new wallet) regardless of timing.
        val first = stubReadyWallet(address = "addr_undeployed")
        val vm = newVm()
        vm.refreshBalance(devConfig(), activity)
        coVerify(exactly = 1) { first.refresh() }

        val second = stubReadyWallet(address = "addr_preprod")
        vm.refreshBalance(devConfig(), activity)
        coVerify(exactly = 1) { second.refresh() }
    }

    // ── Helpers ──

    /**
     * Wire [sdkProvider] to return a Ready SDK whose wallet reports [balance] and
     * exposes a live [MutableStateFlow] balanceFlow. Returns the wallet mock so the
     * test can verify resync calls.
     */
    private fun stubReadyWallet(
        address: String = "addr",
        balance: WalletBalance = WalletBalance(
            unshieldedNight = BigInteger.ZERO,
            shieldedNight = BigInteger.ZERO,
            dust = BigInteger.ZERO,
            dustRegistered = false,
        ),
    ): MidnightWallet {
        val wallet = mockk<MidnightWallet>(relaxed = true)
        coEvery { wallet.balance() } returns balance
        coEvery { wallet.refresh() } returns Unit
        every { wallet.balanceFlow() } returns MutableStateFlow(balance)

        val builtSdk = mockk<MidnightSdk>(relaxed = true)
        every { builtSdk.wallet } returns wallet
        every { builtSdk.walletAddress } returns address
        every { builtSdk.shieldedWalletAddress } returns "shielded_$address"
        coEvery { sdkProvider.ensureSdk(activity, any()) } returns builtSdk
        return wallet
    }

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
        sdkProvider = sdkProvider,
        sigilStateStore = store,
        driveAuth = driveAuth,
        sessionLock = sessionLock,
        appContext = context,
        appDataProvider = java.util.Optional.empty(),
    )
}
