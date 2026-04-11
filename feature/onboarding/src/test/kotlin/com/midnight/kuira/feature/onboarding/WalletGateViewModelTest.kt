// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.feature.onboarding

import com.midnight.kuira.core.auth.SeedVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for [WalletGateViewModel].
 *
 * Verifies the three-state machine: Loading → (NoWallet | Ready)
 * and the manual flip via [WalletGateViewModel.onWalletCreated].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletGateViewModelTest {

    private lateinit var seedVault: SeedVault
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        seedVault = mock()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state is Ready when seed exists`() = runTest {
        whenever(seedVault.hasSeed()).thenReturn(true)

        val viewModel = WalletGateViewModel(seedVault)
        advanceUntilIdle()

        assertEquals(WalletGateState.Ready, viewModel.state.value)
    }

    @Test
    fun `state is NoWallet when no seed exists`() = runTest {
        whenever(seedVault.hasSeed()).thenReturn(false)

        val viewModel = WalletGateViewModel(seedVault)
        advanceUntilIdle()

        assertEquals(WalletGateState.NoWallet, viewModel.state.value)
    }

    @Test
    fun `onWalletCreated transitions from NoWallet to Ready`() = runTest {
        whenever(seedVault.hasSeed()).thenReturn(false)
        val viewModel = WalletGateViewModel(seedVault)
        advanceUntilIdle()
        assertEquals(WalletGateState.NoWallet, viewModel.state.value)

        viewModel.onWalletCreated()

        assertEquals(WalletGateState.Ready, viewModel.state.value)
    }

    @Test
    fun `onWalletCreated is idempotent when already Ready`() = runTest {
        whenever(seedVault.hasSeed()).thenReturn(true)
        val viewModel = WalletGateViewModel(seedVault)
        advanceUntilIdle()

        viewModel.onWalletCreated()
        viewModel.onWalletCreated()

        assertEquals(WalletGateState.Ready, viewModel.state.value)
    }
}
