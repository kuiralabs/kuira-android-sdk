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

@RunWith(AndroidJUnit4::class)
class CompactRuntimeShimTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun loadAsset(path: String): String {
        return context.assets.open(path).bufferedReader().readText()
    }

    @Test
    fun shim_stateValueWorks() {
        var result: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> result = args[0] as? String }
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
                evaluate<Any?>("""
                    const sv = __compactRuntime.StateValue.newNull();
                    const arr = __compactRuntime.StateValue.newArray();
                    const pushed = arr.arrayPush(sv);
                    capture(JSON.stringify({ nullType: sv._data.type, arrLength: pushed._data.items.length }));
                """.trimIndent())
            }
        }
        assertNotNull("Should capture result", result)
        assertTrue(result!!.contains("null"))
        assertTrue(result!!.contains("1"))
    }

    @Test
    fun shim_contractStateWorks() {
        var result: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> result = args[0] as? String }
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
                evaluate<Any?>("""
                    const state = new __compactRuntime.ContractState();
                    state.setOperation('post', new __compactRuntime.ContractOperation());
                    const sv = __compactRuntime.StateValue.newArray();
                    state.data = new __compactRuntime.ChargedState(sv);
                    capture(JSON.stringify({
                        hasPost: state.operation('post') !== null,
                        hasData: state.data !== null,
                    }));
                """.trimIndent())
            }
        }
        assertNotNull(result)
        assertTrue(result!!.contains("true"))
    }

    @Test
    fun shim_dummyAddressWorks() {
        var result: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> result = args[0] as? String }
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
                evaluate<Any?>("capture(__compactRuntime.dummyContractAddress())")
            }
        }
        assertNotNull(result)
        assertEquals(64, result!!.length)
    }

    @Test
    fun shim_valueToBigIntWorks() {
        var result: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> result = args[0] as? String }
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
                evaluate<Any?>("""
                    const v = __compactRuntime.valueToBigInt(42);
                    capture(v.toString());
                """.trimIndent())
            }
        }
        assertNotNull(result)
        assertEquals("42", result)
    }
}
