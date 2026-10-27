package com.midnight.kuira.core.compact

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a `bool` witness value becomes inside the runtime: the real witness template, run in
 * QuickJS. Compact's Boolean is a JS boolean; a BigInt 1 or 0 fails the runtime's type check.
 */
@RunWith(AndroidJUnit4::class)
class BooleanWitnessRuntimeTest {

    @Test
    fun trueReachesTheCircuitAsTrue() = assertDecodes(value = true, expected = "boolean:true")

    @Test
    fun falseReachesTheCircuitAsFalse() = assertDecodes(value = false, expected = "boolean:false")

    private fun assertDecodes(value: Boolean, expected: String) = runBlocking {
        // Given
        val provider = WitnessProvider { WitnessResult.bool(value) }
        val entries = buildWitnessEntriesJs(mapOf(WITNESS to provider))

        val decoded = quickJs {
            function("__witness_$WITNESS") { args: Array<Any?> ->
                CircuitExecutor.serializeWitness(provider.provide(args.getOrNull(0)))
            }

            // When
            evaluate<String>(
                """
                const witnesses = { $entries };
                const [, result] = witnesses.$WITNESS({ privateState: null });
                typeof result + ':' + String(result);
                """.trimIndent(),
            )
        }

        // Then
        assertEquals("a Boolean witness must reach the circuit as a JS boolean", expected, decoded)
    }

    private companion object {
        const val WITNESS = "isMember"
    }
}
