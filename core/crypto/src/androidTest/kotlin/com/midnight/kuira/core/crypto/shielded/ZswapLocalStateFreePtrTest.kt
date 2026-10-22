package com.midnight.kuira.core.crypto.shielded

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ZswapLocalState.freeNativePtr] must actually release a pointer rather than throw.
 *
 * It is the only way [ZswapTransferBuilder] releases the intermediate states a spend creates —
 * after a MULTI-coin spend, on the failure path, and in `cleanupStatePtrs`. It was implemented as
 * a static native that has no implementation in the shipped library, so every call threw
 * `UnsatisfiedLinkError`: a cleanup that crashed, masked whatever error was being cleaned up
 * after, and leaked the state regardless.
 *
 * Nothing caught it because a single-coin shielded spend never reaches those call sites (the free
 * loop's body does not execute, and the pointer list is cleared on success), and the multi-input
 * UNSHIELDED send — the one place a multi-input send is covered — goes through a different
 * builder entirely. This test takes the direct route instead of waiting for a multi-coin shielded
 * spend to exist.
 */
@RunWith(AndroidJUnit4::class)
class ZswapLocalStateFreePtrTest {

    @Test
    fun freeNativePtrReleasesAStateInsteadOfThrowing() {
        val state = ZswapLocalState.create()
        assertNotNull("zswap state must be creatable", state)
        // Hand the raw pointer over and let the static helper own it from here — so no close()
        // below, which would double-free. Before the fix this line threw UnsatisfiedLinkError.
        ZswapLocalState.freeNativePtr(state!!.nativePtr())
    }

    @Test
    fun freeNativePtrTreatsTheNullPointerAsNothingToDo() {
        // ZswapTransferBuilder can hold a 0 pointer when a native call failed, so this path is
        // reachable precisely when something has already gone wrong.
        ZswapLocalState.freeNativePtr(0L)
    }
}
