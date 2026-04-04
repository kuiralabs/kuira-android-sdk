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
                addModule("compact-runtime", loadAsset("runtime/compact-runtime-bundle.js"))
                function("capture") { args: Array<Any?> -> result = args[0] as? String }

                evaluate<Any?>("""
                    import { StateValue } from 'compact-runtime';
                    const sv = StateValue.newNull();
                    const arr = StateValue.newArray();
                    const pushed = arr.arrayPush(sv);
                    capture(JSON.stringify({ nullType: sv._data.type, arrLength: pushed._data.items.length }));
                """.trimIndent(), asModule = true)
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
                addModule("compact-runtime", loadAsset("runtime/compact-runtime-bundle.js"))
                function("capture") { args: Array<Any?> -> result = args[0] as? String }

                evaluate<Any?>("""
                    import { ContractState, ContractOperation, ChargedState, StateValue } from 'compact-runtime';
                    const state = new ContractState();
                    state.setOperation('post', new ContractOperation());
                    const sv = StateValue.newArray();
                    state.data = new ChargedState(sv);
                    capture(JSON.stringify({
                        hasPost: state.operation('post') !== null,
                        hasData: state.data !== null,
                    }));
                """.trimIndent(), asModule = true)
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
                addModule("compact-runtime", loadAsset("runtime/compact-runtime-bundle.js"))
                function("capture") { args: Array<Any?> -> result = args[0] as? String }

                evaluate<Any?>("""
                    import { dummyContractAddress } from 'compact-runtime';
                    capture(dummyContractAddress());
                """.trimIndent(), asModule = true)
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
                addModule("compact-runtime", loadAsset("runtime/compact-runtime-bundle.js"))
                function("capture") { args: Array<Any?> -> result = args[0] as? String }

                evaluate<Any?>("""
                    import { valueToBigInt } from 'compact-runtime';
                    const v = valueToBigInt(42);
                    capture(v.toString());
                """.trimIndent(), asModule = true)
            }
        }
        assertNotNull(result)
        assertEquals("42", result)
    }
}
