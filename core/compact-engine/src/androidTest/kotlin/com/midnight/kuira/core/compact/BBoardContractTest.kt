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
 * Uses IIFE format (not ES modules) because QuickJS's cross-module
 * import crashes in the native layer. IIFE loads compact-runtime as
 * a global, then the bboard contract runs as a script.
 */
@RunWith(AndroidJUnit4::class)
class BBoardContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun loadAsset(path: String): String {
        return context.assets.open(path).bufferedReader().readText()
    }

    @Test
    fun iife_bundle_works() {
        var result: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> result = args[0] as? String }
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
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
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
                evaluate<Any?>(loadAsset("runtime/bboard-contract-iife.js"))
                evaluate<Any?>("capture(typeof Contract === 'function' ? 'yes' : 'no')")
            }
        }
        assertEquals("Contract should load", "yes", result)
    }

    @Test
    fun bboard_contractCanInstantiate() {
        var result: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> result = args[0] as? String }
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
                evaluate<Any?>(loadAsset("runtime/bboard-contract-iife.js"))
                // Verify the Contract class has the expected circuits
                evaluate<Any?>("""
                    const witnesses = { localSecretKey: function() { return [null, new Uint8Array(32)]; } };
                    const contract = new Contract(witnesses);
                    capture(JSON.stringify({
                        hasPost: typeof contract.impureCircuits.post === 'function',
                        hasTakeDown: typeof contract.impureCircuits.takeDown === 'function',
                    }));
                """.trimIndent())
            }
        }
        assertNotNull(result)
        assertTrue("Should have post", result!!.contains("true"))
    }
}
