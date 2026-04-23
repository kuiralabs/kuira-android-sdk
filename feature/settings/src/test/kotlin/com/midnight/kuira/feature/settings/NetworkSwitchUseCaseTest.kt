package com.midnight.kuira.feature.settings

import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletAddressCache
import com.midnight.kuira.core.auth.WalletAddresses
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NetworkSwitchUseCaseTest {

    private lateinit var networkRepository: NetworkRepository
    private lateinit var walletAddressCache: WalletAddressCache
    private lateinit var seedVault: SeedVault
    private lateinit var useCase: NetworkSwitchUseCase

    @Before
    fun setup() {
        networkRepository = mock()
        walletAddressCache = mock()
        seedVault = mock()
        useCase = NetworkSwitchUseCase(networkRepository, walletAddressCache, seedVault)
    }

    @Test
    fun `cached network switches without biometric`() = runTest {
        val cached = WalletAddresses(
            unshieldedAddress = "mn_addr_preprod1abc",
            shieldedAddress = "mn_shield-addr_preprod1xyz",
        )
        whenever(walletAddressCache.load(MidnightNetwork.PREPROD)).thenReturn(cached)

        val result = useCase.execute(MidnightNetwork.PREPROD, activity = null)

        assertTrue(result.isSuccess)
        verify(networkRepository).setSelectedNetwork(MidnightNetwork.PREPROD)
        verify(seedVault, never()).loadSeed(any())
    }

    @Test
    fun `uncached network without activity fails`() = runTest {
        whenever(walletAddressCache.load(MidnightNetwork.PREVIEW)).thenReturn(null)

        val result = useCase.execute(MidnightNetwork.PREVIEW, activity = null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        verify(networkRepository, never()).setSelectedNetwork(any())
    }
}
