package com.midnight.kuira.core.compact

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * Main-safety regression for [CircuitExecutor].
 *
 * executeCircuit / executeConstructor must run their heavy QuickJS circuit
 * execution + native JNI tx-assembly OFF the caller's thread. Before the fix a
 * dApp calling on Dispatchers.Main (wallet panel, Kicks match flow) ran the
 * Compact circuit on the main thread for seconds, freezing every Compose overlay
 * button (picker / leave / resume).
 *
 * The native QuickJS path can't run in a JVM unit test, so each call is driven
 * with an INVALID identifier — validation throws inside withContext(ioDispatcher)
 * BEFORE any QuickJS/native call, which is enough to observe that the body was
 * dispatched onto the injected dispatcher and NOT the caller thread.
 */
class CircuitExecutorThreadingTest {

    /** A dispatcher on a distinct named thread that records where it ran work. */
    private class TrackingDispatcher : CoroutineDispatcher() {
        val threads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        private val delegate =
            Executors.newSingleThreadExecutor { r -> Thread(r, "circuit-io-test") }
                .asCoroutineDispatcher()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context, Runnable {
                threads += Thread.currentThread().name
                block.run()
            })
        }
    }

    @Test
    fun `executeCircuit runs its body off the caller thread`() {
        val tracking = TrackingDispatcher()
        val executor = CircuitExecutor(context = mockk(relaxed = true), ioDispatcher = tracking)
        val callerThread = Thread.currentThread().name

        try {
            runBlocking {
                executor.executeCircuit(
                    contractJs = "",
                    contractAddress = "abab",
                    circuitName = "not a valid identifier!", // fails validateIdentifier first
                    witnesses = emptyMap(),
                    initialPrivateState = "{}",
                    coinPublicKey = ByteArray(32),
                )
            }
            fail("expected validateIdentifier to reject the invalid circuit name")
        } catch (e: IllegalArgumentException) {
            // expected — and it threw from INSIDE withContext(ioDispatcher).
        }

        assertTrue(
            "executeCircuit must dispatch its body onto the injected IO dispatcher",
            tracking.threads.contains("circuit-io-test"),
        )
        assertFalse(
            "executeCircuit must NOT run its body on the caller thread",
            tracking.threads.contains(callerThread),
        )
    }

    @Test
    fun `executeConstructor runs its body off the caller thread`() {
        val tracking = TrackingDispatcher()
        val executor = CircuitExecutor(context = mockk(relaxed = true), ioDispatcher = tracking)
        val callerThread = Thread.currentThread().name

        try {
            runBlocking {
                executor.executeConstructor(
                    contractJs = "",
                    witnesses = emptyMap(),
                    initialPrivateState = "{}",
                    coinPublicKey = ByteArray(32),
                    networkId = "not valid!", // fails validateIdentifier first
                )
            }
            fail("expected validateIdentifier to reject the invalid networkId")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        assertTrue(
            "executeConstructor must dispatch its body onto the injected IO dispatcher",
            tracking.threads.contains("circuit-io-test"),
        )
        assertFalse(
            "executeConstructor must NOT run its body on the caller thread",
            tracking.threads.contains(callerThread),
        )
    }
}
