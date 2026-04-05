package com.midnight.kuira.core.crypto.state

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores and retrieves dApp private state, encrypted on device.
 *
 * Each contract has its own namespace. Private state is encrypted using
 * Android KeyStore (hardware-backed on supported devices) with AES-GCM.
 */
interface PrivateStateProvider {
    /** Get private state bytes, or null if not stored. */
    fun get(contractAddress: String, stateId: String): ByteArray?

    /** Store private state bytes. Max [MAX_STATE_SIZE_BYTES] per entry. */
    fun set(contractAddress: String, stateId: String, data: ByteArray)

    /** Remove a single state entry. */
    fun remove(contractAddress: String, stateId: String)

    /** Check if a state entry exists. */
    fun has(contractAddress: String, stateId: String): Boolean

    /** Remove all state entries for a contract. */
    fun clearContract(contractAddress: String)

    /**
     * Check if the encryption key is accessible.
     *
     * Returns false after a factory reset or if the KeyStore is corrupted.
     * When false, all previously stored state is unrecoverable — callers
     * should re-initialize dApp state from scratch.
     */
    fun isKeyStoreHealthy(): Boolean

    companion object {
        const val MAX_STATE_SIZE_BYTES = 512 * 1024 // 512KB per entry
    }
}

/**
 * Encrypts dApp private state using Android KeyStore + AES-256-GCM.
 *
 * Data is stored in SharedPreferences as Base64-encoded ciphertext.
 * The encryption key lives in Android KeyStore (never leaves secure hardware).
 *
 * **Single key design:** All dApp state shares one KeyStore alias. This is
 * intentional — Android KeyStore is the trust boundary, not individual aliases.
 * An attacker with KeyStore access can read all aliases equally. Per-contract
 * keys would add complexity without meaningful security benefit.
 *
 * Storage layout:
 * - Pref file: "kuira_private_state"
 * - Key format: "{len}:{contractAddress}:{stateId}" (length-prefixed, collision-safe)
 * - Value format: Base64(ivLen(1) + IV + ciphertext + GCM tag)
 */
class KeyStorePrivateStateProvider(context: Context) : PrivateStateProvider {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(contractAddress: String, stateId: String): ByteArray? {
        val key = storageKey(contractAddress, stateId)
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            decrypt(Base64.decode(encoded, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt state for $stateId: ${e.javaClass.simpleName}")
            null
        }
    }

    override fun set(contractAddress: String, stateId: String, data: ByteArray) {
        require(data.size <= PrivateStateProvider.MAX_STATE_SIZE_BYTES) {
            "State data too large: ${data.size} bytes (max ${PrivateStateProvider.MAX_STATE_SIZE_BYTES})"
        }
        val key = storageKey(contractAddress, stateId)
        val encrypted = encrypt(data)
        prefs.edit()
            .putString(key, Base64.encode(encrypted, Base64.NO_WRAP).decodeToString())
            .commit() // commit() not apply() — ensures write completes before return
    }

    override fun remove(contractAddress: String, stateId: String) {
        prefs.edit()
            .remove(storageKey(contractAddress, stateId))
            .commit()
    }

    override fun has(contractAddress: String, stateId: String): Boolean {
        return prefs.contains(storageKey(contractAddress, stateId))
    }

    override fun clearContract(contractAddress: String) {
        val prefix = "${contractAddress.length}:$contractAddress:"
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(prefix) }
            .forEach { editor.remove(it) }
        editor.commit()
    }

    override fun isKeyStoreHealthy(): Boolean {
        return try {
            getOrCreateKey()
            true
        } catch (e: Exception) {
            Log.w(TAG, "KeyStore unhealthy: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Length-prefixed storage key — prevents collisions even if
     * contractAddress or stateId contains the `:` separator.
     */
    private fun storageKey(contractAddress: String, stateId: String): String =
        "${contractAddress.length}:$contractAddress:$stateId"

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val ivLength = data[0].toInt() and 0xFF
        val iv = data.sliceArray(1..ivLength)
        val ciphertext = data.sliceArray((ivLength + 1) until data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE)
                .build(),
        )
        return keyGen.generateKey()
    }

    companion object {
        private const val TAG = "PrivateStateProvider"
        private const val PREFS_NAME = "kuira_private_state"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "kuira_dapp_state_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val AES_KEY_SIZE = 256
    }
}
