package com.midnight.kuira.sdk.walletseed

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Where the dev seed comes from, and whose debug flag decides it.
 *
 * Both answers used to be the library's: the hex was baked into `BuildConfig` when the SDK was
 * published, and the gate read the LIBRARY's `BuildConfig.DEBUG`. That is false in every
 * published AAR, so no app consuming the SDK could ever use the hatch — every example app needed
 * a sigil forged by hand before it could be driven — and the published artifact carried whatever
 * seed the publishing machine happened to have.
 */
@RunWith(RobolectricTestRunner::class)
class DevSeedTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun declareNoSeed() {
        applicationInfo().metaData = android.os.Bundle()
    }

    @Test
    fun `a debuggable app gets the seed its own manifest declares`() {
        makeDebuggable(true)
        applicationInfo().metaData.putString(DevSeed.MANIFEST_KEY, SEED_HEX)

        assertArrayEquals(
            "the host app's manifest is the only place the seed can come from",
            SEED_BYTES,
            DevSeed.resolve(context),
        )
    }

    /** The gate that makes shipping a build with the override still declared survivable. */
    @Test
    fun `a release app is refused even when it declares one`() {
        makeDebuggable(false)
        applicationInfo().metaData.putString(DevSeed.MANIFEST_KEY, SEED_HEX)

        assertNull("a non-debuggable app must never take the override", DevSeed.resolve(context))
    }

    @Test
    fun `an app that declares nothing has no override`() {
        makeDebuggable(true)

        assertNull(DevSeed.resolve(context))
    }

    /**
     * An app whose `local.properties` has no `kuira.dev.seed` still carries the meta-data entry,
     * and the placeholder ships through unsubstituted when the plugin never ran.
     */
    @Test
    fun `an unsubstituted placeholder is not a seed`() {
        makeDebuggable(true)
        applicationInfo().metaData.putString(DevSeed.MANIFEST_KEY, "\${kuiraDevSeed}")

        assertNull(DevSeed.resolve(context))
    }

    @Test
    fun `the debuggable flag read is the host app's own`() {
        makeDebuggable(true)
        assertTrue(DevSeed.isHostDebuggable(context))

        makeDebuggable(false)
        assertFalse(DevSeed.isHostDebuggable(context))
    }

    /**
     * The gate apps actually consult.
     *
     * Three example apps ask the SDK for a seed and were unblocked by the dev seed alone; two ask
     * `hasSigil()` first and showed "identity required" with a perfectly good wallet behind it.
     * The seed answered "give me a wallet" while the gate asked "is there an identity", so an
     * automated run could reach neither.
     */
    @Test
    fun `a declared dev seed counts as an identity`() {
        makeDebuggable(true)
        applicationInfo().metaData.putString(DevSeed.MANIFEST_KEY, SEED_HEX)

        assertNotNull(
            "a debuggable app that declares a seed can open a wallet, which is what the gate asks",
            DevSeed.resolve(context),
        )
    }

    /**
     * The yes/no the sigil panel asks, which must not decode the seed to answer: a boolean
     * question that materializes key material is key material in memory for no reason.
     */
    @Test
    fun `a declared seed is visible without decoding it`() {
        makeDebuggable(true)
        applicationInfo().metaData.putString(DevSeed.MANIFEST_KEY, SEED_HEX)
        assertTrue(DevSeed.isDeclared(context))

        makeDebuggable(false)
        assertFalse("a release app declares nothing usable", DevSeed.isDeclared(context))
    }

    @Test
    fun `an app that declares nothing, or a bare placeholder, declares no seed`() {
        makeDebuggable(true)
        assertFalse(DevSeed.isDeclared(context))

        applicationInfo().metaData.putString(DevSeed.MANIFEST_KEY, "\${kuiraDevSeed}")
        assertFalse(DevSeed.isDeclared(context))
    }

    private fun applicationInfo(): ApplicationInfo =
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .applicationInfo!!

    private fun makeDebuggable(debuggable: Boolean) {
        val info = applicationInfo()
        info.flags = if (debuggable) {
            info.flags or ApplicationInfo.FLAG_DEBUGGABLE
        } else {
            info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
        context.applicationInfo.flags = info.flags
    }

    private companion object {
        private val SEED_HEX = "9f".repeat(64)
        private val SEED_BYTES = ByteArray(64) { 0x9f.toByte() }
    }
}
