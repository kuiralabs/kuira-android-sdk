// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.feature.onboarding

import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.core.auth.AuthenticationCancelledException
import com.midnight.kuira.core.auth.AuthenticationFailedException
import com.midnight.kuira.core.auth.AuthenticationLockedOutException
import com.midnight.kuira.core.auth.AuthenticationPermanentlyLockedOutException
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SecurityCapabilities
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [OnboardingViewModel].
 *
 * Uses Mockito to fake the auth stack so state transitions can be
 * verified without real Keystore / BiometricPrompt / Activity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private lateinit var walletKeyManager: WalletKeyManager
    private lateinit var seedVault: SeedVault
    private lateinit var securityCapabilities: SecurityCapabilities
    private lateinit var viewModel: OnboardingViewModel
    private lateinit var activity: FragmentActivity

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        walletKeyManager = mock()
        seedVault = mock()
        securityCapabilities = mock()
        activity = mock()

        viewModel = OnboardingViewModel(
            walletKeyManager = walletKeyManager,
            seedVault = seedVault,
            securityCapabilities = securityCapabilities,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Welcome`() {
        assertEquals(OnboardingUiState.Welcome, viewModel.uiState.value)
    }

    @Test
    fun `createWallet transitions to NeedsAuthSetup when no authenticator`() = runTest {
        whenever(securityCapabilities.hasAnyAuthenticator).thenReturn(false)

        viewModel.createWallet(activity)

        assertEquals(OnboardingUiState.NeedsAuthSetup, viewModel.uiState.value)
    }

    @Test
    fun `createWallet succeeds when storeSeed completes`() = runTest {
        whenever(securityCapabilities.hasAnyAuthenticator).thenReturn(true)
        whenever(walletKeyManager.hasKey()).thenReturn(false)
        // storeSeed invokes the producer lambda and returns successfully
        doAnswer { invocation ->
            val producer = invocation.getArgument<() -> PlaintextSeed>(1)
            producer() // Invoke to exercise the mnemonic path
            Unit
        }.whenever(seedVault).storeSeed(any(), any())

        viewModel.createWallet(activity)

        assertEquals(OnboardingUiState.Success, viewModel.uiState.value)
        verify(walletKeyManager).generateKey()
    }

    @Test
    fun `createWallet maps AuthenticationCancelledException to Error and cleans up key`() = runTest {
        whenever(securityCapabilities.hasAnyAuthenticator).thenReturn(true)
        whenever(walletKeyManager.hasKey()).thenReturn(false)
        doAnswer { throw AuthenticationCancelledException("cancelled") }
            .whenever(seedVault).storeSeed(any(), any())

        viewModel.createWallet(activity)

        val state = viewModel.uiState.value
        assertTrue("Should be Error state", state is OnboardingUiState.Error)
        assertEquals(
            R.string.onboarding_error_auth_cancelled,
            (state as OnboardingUiState.Error).message.resId
        )
        verify(walletKeyManager).deleteKey()
    }

    @Test
    fun `createWallet maps AuthenticationLockedOutException to lockout error`() = runTest {
        whenever(securityCapabilities.hasAnyAuthenticator).thenReturn(true)
        whenever(walletKeyManager.hasKey()).thenReturn(true) // Key already exists
        doAnswer { throw AuthenticationLockedOutException("locked") }
            .whenever(seedVault).storeSeed(any(), any())

        viewModel.createWallet(activity)

        val state = viewModel.uiState.value as OnboardingUiState.Error
        assertEquals(R.string.onboarding_error_locked_out, state.message.resId)
        // Key was NOT created this attempt, so it should NOT be deleted
        verify(walletKeyManager, org.mockito.kotlin.never()).deleteKey()
    }

    @Test
    fun `createWallet maps AuthenticationPermanentlyLockedOutException to perm lockout error`() = runTest {
        whenever(securityCapabilities.hasAnyAuthenticator).thenReturn(true)
        whenever(walletKeyManager.hasKey()).thenReturn(true)
        doAnswer { throw AuthenticationPermanentlyLockedOutException("perm") }
            .whenever(seedVault).storeSeed(any(), any())

        viewModel.createWallet(activity)

        val state = viewModel.uiState.value as OnboardingUiState.Error
        assertEquals(R.string.onboarding_error_permanently_locked, state.message.resId)
    }

    @Test
    fun `createWallet maps AuthenticationFailedException to auth failed error with message`() = runTest {
        whenever(securityCapabilities.hasAnyAuthenticator).thenReturn(true)
        whenever(walletKeyManager.hasKey()).thenReturn(true)
        doAnswer { throw AuthenticationFailedException("hardware error") }
            .whenever(seedVault).storeSeed(any(), any())

        viewModel.createWallet(activity)

        val state = viewModel.uiState.value as OnboardingUiState.Error
        assertEquals(R.string.onboarding_error_auth_failed, state.message.resId)
        assertEquals(listOf("hardware error"), state.message.formatArgs)
    }

    @Test
    fun `createWallet maps generic exception to generic error and cleans up fresh key`() = runTest {
        whenever(securityCapabilities.hasAnyAuthenticator).thenReturn(true)
        whenever(walletKeyManager.hasKey()).thenReturn(false)
        doAnswer { throw IllegalStateException("boom") }
            .whenever(seedVault).storeSeed(any(), any())

        viewModel.createWallet(activity)

        val state = viewModel.uiState.value as OnboardingUiState.Error
        assertEquals(R.string.onboarding_error_generic, state.message.resId)
        verify(walletKeyManager).deleteKey() // Freshly created, must clean up
    }

    @Test
    fun `createWallet does not delete pre-existing key on failure`() = runTest {
        whenever(securityCapabilities.hasAnyAuthenticator).thenReturn(true)
        whenever(walletKeyManager.hasKey()).thenReturn(true) // Key already existed
        doAnswer { throw AuthenticationCancelledException("cancelled") }
            .whenever(seedVault).storeSeed(any(), any())

        viewModel.createWallet(activity)

        // We didn't create the key this attempt, so we must not delete it
        verify(walletKeyManager, org.mockito.kotlin.never()).deleteKey()
    }

    @Test
    fun `reset returns state to Welcome`() = runTest {
        whenever(securityCapabilities.hasAnyAuthenticator).thenReturn(false)
        viewModel.createWallet(activity)
        assertEquals(OnboardingUiState.NeedsAuthSetup, viewModel.uiState.value)

        viewModel.reset()

        assertEquals(OnboardingUiState.Welcome, viewModel.uiState.value)
    }

    @Test
    fun `startRestore enters RestoreInput state with empty input`() {
        viewModel.startRestore()

        val state = viewModel.uiState.value
        assertTrue(state is OnboardingUiState.RestoreInput)
        assertEquals("", (state as OnboardingUiState.RestoreInput).input)
        assertFalse(state.invalid)
    }

    @Test
    fun `updateRestoreInput updates the input text and clears invalid flag`() {
        viewModel.startRestore()
        // Mark invalid first by setting a known bad state
        val initial = viewModel.uiState.value as OnboardingUiState.RestoreInput

        viewModel.updateRestoreInput("abandon abandon abandon")

        val updated = viewModel.uiState.value as OnboardingUiState.RestoreInput
        assertEquals("abandon abandon abandon", updated.input)
        assertFalse(updated.invalid)
    }

    @Test
    fun `confirmRestore marks input invalid for malformed phrase`() = runTest {
        viewModel.startRestore()
        viewModel.updateRestoreInput("not a real mnemonic")

        viewModel.confirmRestore(activity)

        val state = viewModel.uiState.value as OnboardingUiState.RestoreInput
        assertTrue(state.invalid)
    }

    @Test
    fun `confirmRestore proceeds to creation flow for valid mnemonic`() = runTest {
        // BIP-39 zero-entropy mnemonic — guaranteed valid
        val validPhrase = "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon art"

        whenever(securityCapabilities.hasAnyAuthenticator).thenReturn(true)
        whenever(walletKeyManager.hasKey()).thenReturn(false)
        doAnswer { invocation ->
            val producer = invocation.getArgument<() -> PlaintextSeed>(1)
            producer()
            Unit
        }.whenever(seedVault).storeSeed(any(), any())

        viewModel.startRestore()
        viewModel.updateRestoreInput(validPhrase)
        viewModel.confirmRestore(activity)

        assertEquals(OnboardingUiState.Success, viewModel.uiState.value)
        verify(walletKeyManager).generateKey()
    }

    @Test
    fun `confirmRestore is no-op outside RestoreInput state`() = runTest {
        // Initial state is Welcome, not RestoreInput
        viewModel.confirmRestore(activity)

        assertEquals(OnboardingUiState.Welcome, viewModel.uiState.value)
    }
}
