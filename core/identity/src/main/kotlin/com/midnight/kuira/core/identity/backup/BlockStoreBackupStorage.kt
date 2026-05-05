package com.midnight.kuira.core.identity.backup

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.StoreBytesData
import kotlinx.coroutines.tasks.await

/**
 * Google Block Store implementation of [BackupStorage].
 *
 * Block Store provides encrypted device-to-device credential transfer
 * via Google Play Services. Data is E2E encrypted on API 29+ when the
 * user has a screen lock configured.
 *
 * Privacy: our blob is ALREADY encrypted with AES-256-GCM (PRF-derived key)
 * before being stored. Block Store's E2E encryption is an additional layer.
 * Even if Block Store's encryption is compromised, the inner layer protects
 * the seed.
 *
 * Limits: 4KB per entry, up to 16 entries. Our blob is 525 bytes — well within.
 */
class BlockStoreBackupStorage(
    private val context: Context,
) : BackupStorage {

    override suspend fun store(encryptedBlob: ByteArray) {
        val client = Blockstore.getClient(context)

        val isE2ee = try {
            client.isEndToEndEncryptionAvailable.await()
        } catch (e: Exception) {
            Log.w(TAG, "E2E encryption check failed: ${e.message}")
            false
        }

        Log.i(TAG, "Storing ${encryptedBlob.size} bytes, E2E available: $isE2ee, cloud backup: $isE2ee")

        val storeRequest = StoreBytesData.Builder()
            .setBytes(encryptedBlob)
            .setShouldBackupToCloud(isE2ee)
            .build()

        try {
            val result = client.storeBytes(storeRequest).await()
            Log.i(TAG, "Block Store storeBytes returned: $result")
        } catch (e: Exception) {
            Log.e(TAG, "Block Store storeBytes FAILED: ${e.message}", e)
            throw e
        }
    }

    override suspend fun retrieve(): ByteArray? {
        val client = Blockstore.getClient(context)
        return try {
            val result = client.retrieveBytes().await()
            Log.i(TAG, "Block Store retrieveBytes returned ${result.size} bytes")
            if (result.isNotEmpty()) result else null
        } catch (e: Exception) {
            Log.w(TAG, "Block Store retrieveBytes failed: ${e.message}")
            null
        }
    }

    override suspend fun delete() {
        val client = Blockstore.getClient(context)
        try {
            val emptyData = StoreBytesData.Builder()
                .setBytes(ByteArray(0))
                .build()
            client.storeBytes(emptyData).await()
        } catch (_: Exception) {
            // Best-effort deletion
        }
    }

    override suspend fun isAvailable(): Boolean {
        return try {
            Blockstore.getClient(context)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "BlockStore"
    }
}
