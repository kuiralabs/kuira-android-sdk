package com.midnight.kuira.core.identity.backup

import android.app.Activity
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Derives wallet seed material from the user's passkey via the
 * WebAuthn PRF extension.
 *
 * Two outputs sit at different levels of the chain:
 *  - [derivePrfEntropy] — raw 32-byte PRF output. Suitable as
 *    BIP-39 entropy, an HKDF source, a symmetric key, etc.
 *  - [deriveBip39Seed] — full chain: PRF → 32-byte entropy →
 *    24-word mnemonic → 64-byte BIP-39 PBKDF2 seed. This is what
 *    `MidnightSdk.Builder.seed(...)` consumes.
 *
 * The PRF output is identical on every device + every Kuira
 * ecosystem app that shares the RP via `assetlinks.json`, so the
 * derived seed is identical too — no shared backup blob needed.
 *
 * SEED_SALT is domain-separated from SigilBackup.BACKUP_SALT; PRF
 * with two different salts produces two independent secrets from
 * the same passkey credential.
 *
 * **Known limitation:** the entropy → seed chain temporarily
 * materializes the 24-word mnemonic as a `String`. JVM strings are
 * immutable and may be copied/interned by the allocator before GC
 * can reclaim them — same trap as the existing
 * `WalletPanelViewModel.ensureSeedReady` random-seed path. A direct
 * `entropyToSeed` path would close this gap; deferred until both
 * consumers can migrate in one pass.
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

    /**
     * One-shot: passkey PRF → BIP-39 entropy → mnemonic → BIP-39 seed.
     *
     * The returned 64-byte seed is what `MidnightSdk.Builder.seed(...)`
     * consumes. Identical on every device + every Kuira ecosystem app
     * that shares the RP, because PRF is deterministic in
     * (passkey, salt) and the entropy → seed chain is purely
     * deterministic.
     *
     * **Caller MUST wipe** the returned ByteArray once the SDK has
     * copied it — same contract as [PlaintextSeed.bip39Seed].
     */
    suspend fun deriveBip39Seed(
        activity: Activity,
        passkeyManager: PasskeyManager,
    ): ByteArray {
        val material = derivePrfMaterial(activity, passkeyManager)
        material.entropy.fill(0)
        return material.bip39Seed
    }

    /**
     * One-shot derive that returns BOTH the 32-byte BIP-39 entropy and
     * the 64-byte BIP-39 seed from a single PRF authentication.
     *
     * Use this when both halves are needed (e.g. storing a
     * `PlaintextSeed` which carries entropy + seed together) — calling
     * [derivePrfEntropy] then [deriveBip39Seed] would trigger two
     * biometric prompts because each call re-runs Credential Manager.
     *
     * **Caller MUST wipe both arrays** once the consumer has copied
     * them — call [PrfSeedMaterial.wipe] or wipe the fields directly.
     */
    suspend fun derivePrfMaterial(
        activity: Activity,
        passkeyManager: PasskeyManager,
    ): PrfSeedMaterial {
        val entropy = derivePrfEntropy(activity, passkeyManager)
        val seed = entropyToBip39Seed(entropy)
        return PrfSeedMaterial(entropy, seed)
    }

    /**
     * Deterministic chain from BIP-39 entropy to BIP-39 seed.
     *
     * Extracted so unit tests can pin the (entropy → seed) mapping
     * without standing up Credential Manager. Returns the standard
     * BIP-39 64-byte PBKDF2 seed (see `BIP39.mnemonicToSeed`).
     *
     * **Caller MUST wipe** the returned ByteArray once the consumer
     * has copied it — same contract as [deriveBip39Seed] and
     * [PlaintextSeed.bip39Seed].
     */
    internal fun entropyToBip39Seed(entropy: ByteArray): ByteArray {
        require(entropy.size == BIP39_ENTROPY_SIZE) {
            "BIP-39 entropy must be $BIP39_ENTROPY_SIZE bytes, got ${entropy.size}"
        }
        val mnemonic = BIP39.entropyToMnemonic(entropy)
        return BIP39.mnemonicToSeed(mnemonic)
    }

    private const val CHALLENGE_SIZE = 32

    /** BIP-39 entropy size for a 24-word mnemonic. */
    private const val BIP39_ENTROPY_SIZE = 32
}

/**
 * Both halves of the passkey-PRF derivation: the 32-byte BIP-39
 * entropy (= raw PRF output) and the 64-byte BIP-39 seed. Returned
 * together by [SeedDeriver.derivePrfMaterial] so consumers needing
 * both don't pay two biometric prompts.
 */
class PrfSeedMaterial(
    val entropy: ByteArray,
    val bip39Seed: ByteArray,
) {
    /** Zeroes both byte arrays. Idempotent. */
    fun wipe() {
        entropy.fill(0)
        bip39Seed.fill(0)
    }
}
