package com.midnight.kuira.core.identity.backup

/**
 * Storage-agnostic interface for encrypted backup blobs.
 *
 * The encryption layer (PrfKeyDeriver + AppStateBackupEncryptor) is independent
 * of storage. This interface allows swapping Google Block Store for
 * Google Drive, a personal server, or even a QR code export.
 */
interface BackupStorage {
    /** Store an encrypted blob. Overwrites any existing backup. */
    suspend fun store(encryptedBlob: ByteArray)

    /** Retrieve the stored encrypted blob, or null if no backup exists. */
    suspend fun retrieve(): ByteArray?

    /** Delete the stored backup. */
    suspend fun delete()

    /** Whether the storage backend is available on this device. */
    suspend fun isAvailable(): Boolean
}
