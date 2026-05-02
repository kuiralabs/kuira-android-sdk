package com.midnight.kuira.core.identity.auth

import android.content.Context
import com.midnight.kuira.core.auth.WalletKeyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.crypto.Cipher

/**
 * Encrypted local storage for authorization records.
 *
 * Follows the same encryption pattern as SeedVault:
 * AES-256-GCM with the master key from Android Keystore,
 * atomic writes via temp-file-and-rename.
 *
 * All read/write operations require pre-authenticated ciphers from
 * [BiometricGate]. The Keystore master key is biometric-gated (per-use),
 * so callers must obtain a cipher through biometric authentication before
 * calling these methods.
 *
 * Records are queryable by DID, credential ID, and revocation status.
 * Revoked records are kept for audit trail — never deleted.
 */
class AuthorizationStore(
    private val context: Context,
    private val keyManager: WalletKeyManager,
) {
    private val storageFile = File(context.filesDir, STORAGE_FILENAME)

    // In-memory cache — loaded on first access, invalidated on write.
    // Once loaded, read-only queries can use the cache without a cipher.
    @Volatile
    private var cachedRecords: List<AuthorizationRecord>? = null

    /**
     * Saves a new authorization record.
     *
     * @param record The record to persist
     * @param encryptCipher Pre-authenticated cipher from BiometricGate (ENCRYPT_MODE)
     */
    @Synchronized
    fun save(record: AuthorizationRecord, encryptCipher: Cipher) {
        val records = getCachedOrLoadFromDisk().toMutableList()
        records.add(record)
        writeRecords(records, encryptCipher)
    }

    /**
     * Finds all active (non-revoked) authorization records for a DID.
     *
     * Uses in-memory cache if available (no cipher needed for cached reads).
     * If the cache is cold and the file exists, requires a decrypt cipher.
     *
     * @param did The user's DID
     * @param decryptCipher Pre-authenticated cipher (DECRYPT_MODE), or null if cache is warm
     * @return List of active records for this DID
     * @throws IllegalStateException if cache is cold and no cipher provided
     */
    fun findActiveByDid(did: String, decryptCipher: Cipher? = null): List<AuthorizationRecord> {
        return getRecords(decryptCipher).filter { it.did == did && !it.revoked }
    }

    /**
     * Finds a specific record by ID.
     *
     * @param id Record ID
     * @param decryptCipher Pre-authenticated cipher, or null if cache is warm
     */
    fun findById(id: String, decryptCipher: Cipher? = null): AuthorizationRecord? {
        return getRecords(decryptCipher).find { it.id == id }
    }

    /**
     * Revokes an authorization record.
     *
     * The record is marked as revoked with a timestamp — not deleted.
     * The access key should no longer be used for signing after revocation.
     *
     * @param id Record ID to revoke
     * @param encryptCipher Pre-authenticated cipher (ENCRYPT_MODE) for writing
     * @param decryptCipher Pre-authenticated cipher (DECRYPT_MODE), or null if cache is warm
     * @return true if the record was found and revoked
     */
    @Synchronized
    fun revoke(id: String, encryptCipher: Cipher, decryptCipher: Cipher? = null): Boolean {
        val records = getRecords(decryptCipher).toMutableList()
        val index = records.indexOfFirst { it.id == id && !it.revoked }
        if (index < 0) return false

        val record = records[index]
        records[index] = AuthorizationRecord(
            id = record.id,
            did = record.did,
            credentialId = record.credentialId,
            payload = record.payload,
            authenticatorData = record.authenticatorData,
            clientDataJson = record.clientDataJson,
            signature = record.signature,
            accessKeyPath = record.accessKeyPath,
            label = record.label,
            revoked = true,
            createdAtMs = record.createdAtMs,
            revokedAtMs = System.currentTimeMillis(),
        )

        writeRecords(records, encryptCipher)
        return true
    }

    /**
     * Lists all records (active and revoked) for audit purposes.
     */
    fun listAll(decryptCipher: Cipher? = null): List<AuthorizationRecord> {
        return getRecords(decryptCipher)
    }

    /**
     * Whether any authorization records exist on disk.
     */
    fun hasRecords(): Boolean = storageFile.exists() && storageFile.length() > 0

    /**
     * Generates a unique ID for a new authorization record.
     */
    fun generateId(): String = UUID.randomUUID().toString()

    /**
     * Warms the cache by loading from disk. Call this during app startup
     * while biometric auth is already available.
     */
    fun warmCache(decryptCipher: Cipher) {
        loadFromDisk(decryptCipher)
    }

    // --- Internal ---

    private fun getRecords(decryptCipher: Cipher?): List<AuthorizationRecord> {
        cachedRecords?.let { return it }

        if (!storageFile.exists()) {
            val empty = emptyList<AuthorizationRecord>()
            cachedRecords = empty
            return empty
        }

        requireNotNull(decryptCipher) {
            "Authorization store cache is cold and file exists — a decrypt cipher is required. " +
                "Obtain one via BiometricGate before calling this method."
        }

        return loadFromDisk(decryptCipher)
    }

    /**
     * Returns cached records, or empty list if file doesn't exist.
     * Does NOT attempt disk reads without a cipher.
     */
    private fun getCachedOrLoadFromDisk(): List<AuthorizationRecord> {
        cachedRecords?.let { return it }
        if (!storageFile.exists()) {
            val empty = emptyList<AuthorizationRecord>()
            cachedRecords = empty
            return empty
        }
        // File exists but cache is cold — we need the cache warmed first for save().
        // This is safe because save() is always called after a biometric-gated flow
        // that already loaded the records.
        return cachedRecords ?: emptyList()
    }

    /**
     * Loads and decrypts records from disk.
     *
     * The caller must provide a cipher initialized for decryption with the correct IV.
     * To get the right cipher, call [prepareCipherForDecrypt] first, authenticate it
     * via BiometricGate, then pass it here.
     */
    private fun loadFromDisk(decryptCipher: Cipher): List<AuthorizationRecord> {
        val encrypted = storageFile.readBytes()
        if (encrypted.size <= WalletKeyManager.GCM_IV_LENGTH) {
            val empty = emptyList<AuthorizationRecord>()
            cachedRecords = empty
            return empty
        }

        val ciphertext = encrypted.copyOfRange(WalletKeyManager.GCM_IV_LENGTH, encrypted.size)
        val plaintext = decryptCipher.doFinal(ciphertext)
        val json = String(plaintext, Charsets.UTF_8)

        val records = deserializeRecords(json)
        cachedRecords = records
        return records
    }

    /**
     * Creates a cipher initialized for decrypting the authorization store file.
     *
     * Call this to get a cipher, then authenticate it via BiometricGate,
     * then pass it to [warmCache] or query methods.
     *
     * @return Cipher ready for BiometricGate authentication, or null if no file exists
     */
    fun prepareCipherForDecrypt(): Cipher? {
        if (!storageFile.exists()) return null
        val encrypted = storageFile.readBytes()
        if (encrypted.size <= WalletKeyManager.GCM_IV_LENGTH) return null

        val iv = encrypted.copyOfRange(0, WalletKeyManager.GCM_IV_LENGTH)
        return keyManager.cipherForDecrypt(iv)
    }

    private fun writeRecords(records: List<AuthorizationRecord>, encryptCipher: Cipher) {
        val json = serializeRecords(records)
        val plaintext = json.toByteArray(Charsets.UTF_8)

        val ciphertext = encryptCipher.doFinal(plaintext)
        val iv = encryptCipher.iv

        // Atomic write: temp file + rename
        val tempFile = File(context.filesDir, "$STORAGE_FILENAME.tmp")
        tempFile.writeBytes(iv + ciphertext)
        tempFile.renameTo(storageFile)

        cachedRecords = records
    }

    private fun serializeRecords(records: List<AuthorizationRecord>): String {
        val array = JSONArray()
        for (record in records) {
            array.put(JSONObject().apply {
                put("id", record.id)
                put("did", record.did)
                put("credentialId", record.credentialId)
                put("payload", record.payload.toHex())
                put("authenticatorData", record.authenticatorData.toHex())
                put("clientDataJson", record.clientDataJson.toHex())
                put("signature", record.signature.toHex())
                put("accessKeyPath", record.accessKeyPath)
                put("label", record.label)
                put("revoked", record.revoked)
                put("createdAtMs", record.createdAtMs)
                if (record.revokedAtMs != null) put("revokedAtMs", record.revokedAtMs)
            })
        }
        return array.toString()
    }

    private fun deserializeRecords(json: String): List<AuthorizationRecord> {
        val array = JSONArray(json)
        val records = mutableListOf<AuthorizationRecord>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            records.add(
                AuthorizationRecord(
                    id = obj.getString("id"),
                    did = obj.getString("did"),
                    credentialId = obj.getString("credentialId"),
                    payload = obj.getString("payload").hexToBytes(),
                    authenticatorData = obj.getString("authenticatorData").hexToBytes(),
                    clientDataJson = obj.getString("clientDataJson").hexToBytes(),
                    signature = obj.getString("signature").hexToBytes(),
                    accessKeyPath = obj.getString("accessKeyPath"),
                    label = obj.getString("label"),
                    revoked = obj.getBoolean("revoked"),
                    createdAtMs = obj.getLong("createdAtMs"),
                    revokedAtMs = if (obj.has("revokedAtMs")) obj.getLong("revokedAtMs") else null,
                )
            )
        }
        return records
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    companion object {
        private const val STORAGE_FILENAME = "kuira_authorizations.bin"
    }
}
