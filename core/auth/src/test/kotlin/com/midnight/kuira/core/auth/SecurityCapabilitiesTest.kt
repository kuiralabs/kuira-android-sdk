// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for [KeySecurityLevel] enum ordering and values.
 *
 * Note: Most [SecurityCapabilities] methods require Android context
 * (BiometricManager, PackageManager, KeyStore) and need instrumentation tests.
 * These unit tests cover the enum contract and invariants only.
 */
class SecurityCapabilitiesTest {

    @Test
    fun `KeySecurityLevel has three levels in correct order`() {
        val levels = KeySecurityLevel.entries
        assertEquals(3, levels.size)
        assertEquals(KeySecurityLevel.STRONGBOX, levels[0])
        assertEquals(KeySecurityLevel.TEE, levels[1])
        assertEquals(KeySecurityLevel.SOFTWARE, levels[2])
    }

    @Test
    fun `STRONGBOX is strongest security level`() {
        // StrongBox ordinal should be lowest (strongest first)
        assert(KeySecurityLevel.STRONGBOX.ordinal < KeySecurityLevel.TEE.ordinal)
        assert(KeySecurityLevel.TEE.ordinal < KeySecurityLevel.SOFTWARE.ordinal)
    }

    @Test
    fun `KeySecurityLevel valueOf works for all entries`() {
        // Ensure enum values are stable (serialization compatibility)
        assertNotNull(KeySecurityLevel.valueOf("STRONGBOX"))
        assertNotNull(KeySecurityLevel.valueOf("TEE"))
        assertNotNull(KeySecurityLevel.valueOf("SOFTWARE"))
    }
}
