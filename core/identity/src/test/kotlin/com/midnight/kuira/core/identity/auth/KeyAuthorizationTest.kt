package com.midnight.kuira.core.identity.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for KeyAuthorization payload construction and parsing.
 */
class KeyAuthorizationTest {

    private val sampleRootKey = ByteArray(33).also {
        it[0] = 0x02
        for (i in 1..32) it[i] = (i * 3).toByte()
    }

    private val sampleAccessKey = ByteArray(33).also {
        it[0] = 0x03
        for (i in 1..32) it[i] = (i * 7).toByte()
    }

    private val sampleTimestamp = 1714500000000L // Fixed timestamp for tests
    private val sampleExpiry = 1714600000000L

    @Test
    fun `buildPayload produces correct size`() {
        val payload = KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = sampleAccessKey,
            scope = AuthorizationScope.FULL_ACCESS,
            timestampMs = sampleTimestamp,
        )

        assertEquals(KeyAuthorization.PAYLOAD_SIZE, payload.size)
    }

    @Test
    fun `roundtrip - buildPayload then parsePayload recovers all fields`() {
        val scope = AuthorizationScope.TRANSACT
        val payload = KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = sampleAccessKey,
            scope = scope,
            timestampMs = sampleTimestamp,
            expiryMs = sampleExpiry,
        )

        val parsed = KeyAuthorization.parsePayload(payload)

        assertArrayEquals(sampleRootKey, parsed.rootPublicKey)
        assertArrayEquals(sampleAccessKey, parsed.accessPublicKey)
        assertEquals(scope.flags, parsed.scope.flags)
        assertEquals(sampleTimestamp, parsed.timestampMs)
        assertEquals(sampleExpiry, parsed.expiryMs)
    }

    @Test
    fun `no expiry roundtrips correctly`() {
        val payload = KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = sampleAccessKey,
            scope = AuthorizationScope.READ_ONLY,
            timestampMs = sampleTimestamp,
            expiryMs = KeyAuthorization.NO_EXPIRY,
        )

        val parsed = KeyAuthorization.parsePayload(payload)

        assertFalse(parsed.hasExpiry)
        assertFalse(parsed.isExpired())
    }

    @Test
    fun `expiry detection works`() {
        val payload = KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = sampleAccessKey,
            scope = AuthorizationScope.FULL_ACCESS,
            timestampMs = sampleTimestamp,
            expiryMs = sampleExpiry,
        )

        val parsed = KeyAuthorization.parsePayload(payload)

        assertTrue(parsed.hasExpiry)
        // Before expiry
        assertFalse(parsed.isExpired(nowMs = sampleExpiry - 1))
        // At expiry
        assertTrue(parsed.isExpired(nowMs = sampleExpiry))
        // After expiry
        assertTrue(parsed.isExpired(nowMs = sampleExpiry + 1))
    }

    @Test
    fun `hashPayload produces 32-byte SHA-256`() {
        val payload = KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = sampleAccessKey,
            scope = AuthorizationScope.FULL_ACCESS,
            timestampMs = sampleTimestamp,
        )

        val hash = KeyAuthorization.hashPayload(payload)

        assertEquals(32, hash.size)
    }

    @Test
    fun `hashPayload is deterministic`() {
        val payload = KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = sampleAccessKey,
            scope = AuthorizationScope.FULL_ACCESS,
            timestampMs = sampleTimestamp,
        )

        val hash1 = KeyAuthorization.hashPayload(payload)
        val hash2 = KeyAuthorization.hashPayload(payload)

        assertArrayEquals(hash1, hash2)
    }

    @Test
    fun `different scopes produce different payloads`() {
        val p1 = KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = sampleAccessKey,
            scope = AuthorizationScope.READ_ONLY,
            timestampMs = sampleTimestamp,
        )
        val p2 = KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = sampleAccessKey,
            scope = AuthorizationScope.FULL_ACCESS,
            timestampMs = sampleTimestamp,
        )

        assertFalse(p1.contentEquals(p2))
    }

    @Test
    fun `scope permission flags work correctly`() {
        val scope = AuthorizationScope(AuthorizationScope.SILENT or AuthorizationScope.NOTIFY)

        assertTrue(scope.hasPermission(AuthorizationScope.SILENT))
        assertTrue(scope.hasPermission(AuthorizationScope.NOTIFY))
        assertFalse(scope.hasPermission(AuthorizationScope.APPROVE))
    }

    @Test
    fun `FULL_ACCESS has all permissions`() {
        assertTrue(AuthorizationScope.FULL_ACCESS.hasPermission(AuthorizationScope.SILENT))
        assertTrue(AuthorizationScope.FULL_ACCESS.hasPermission(AuthorizationScope.NOTIFY))
        assertTrue(AuthorizationScope.FULL_ACCESS.hasPermission(AuthorizationScope.APPROVE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildPayload rejects wrong root key size`() {
        KeyAuthorization.buildPayload(
            rootPublicKey = ByteArray(32), // should be 33
            accessPublicKey = sampleAccessKey,
            scope = AuthorizationScope.FULL_ACCESS,
            timestampMs = sampleTimestamp,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildPayload rejects wrong access key size`() {
        KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = ByteArray(65), // should be 33
            scope = AuthorizationScope.FULL_ACCESS,
            timestampMs = sampleTimestamp,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildPayload rejects expiry before timestamp`() {
        KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = sampleAccessKey,
            scope = AuthorizationScope.FULL_ACCESS,
            timestampMs = sampleTimestamp,
            expiryMs = sampleTimestamp - 1,
        )
    }

    @Test
    fun `payload starts with magic bytes`() {
        val payload = KeyAuthorization.buildPayload(
            rootPublicKey = sampleRootKey,
            accessPublicKey = sampleAccessKey,
            scope = AuthorizationScope.FULL_ACCESS,
            timestampMs = sampleTimestamp,
        )

        val magic = String(payload.copyOfRange(0, 13), Charsets.US_ASCII)
        assertEquals("KUIRA-AUTH-V1", magic)
    }
}
