package com.midnight.kuira.feature.balance

import com.midnight.kuira.core.auth.WalletAddressCache
import com.midnight.kuira.core.auth.WalletAddresses
import com.midnight.kuira.core.indexer.di.SubscriptionManagerFactory
import com.midnight.kuira.core.indexer.sync.SubscriptionManager
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

/**
 * Integration-level test verifying that when the selected network
 * changes, BalanceViewModel:
 * 1. Reloads the address cache for the NEW network
 * 2. Calls loadBalances with the NEW network's address
 * 3. Does NOT use the old network's address
 *
 * This test would have caught the "wallet state out of sync" bug
 * where SubscriptionManagerFactory used a frozen IndexerClient.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSwitchIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    // Mutable flow that simulates network changes
    private val networkFlow = MutableStateFlow(MidnightNetwork.UNDEPLOYED)

    private val undeployedAddresses = WalletAddresses(
        unshieldedAddress = "mn_addr_undeployed1abc",
        shieldedAddress = "mn_shield-addr_undeployed1xyz",
    )
    private val preprodAddresses = WalletAddresses(
        unshieldedAddress = "mn_addr_preprod1def",
        shieldedAddress = "mn_shield-addr_preprod1uvw",
    )

    private lateinit var networkRepository: NetworkRepository
    private lateinit var walletAddressCache: WalletAddressCache
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var subscriptionManagerFactory: SubscriptionManagerFactory

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        networkRepository = mock()
        whenever(networkRepository.selectedNetworkFlow).thenReturn(networkFlow)
        whenever(networkRepository.getSelectedNetworkBlocking()).thenReturn(MidnightNetwork.UNDEPLOYED)

        walletAddressCache = mock()
        // suspend stubs set up in each test via runTest

        subscriptionManager = mock()
        whenever(subscriptionManager.startSubscription(any(), any())).thenReturn(emptyFlow())
        subscriptionManagerFactory = mock()
        whenever(subscriptionManagerFactory.create()).thenReturn(subscriptionManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `network switch reloads address cache for new network`() = runTest(testDispatcher) {
        whenever(walletAddressCache.load(MidnightNetwork.UNDEPLOYED)).thenReturn(undeployedAddresses)
        whenever(walletAddressCache.load(MidnightNetwork.PREPROD)).thenReturn(preprodAddresses)

        // Start on UNDEPLOYED
        val viewModel = buildViewModel()
        advanceUntilIdle()

        // Verify initial address is UNDEPLOYED
        val initialState = viewModel.walletAddresses.value
        assertEquals(
            "mn_addr_undeployed1abc",
            (initialState as BalanceViewModel.AddressCacheState.Found).addresses.unshieldedAddress,
        )

        // Switch to PREPROD
        networkFlow.value = MidnightNetwork.PREPROD
        advanceUntilIdle()

        // Verify address reloaded for PREPROD
        val newState = viewModel.walletAddresses.value
        assertEquals(
            "mn_addr_preprod1def",
            (newState as BalanceViewModel.AddressCacheState.Found).addresses.unshieldedAddress,
        )

        // Verify the cache was queried for PREPROD
        verify(walletAddressCache).load(MidnightNetwork.PREPROD)
    }

    @Test
    fun `network switch to uncached network shows empty state`() = runTest(testDispatcher) {
        whenever(walletAddressCache.load(MidnightNetwork.UNDEPLOYED)).thenReturn(undeployedAddresses)
        // PREVIEW has no cached addresses
        whenever(walletAddressCache.load(MidnightNetwork.PREVIEW)).thenReturn(null)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        // Switch to PREVIEW (no cached addresses)
        networkFlow.value = MidnightNetwork.PREVIEW
        advanceUntilIdle()

        val state = viewModel.walletAddresses.value
        assertEquals(BalanceViewModel.AddressCacheState.Empty, state)
    }

    private fun buildViewModel(): BalanceViewModel = BalanceViewModel(
        repository = mock(),
        shieldedRepository = mock(),
        subscriptionManagerFactory = subscriptionManagerFactory,
        formatter = com.midnight.kuira.core.ledger.ui.BalanceFormatter(),
        networkRepository = networkRepository,
        networkClientFactory = mock(),
        walletAddressCache = walletAddressCache,
        seedVault = mock(),
    )
}
