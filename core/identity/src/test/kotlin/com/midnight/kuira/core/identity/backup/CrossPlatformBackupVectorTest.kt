package com.midnight.kuira.core.identity.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Holds the backup blob format stable across Android and iOS.
 *
 * The two platforms encrypt with independent code — Android via `javax.crypto`, iOS via
 * CryptoKit — so nothing but a test stops one from drifting. Both halves matter:
 *
 * - [derivedKeysAreStableAcrossPlatforms] and [appStateBlobsCarryTheFramingPrefixAndDustDoesNot]
 *   hold the key derivation and the wire layout in place.
 * - [opensBlobsSwiftWrote] opens blobs iOS wrote, which is the direction a user hits: a backup
 *   made on iOS, restored on Android.
 * - [printVectors] is the regeneration helper; its output is pinned in the iOS suite
 *   (`AndroidWrittenBlobTests`) so iOS is verified against bytes Android actually wrote.
 *
 * Regenerate the pinned hex on both sides together if the format ever legitimately changes.
 */
class CrossPlatformBackupVectorTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /**
     * The derived keys are the root of the whole contract — every blob either side writes is
     * sealed under them. These same 32 bytes come out of the iOS `BackupKeyDeriver`, so a change
     * to the HKDF inputs on either platform silently breaks restore unless this fails first.
     */
    @Test
    fun derivedKeysAreStableAcrossPlatforms() {
        val seed = ByteArray(32) { 0xA1.toByte() }
        assertEquals(
            "8b2a4335a3fc3a02dfff4db6e7bef3f7e2e4dbdbd81ef4aeb7dd132c8d18f6b3",
            SeedDerivedKeyDeriver.deriveDustBackupKey(seed).hex(),
        )
        assertEquals(
            "5ef879c70f3c7c9296150f73f5689798bb83e7d79619e41898f4198d31b4b492",
            SeedDerivedKeyDeriver.deriveAppStateBackupKey(seed).hex(),
        )
    }

    /**
     * App-state frames its plaintext as `[metadataLen: 2 bytes BE][payload]` before sealing;
     * dust does not. The IVs are random, so the blobs differ every run, but that two-byte
     * difference in length is fixed — and an iOS reader that skipped the framing still decrypted
     * and authenticated successfully, returning corrupt data. Length is the cheap tripwire.
     */
    @Test
    fun appStateBlobsCarryTheFramingPrefixAndDustDoesNot() {
        val seed = ByteArray(32) { 0xA1.toByte() }
        val payload = "kuira backup vector".toByteArray(Charsets.UTF_8)
        val dustKey = SeedDerivedKeyDeriver.deriveDustBackupKey(seed)
        val appKey = SeedDerivedKeyDeriver.deriveAppStateBackupKey(seed)

        val dust = DustBackupEncryptor.encrypt(dustKey, payload)
        val appState = AppStateBackupEncryptor.encrypt(appKey, payload)
        assertEquals(1 + 12 + payload.size + 16, dust.size)
        assertEquals(dust.size + 2, appState.size)

        assertEquals(1 + 12 + 16, DustBackupEncryptor.encrypt(dustKey, ByteArray(0)).size)
        assertEquals(1 + 12 + 2 + 16, AppStateBackupEncryptor.encrypt(appKey, ByteArray(0)).size)
    }

    /**
     * Prints blobs from the real encryptors for pinning in the iOS suite.
     *
     * Deliberately NOT a `@Test`. It asserts nothing, and it cannot: the IVs are random, so the
     * blobs differ every run. Everything about them that IS assertable already is -- by
     * [derivedKeysAreStableAcrossPlatforms], [appStateBlobsCarryTheFramingPrefixAndDustDoesNot] and
     * [opensBlobsSwiftWrote] here, plus the round trips in `DustBackupTest` and
     * `AppStateBackupEncryptorTest`. Carrying it as an ignored test only kept the unit suite's skip
     * count off zero, which costs the signal: a genuinely masked test added later hides inside a
     * number that already looks normal.
     *
     * To regenerate: annotate it with `@Test`, run it, paste the output into the iOS
     * `AndroidWrittenBlobTests`, then remove the annotation again.
     */
    fun printVectors() {
        val seed = ByteArray(32) { 0xA1.toByte() }
        val payload = "kuira backup vector".toByteArray(Charsets.UTF_8)
        val dustKey = SeedDerivedKeyDeriver.deriveDustBackupKey(seed)
        val appKey = SeedDerivedKeyDeriver.deriveAppStateBackupKey(seed)

        println("ANDROID-VECTOR dustBlob     = ${DustBackupEncryptor.encrypt(dustKey, payload).hex()}")
        println("ANDROID-VECTOR appStateBlob = ${AppStateBackupEncryptor.encrypt(appKey, payload).hex()}")
        println("ANDROID-VECTOR dustEmpty    = ${DustBackupEncryptor.encrypt(dustKey, ByteArray(0)).hex()}")
        println("ANDROID-VECTOR appEmpty     = ${AppStateBackupEncryptor.encrypt(appKey, ByteArray(0)).hex()}")
    }

    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Blobs written by KuiraBackup's `BackupEncryptor` over the same seed and payload. The empty
     * cases are here deliberately: a minimum-size guard written with `>` instead of `>=` rejects
     * a perfectly valid 29-byte dust blob, which is exactly the defect this caught on iOS.
     */
    @Test
    fun opensBlobsSwiftWrote() {
        val seed = ByteArray(32) { 0xA1.toByte() }
        val payload = "kuira backup vector".toByteArray(Charsets.UTF_8)
        val dustKey = SeedDerivedKeyDeriver.deriveDustBackupKey(seed)
        val appKey = SeedDerivedKeyDeriver.deriveAppStateBackupKey(seed)

        val swiftDust = "01e8c8625d62ec86dfe60a8643c83086daae98affd70f810db32f54e4289d3baa88de9d56b30f5f521a771ba46deb99f"
        val swiftAppState = "02a3fafad6efdd0949d10c4aea987495e398dc73fd78975dafe47f789d98ef3b6fe8fffa809f9f8711ab5b452b76c9a9db2f"
        val swiftDustEmpty = "019bd2c69f18d4d23d121a8419ba28067197c690ae97d1fbe0ec6cf66b"
        val swiftAppEmpty = "02d0c8e6abae1960e0db7d9a5c738ccb84d451ce932d713aae8b0d2847fcc9"

        assertArrayEquals(payload, DustBackupEncryptor.decrypt(dustKey, swiftDust.unhex()))
        assertArrayEquals(payload, AppStateBackupEncryptor.decrypt(blob = swiftAppState.unhex(), aesKey = appKey))
        assertArrayEquals(ByteArray(0), DustBackupEncryptor.decrypt(dustKey, swiftDustEmpty.unhex()))
        assertArrayEquals(ByteArray(0), AppStateBackupEncryptor.decrypt(blob = swiftAppEmpty.unhex(), aesKey = appKey))
    }
}
