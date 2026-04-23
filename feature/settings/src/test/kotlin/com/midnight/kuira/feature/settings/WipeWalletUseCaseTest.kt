package com.midnight.kuira.feature.settings

import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletAddressCache
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions

class WipeWalletUseCaseTest {

    private lateinit var seedVault: SeedVault
    private lateinit var walletAddressCache: WalletAddressCache
    private lateinit var useCase: WipeWalletUseCase

    @Before
    fun setup() {
        seedVault = mock()
        walletAddressCache = mock()
        useCase = WipeWalletUseCase(seedVault, walletAddressCache)
    }

    @Test
    fun `execute deletes seed and clears address cache`() = runTest {
        useCase.execute()

        verify(seedVault).deleteSeed()
        verify(walletAddressCache).clear()
    }

    @Test
    fun `execute does not interact with other repositories`() = runTest {
        useCase.execute()

        verify(seedVault).deleteSeed()
        verify(walletAddressCache).clear()
        verifyNoMoreInteractions(seedVault)
        verifyNoMoreInteractions(walletAddressCache)
    }
}
