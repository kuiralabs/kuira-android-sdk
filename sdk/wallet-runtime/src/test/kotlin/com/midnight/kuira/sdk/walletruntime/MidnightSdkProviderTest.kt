package com.midnight.kuira.sdk.walletruntime

import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.core.compact.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.testing.MainDispatcherRule
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.sdk.walletseed.WalletSeedSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [MidnightSdkProvider] — the single-SDK ownership state machine.
 *
 * The provider's job is orchestration, not SDK internals: build once, reuse
 * on identical config, rebuild + tear down the old instance on config change,
 * suspend followers until an SDK exists, and wipe the seed it pulled from
 * [WalletSeedSource]. All of that is testable with a fake [MidnightSdkFactory]
 * handing back relaxed [MidnightSdk] mocks — no real HD derivation, Room DB,
 * or indexer socket required.
 *
 * Robolectric only for `android.util.Log` shadowing (the provider logs build
 * lifecycle); the class touches no Context or SharedPreferences itself.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent — TestScope time control
@RunWith(RobolectricTestRunner::class)
class MidnightSdkProviderTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val walletSeedSource: WalletSeedSource = mockk()
    private val sdkFactory: MidnightSdkFactory = mockk()
    private val activity: FragmentActivity = mockk(relaxed = true)

    private val configUndeployed = WalletConfig(
        network = MidnightNetwork.UNDEPLOYED,
        provingMode = ProvingMode.DEFAULT,
    )
    private val configPreprod = WalletConfig(
        network = MidnightNetwork.PREPROD,
        provingMode = ProvingMode.DEFAULT,
    )

    private fun newProvider() = MidnightSdkProvider(
        walletSeedSource = walletSeedSource,
        sdkFactory = sdkFactory,
    )

    /** A fresh seed each call so a wipe of one call's seed can't mask another. */
    private fun stubSeed() {
        coEvery { walletSeedSource.ensureSeedReady(activity) } answers { ByteArray(64) { 1 } }
    }

    @Test
    fun `ensureSdk builds once and reuses on identical config`() = runTest {
        stubSeed()
        val sdk = mockk<MidnightSdk>(relaxed = true)
        every { sdkFactory.create(any(), any()) } returns sdk

        val provider = newProvider()
        val first = provider.ensureSdk(activity, configUndeployed)
        val second = provider.ensureSdk(activity, configUndeployed)

        assertSame("Identical config must reuse the cached SDK", first, second)
        verify(exactly = 1) { sdkFactory.create(any(), any()) }
        coVerify(exactly = 1) { walletSeedSource.ensureSeedReady(activity) }
        assertSame(sdk, provider.sdk.value)
        assertEquals(configUndeployed, provider.activeConfig.value)
    }

    @Test
    fun `ensureSdk rebuilds and closes the old SDK when config changes`() = runTest {
        stubSeed()
        val sdkA = mockk<MidnightSdk>(relaxed = true)
        val sdkB = mockk<MidnightSdk>(relaxed = true)
        every { sdkFactory.create(configUndeployed, any()) } returns sdkA
        every { sdkFactory.create(configPreprod, any()) } returns sdkB

        val provider = newProvider()
        provider.ensureSdk(activity, configUndeployed)
        val rebuilt = provider.ensureSdk(activity, configPreprod)

        assertSame("Config change must hand back the freshly built SDK", sdkB, rebuilt)
        verify(exactly = 1) { sdkA.close() } // old instance torn down
        verify(exactly = 2) { sdkFactory.create(any(), any()) }
        coVerify(exactly = 2) { walletSeedSource.ensureSeedReady(activity) }
        assertSame(sdkB, provider.sdk.value)
        assertEquals(configPreprod, provider.activeConfig.value)
    }

    @Test
    fun `awaitSdk suspends until an SDK is published then returns it`() = runTest {
        stubSeed()
        val sdk = mockk<MidnightSdk>(relaxed = true)
        every { sdkFactory.create(any(), any()) } returns sdk

        val provider = newProvider()
        val waiter = async { provider.awaitSdk() }
        runCurrent()
        assertFalse("awaitSdk must not resolve before any SDK exists", waiter.isCompleted)

        provider.ensureSdk(activity, configUndeployed)
        runCurrent()

        assertTrue("awaitSdk must resolve once an SDK is published", waiter.isCompleted)
        assertSame(sdk, waiter.await())
    }

    @Test
    fun `awaitSdk returns the already-built SDK immediately`() = runTest {
        stubSeed()
        val sdk = mockk<MidnightSdk>(relaxed = true)
        every { sdkFactory.create(any(), any()) } returns sdk

        val provider = newProvider()
        provider.ensureSdk(activity, configUndeployed)

        assertSame(sdk, provider.awaitSdk())
    }

    @Test
    fun `close tears down the SDK and clears state`() = runTest {
        stubSeed()
        val sdk = mockk<MidnightSdk>(relaxed = true)
        every { sdkFactory.create(any(), any()) } returns sdk

        val provider = newProvider()
        provider.ensureSdk(activity, configUndeployed)
        provider.close()

        verify(exactly = 1) { sdk.close() }
        assertNull("close must clear the live SDK", provider.sdk.value)
        assertNull("close must clear the active config", provider.activeConfig.value)
    }

    @Test
    fun `seed is wiped after the SDK is built`() = runTest {
        // The builder copies the seed internally; the provider must zero its
        // own view so BIP-39 material doesn't linger on the heap. Capture the
        // exact array handed to the factory and assert it's zeroed afterward.
        stubSeed()
        val sdk = mockk<MidnightSdk>(relaxed = true)
        val seedSlot = slot<ByteArray>()
        every { sdkFactory.create(any(), capture(seedSlot)) } returns sdk

        val provider = newProvider()
        provider.ensureSdk(activity, configUndeployed)

        assertTrue(
            "Seed passed to the factory must be wiped after build",
            seedSlot.captured.all { it == 0.toByte() },
        )
    }

    @Test
    fun `seed is wiped even when the factory throws`() = runTest {
        // Defensive: a build failure (bad seed length, native init error) must
        // not leave un-wiped seed material behind.
        stubSeed()
        val seedSlot = slot<ByteArray>()
        every { sdkFactory.create(any(), capture(seedSlot)) } throws IllegalStateException("build boom")

        val provider = newProvider()
        runCatching { provider.ensureSdk(activity, configUndeployed) }

        assertTrue(
            "Seed must be wiped on the failure path too",
            seedSlot.captured.all { it == 0.toByte() },
        )
        assertNull("A failed build must not publish an SDK", provider.sdk.value)
    }
}
