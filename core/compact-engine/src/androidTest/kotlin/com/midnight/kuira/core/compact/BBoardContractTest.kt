package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 6D: Load the real bboard compiled contract in QuickJS.
 *
 * Uses the bundled compact-runtime (single file, no cross-imports)
 * with the onchain-runtime shim replacing WASM.
 */
@RunWith(AndroidJUnit4::class)
class BBoardContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun loadAsset(path: String): String {
        return context.assets.open(path).bufferedReader().readText()
    }

    @Test
    fun bundle_loadsAlone() {
        var result: String? = null
        runBlocking {
            quickJs {
                addModule("compact-runtime", loadAsset("runtime/compact-runtime-bundle.js"))
                function("capture") { args: Array<Any?> -> result = args[0] as? String }

                evaluate<Any?>("""
                    import { StateValue, dummyContractAddress } from 'compact-runtime';
                    const sv = StateValue.newNull();
                    capture(dummyContractAddress());
                """.trimIndent(), asModule = true)
            }
        }
        assertNotNull(result)
        assertEquals(64, result!!.length)
    }

    @Test
    fun iife_bundle_works() {
        var result: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> result = args[0] as? String }

                // Load compact-runtime as IIFE — assigns to __compactRuntime global
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))

                // Verify types are accessible
                evaluate<Any?>("""
                    const sv = __compactRuntime.StateValue.newNull();
                    const addr = __compactRuntime.dummyContractAddress();
                    capture(addr);
                """.trimIndent())
            }
        }
        assertNotNull(result)
        assertEquals(64, result!!.length)
    }

    @Test
    fun bboard_contractLoads() {
        var result: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> result = args[0] as? String }

                // 1. Load compact-runtime as IIFE (global __compactRuntime)
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))

                // 2. Load bboard contract as script (uses __compactRuntime global)
                evaluate<Any?>(loadAsset("runtime/bboard-contract-iife.js"))

                // 3. Verify the Contract class is available
                evaluate<Any?>("capture(typeof Contract === 'function' ? 'yes' : 'no')")
            }
        }
        assertEquals("Contract should load", "yes", result)
    }
}
