package com.midnight.kuira.core.compact

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

/**
 * What a `uint` witness value becomes inside the runtime: the real witness template, run in QuickJS,
 * handed the bytes the SDK serializes. A value whose top byte is 0x80 or more carries a leading
 * sign byte from BigInteger.toByteArray, which the decoding must not turn into a different number.
 */
@RunWith(AndroidJUnit4::class)
class UintWitnessRuntimeTest {

    @Test
    fun aBirthYearReachesTheCircuitAsTheSameNumber() = assertDecodes(BigInteger.valueOf(1990))

    @Test
    fun aValueWithASignByteReachesTheCircuitUnchanged() = assertDecodes(BigInteger.valueOf(255))

    @Test
    fun theLargestUint64ReachesTheCircuitUnchanged() =
        assertDecodes(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))

    @Test
    fun zeroReachesTheCircuitAsZero() = assertDecodes(BigInteger.ZERO)

    private fun assertDecodes(value: BigInteger) = runBlocking {
        // Given: the template for one uint witness, answered with the SDK's own serialization
        val provider = WitnessProvider { WitnessResult.uint(value) }
        val entries = buildWitnessEntriesJs(mapOf(WITNESS to provider))

        val decoded = quickJs {
            function("__witness_$WITNESS") { args: Array<Any?> ->
                CircuitExecutor.serializeWitness(provider.provide(args.getOrNull(0)))
            }

            // When: the circuit asks for the witness
            evaluate<String>(
                """
                const witnesses = { $entries };
                const [, result] = witnesses.$WITNESS({ privateState: null });
                typeof result + ':' + result.toString();
                """.trimIndent(),
            )
        }

        // Then: one BigInt with the same value
        assertEquals("a Uint witness must reach the circuit as the same BigInt", "bigint:$value", decoded)
    }

    private companion object {
        const val WITNESS = "year"
    }
}
