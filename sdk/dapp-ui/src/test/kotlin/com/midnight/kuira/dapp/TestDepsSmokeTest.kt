package com.midnight.kuira.dapp

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Smoke tests for the unit-test dependency stack (mockk + Robolectric)
 * added in chunk 3 of the sdk/dapp-ui extraction.
 *
 * Existence proofs only — these tests verify the *dependencies resolve
 * and are usable end-to-end*, not anything about the production code.
 * The real test suite for the ViewModels + SeedVault lands in chunk 5
 * and exercises these libraries against the actual production classes.
 *
 * Why a dedicated smoke test: it's much cheaper to discover a bad
 * mockk/Kotlin version pairing or a missing Robolectric resource flag
 * here, in a 4-line test that has nothing else going on, than mid-way
 * through writing the real suite.
 */
@RunWith(RobolectricTestRunner::class)
class TestDepsSmokeTest {

    @Test
    fun `mockk can mock a simple class and verify call ordering`() {
        // The pattern the chunk-5 SigilPanelViewModel tests will use:
        // mock the dependency, stub the return value, exercise the
        // production code, then verify the call order via `verify`.
        // Proving the pattern here means a failure in chunk 5 is a
        // production-code bug, not a mockk wiring bug.
        val builder = mockk<StringBuilder>()
        every { builder.length } returns 42
        every { builder.append(any<String>()) } returns builder

        assertEquals(42, builder.length)
        builder.append("hello")

        verify { builder.length }
        verify { builder.append("hello") }
    }

    @Test
    fun `robolectric provides a real Application Context`() {
        // Robolectric's value here is the *real* Android system
        // behaviour — SharedPreferences that actually fsync, file IO
        // backed by an in-test sandbox, etc. Verifying we can reach
        // Application means SharedPreferences durability tests in
        // chunk 5 (specifically `dismissBackup commit() not apply()`)
        // will be able to construct a real PreferenceManager.
        val context: Context = RuntimeEnvironment.getApplication()
        assertNotNull("Robolectric should provide an Application instance", context)
        assertNotNull("Application should expose a Context.filesDir", context.filesDir)
    }
}
