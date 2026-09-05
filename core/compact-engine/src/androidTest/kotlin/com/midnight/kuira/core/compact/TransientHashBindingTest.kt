package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/**
 * `transientHash` end-to-end: QuickJS runtime → native Poseidon → back.
 *
 * Covers the wiring the Rust unit tests cannot reach. `transientHash` used to
 * throw "native function not bound" (kuira-sdk-android#7); binding it also meant
 * fixing the JS wrapper, which declared ONE parameter while `transientHash2`
 * called it with `(alignment, toValue(value))` — so the value was dropped before
 * it ever reached the FFI.
 */
@RunWith(AndroidJUnit4::class)
class TransientHashBindingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    /** Hash the given field values through the real runtime, returning one digest each. */
    private fun hashFields(vararg values: Int): List<String> {
        var captured: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> captured = args[0] as? String }
                CircuitExecutor.registerNativeFfi(this)
                evaluate<Any?>(loadAsset("runtime/polyfills.js"))
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
                val literals = values.joinToString(", ") { "${it}n" }
                evaluate<Any?>(
                    """
                    // CompactTypeField is a singleton object, not a class — no `new`.
                    const F = __compactRuntime.CompactTypeField;
                    const out = [$literals].map(v =>
                        __compactRuntime.transientHash(F, v).toString()
                    );
                    capture(JSON.stringify({ digests: out }));
                    """.trimIndent()
                )
            }
        }
        assertNotNull("transientHash produced no result", captured)
        val arr = JSONObject(captured!!).getJSONArray("digests")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Test
    fun transientHash_isBound_andHashesTheValue() {
        // Three calls: two identical preimages and one different. If the wrapper
        // drops the value again, all three collide on the alignment-only digest.
        val digests = hashFields(1, 1, 2)

        assertEquals("same field value must hash the same", digests[0], digests[1])
        assertNotEquals(
            "different field values must not collide — the value is being dropped",
            digests[0],
            digests[2],
        )
    }

    @Test
    fun transientHash_differsFromPersistentHash() {
        // Poseidon over field elements, not SHA-256 over binary_repr bytes. Equal
        // digests here would mean transientHash is quietly delegating.
        var captured: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> captured = args[0] as? String }
                CircuitExecutor.registerNativeFfi(this)
                evaluate<Any?>(loadAsset("runtime/polyfills.js"))
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
                evaluate<Any?>(
                    """
                    const F = __compactRuntime.CompactTypeField;
                    capture(JSON.stringify({
                        transient: __compactRuntime.transientHash(F, 7n).toString(),
                        persistent: __compactRuntime.persistentHash(F, 7n).toString(),
                    }));
                    """.trimIndent()
                )
            }
        }
        assertNotNull(captured)
        val json = JSONObject(captured!!)
        assertNotEquals(json.getString("transient"), json.getString("persistent"))
    }
}
