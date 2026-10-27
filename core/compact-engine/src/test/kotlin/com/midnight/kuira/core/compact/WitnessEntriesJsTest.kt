package com.midnight.kuira.core.compact

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WitnessEntriesJsTest {

    private val bytesWitness = WitnessProvider { WitnessResult(privateState = null, data = ByteArray(32)) }

    @Test
    fun `a witness name that is not an identifier is refused before it reaches the runtime`() {
        // Given: a name that would close the function and run code of its own
        val injected = "x: 0, y: (globalThis.leak = 1), z"

        // When / Then
        assertThrows(IllegalArgumentException::class.java) {
            buildWitnessEntriesJs(mapOf(injected to bytesWitness))
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildWitnessEntriesJs(mapOf(injected to LedgerTreePathWitness("members")))
        }
    }

    @Test
    fun `a ledger tree that is not a field name is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildWitnessEntriesJs(mapOf("memberPath" to LedgerTreePathWitness("members; globalThis.leak = 1")))
        }
    }

    @Test
    fun `the contract's own witness identifiers are accepted`() {
        // When
        val js = buildWitnessEntriesJs(
            mapOf("birthYear" to bytesWitness, "enrolmentPath" to LedgerTreePathWitness("enrolled")),
        )

        // Then
        assertTrue(js, js.contains("birthYear: function(witnessContext)"))
        assertTrue(js, js.contains("enrolmentPath: function(witnessContext, leaf)"))
        assertTrue(js, js.contains("witnessContext.ledger.enrolled"))
    }
}
