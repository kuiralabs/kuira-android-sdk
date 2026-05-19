package com.midnight.kuira.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit rule that swaps the Main dispatcher for a [TestDispatcher] for
 * the duration of a test.
 *
 * Why: ViewModels backed by `viewModelScope.launch { … }` schedule on
 * `Dispatchers.Main.immediate`, which throws on the JVM without an
 * installed dispatcher. [UnconfinedTestDispatcher] is the default
 * because it runs coroutines eagerly in-thread, which makes
 * `MutableStateFlow` updates from `init { }` blocks observable
 * synchronously from the test body — no `runTest { advanceUntilIdle() }`
 * gymnastics required for the common case.
 *
 * Tests that need controlled virtual time (e.g. exercising `delay(…)`
 * inside the SUT) should pass a `StandardTestDispatcher()` explicitly
 * and drive the scheduler with `runTest { … }`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
