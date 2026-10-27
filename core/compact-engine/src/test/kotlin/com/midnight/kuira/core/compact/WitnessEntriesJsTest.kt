package com.midnight.kuira.core.compact

import org.junit.Assert.assertEquals
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
    fun `the declared witnesses are read from the compiled constructor's checks`() {
        // Given: the checks compact 0.31.1 generates, one per declared witness
        val contractJs = """
            if (typeof(witnesses_0.localSecretKey) !== 'function') {
              throw new __compactRuntime.CompactError('first (witnesses) argument to Contract constructor does not contain a function-valued field named localSecretKey');
            }
            if (typeof(witnesses_0.enrolmentPath) !== 'function') {
              throw new __compactRuntime.CompactError('first (witnesses) argument to Contract constructor does not contain a function-valued field named enrolmentPath');
            }
        """.trimIndent()

        // When / Then
        assertEquals(setOf("localSecretKey", "enrolmentPath"), declaredWitnesses(contractJs))
    }

    @Test
    fun `a declared witness the app does not supply gets a stand-in that names it when called`() {
        // When
        val js = buildWitnessEntriesJs(mapOf("localSecretKey" to bytesWitness), declared = setOf("localSecretKey", "enrolmentPath"))

        // Then: the supplied one is the real bridge, the other a stand-in naming itself
        assertTrue(js, js.contains("localSecretKey: function(witnessContext)"))
        assertTrue(js, js.contains("enrolmentPath: function()"))
        assertTrue(js, js.contains("the contract called witness 'enrolmentPath', and this contract handle supplies none"))
        assertEquals("one entry per declared witness", 1, Regex("localSecretKey: function").findAll(js).count())
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
