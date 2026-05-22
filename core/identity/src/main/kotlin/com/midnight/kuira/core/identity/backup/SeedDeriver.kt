package com.midnight.kuira.core.identity.backup

import android.app.Activity
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Derives a deterministic 32-byte seed (BIP-39 entropy) from the
 * user's passkey via the WebAuthn PRF extension.
 *
 * The same passkey + same salt produces the same 32 bytes on every
 * device + every Kuira ecosystem app that shares the RP via
 * `assetlinks.json` — no shared backup blob needed.
 *
 * SEED_SALT is domain-separated from SigilBackup.BACKUP_SALT; PRF
 * with two different salts produces two independent secrets from
 * the same passkey credential.
 */
object SeedDeriver {

    /** Purpose-bound salt: SHA-256("kuira:seed:v1"). Deterministic, public. */
    val SEED_SALT: ByteArray = MessageDigest.getInstance("SHA-256")
        .digest("kuira:seed:v1".toByteArray(Charsets.UTF_8))

    /**
     * Authenticate the user's passkey with the seed-derivation salt
     * and return the raw 32-byte PRF output. The output is BIP-39
     * entropy size — callers can pass it directly to `BIP39.entropyToMnemonic`.
     *
     * Throws [BackupException] when the authenticator doesn't support
     * the PRF extension (rare on Android — Google Password Manager
     * supports it by default).
     */
    suspend fun derivePrfEntropy(
        activity: Activity,
        passkeyManager: PasskeyManager,
    ): ByteArray {
        val challenge = ByteArray(CHALLENGE_SIZE).also { SecureRandom().nextBytes(it) }
        val result = passkeyManager.authenticateWithPrf(
            activity = activity,
            challenge = challenge,
            prfSalt = SEED_SALT,
        )
        return result.prfOutput
            ?: throw BackupException("PRF not available — authenticator does not support PRF extension")
    }

    private const val CHALLENGE_SIZE = 32
}
