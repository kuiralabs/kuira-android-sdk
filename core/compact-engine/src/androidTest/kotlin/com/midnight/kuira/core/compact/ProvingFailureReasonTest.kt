package com.midnight.kuira.core.compact

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A local proof that fails says why, in the prover's words. It used to say only "Local prover
 * returned null", which hid the reasons a developer can act on: the circuit is too large for
 * the device, or too little memory is free.
 */
@RunWith(AndroidJUnit4::class)
class ProvingFailureReasonTest {

    @Test
    fun aProofThatCannotStartCarriesTheProversReason() {
        // Given
        val keys = ProvingKeyManager(ApplicationProvider.getApplicationContext())
        keys.keysDir.mkdirs()
        val prover = LocalProofProvider(keys)

        // When
        val error = assertThrows(ProvingException::class.java) {
            runBlocking { prover.prove(NOT_A_TRANSACTION) }
        }

        // Then
        assertEquals("the unproven transaction is not valid hex", error.message)
    }

    private companion object {
        const val NOT_A_TRANSACTION = "not_hex"
    }
}
