package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `degradeToTransient` / `upgradeFromTransient` end-to-end through the real runtime.
 *
 * Both used to `return x`. The library op keeps the low 31 bytes and drops the top one,
 * so the identity disagreed with the on-chain circuit whenever that byte was set — and it
 * disagreed silently, which is what makes it worse than `transientCommit` next door simply
 * refusing to run. A circuit computing a Merkle path with the cheap hash crosses back out
 * through exactly these two calls.
 */
@RunWith(AndroidJUnit4::class)
class FieldConversionBindingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    private fun evalInRuntime(script: String): JSONObject {
        var captured: String? = null
        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> captured = args[0] as? String }
                CircuitExecutor.registerNativeFfi(this)
                evaluate<Any?>(loadAsset("runtime/polyfills.js"))
                evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
                evaluate<Any?>(script)
            }
        }
        assertNotNull("script produced no result", captured)
        return JSONObject(captured!!)
    }

    @Test
    fun degradeToTransient_isNotTheIdentity() {
        // Top byte set: precisely the case where dropping it changes the answer. The old
        // code returned its argument, so the field element it produced was the whole
        // 32-byte little-endian value. Compute that here and require a different answer —
        // asserting merely that something came back would still pass against the bug.
        val json = evalInRuntime(
            """
            const bytes = new Uint8Array(32).fill(1);
            bytes[31] = 0xAA;

            // what `return x` used to yield
            let identity = 0n;
            for (let i = 0; i < bytes.length; i++) {
                identity += BigInt(bytes[i]) << BigInt(8 * i);
            }

            const out = __compactRuntime.degradeToTransient(bytes);
            capture(JSON.stringify({ out: out.toString(), identity: identity.toString() }));
            """.trimIndent()
        )
        assertNotEquals(
            "degradeToTransient still returns its argument — the top byte was not dropped",
            json.getString("identity"),
            json.getString("out"),
        )
    }

    @Test
    fun upgradeThenDegrade_returnsTheSameField() {
        // The round trip a Merkle root takes crossing out to Bytes<32> and back.
        val json = evalInRuntime(
            """
            const bytes = new Uint8Array(32).fill(3);
            bytes[31] = 0;
            const field = __compactRuntime.degradeToTransient(bytes);
            const back  = __compactRuntime.upgradeFromTransient(field);
            const again = __compactRuntime.degradeToTransient(back);
            capture(JSON.stringify({ field: field.toString(), again: again.toString() }));
            """.trimIndent()
        )
        assertEquals(
            "degrade(upgrade(x)) must land on the same field element",
            json.getString("field"),
            json.getString("again"),
        )
    }
}
