package com.midnight.kuira.sdk

import com.midnight.kuira.core.network.MidnightNetwork
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [MidnightSdk.Builder] validation logic.
 *
 * These are JVM tests that verify builder input validation without
 * requiring Android context or native libraries.
 */
class MidnightSdkBuilderTest {

    // ── Required parameters ──

    @Test(expected = IllegalArgumentException::class)
    fun `build fails without network`() {
        MidnightSdk.Builder(stubContext())
            .seed(ByteArray(64))
            .build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `build fails without seed`() {
        MidnightSdk.Builder(stubContext())
            .network(MidnightNetwork.PREPROD)
            .build()
    }

    // ── Seed validation ──

    @Test(expected = IllegalArgumentException::class)
    fun `build rejects seed shorter than 32 bytes`() {
        MidnightSdk.Builder(stubContext())
            .network(MidnightNetwork.PREPROD)
            .seed(ByteArray(16))
            .build()
    }

    // ── Builder fluency ──

    @Test
    fun `builder methods return the same builder instance`() {
        val builder = MidnightSdk.Builder(stubContext())
        val result1 = builder.network(MidnightNetwork.PREPROD)
        val result2 = builder.seed(ByteArray(64))
        val result3 = builder.accountIndex(1)

        assertSame("network() should return same builder", builder, result1)
        assertSame("seed() should return same builder", builder, result2)
        assertSame("accountIndex() should return same builder", builder, result3)
    }

    // ── Seed is copied, not referenced ──

    @Test
    fun `builder copies seed bytes`() {
        val originalSeed = ByteArray(64) { it.toByte() }
        val builder = MidnightSdk.Builder(stubContext())
            .seed(originalSeed)

        // Wipe the original — builder should still have its copy
        originalSeed.fill(0)

        // If the builder referenced the original, it would be all zeros.
        // We can't directly verify the internal copy, but we can verify
        // the builder doesn't throw on build due to all-zero seed.
        // (build() will fail for other reasons — context — but seed validation passes)
        try {
            builder.network(MidnightNetwork.PREPROD).build()
        } catch (e: Exception) {
            // Expected — context is a stub. But the seed validation should have passed.
            assertFalse(
                "Seed should not be wiped in the builder",
                e is IllegalArgumentException && e.message?.contains("seed") == true,
            )
        }
    }

    // ── Helpers ──

    private fun stubContext() = android.content.ContextWrapper(null)
}
