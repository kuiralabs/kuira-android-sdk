package com.midnight.kuira.sdk.walletseed

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.identity.backup.SeedDeriver
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the wallet's BIP-39 seed.
 *
 * Every Kuira ecosystem consumer that needs the seed —
 * `WalletPanelViewModel`, `BBoardViewModel`, Kicks's
 * `MatchManager`, future agent runtimes — injects this @Singleton
 * and calls [ensureSeedReady]. They all observe the same seed, the
 * same biometric prompt cadence, and the same dev override.
 *
 * **Bootstrap contract:**
 *  1. Dev-seed escape hatch (debug only) — when `local.properties`
 *     defines `kuira.dev.seed`, decode + return without touching the
 *     vault or the passkey. See module-level KDoc in
 *     `build.gradle.kts`.
 *  2. Sigil gate — no passkey forged ⇒ [SigilRequiredException].
 *     Consumers translate this to whatever UI affordance they own
 *     (`WalletStatus.SigilRequired` in the panel, a "forge sigil"
 *     screen in custom dApps, etc.). Throwing here is
 *     defense-in-depth — the host UI should also gate.
 *  3. Cache hit — SeedVault holds a seed AND the
 *     `seedIsPrfDerived` flag is set ⇒ biometric prompt to decrypt,
 *     return the cached PRF-derived seed.
 *  4. Cache miss OR legacy unflagged vault ⇒ wipe the legacy entry
 *     (decision B — legacy random seeds are abandoned, not migrated),
 *     run ONE passkey PRF authentication to derive the new seed, store
 *     in the vault, set the flag, return.
 *
 * **Caller MUST wipe** the returned ByteArray once the SDK builder
 * has copied it (typically in a `finally` block).
 */
@Singleton
class WalletSeedSource @Inject constructor(
    @ApplicationContext context: Context,
    private val seedVault: SeedVault,
    private val walletKeyManager: WalletKeyManager,
    private val passkeyManager: PasskeyManager,
    private val sigilStateStore: SigilStateStore,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Serializes [ensureSeedReady] so two concurrent callers (e.g. the
     * wallet panel's `refreshBalance` and BBoard's `connectWithSdk`
     * firing in the same tick) don't both pass the cache-miss check
     * and double-prompt the user — or worse, race
     * [SeedVault.storeSeed] which throws on the second write.
     *
     * After the first caller stores the seed and sets the PRF flag,
     * subsequent callers cleanly hit the cache-hit branch.
     */
    private val bootstrapMutex = Mutex()

    /**
     * Session lock (#14): force the next [ensureSeedReady] to require a fresh
     * biometric, even if the Keystore auth-validity window is still open.
     * Delegates to [SeedVault.requireFreshAuthNext]; called by `SessionLock`
     * on lock so the unlock genuinely re-authenticates rather than silently
     * re-decrypting within the window.
     */
    fun requireFreshAuthNext() = seedVault.requireFreshAuthNext()

    /**
     * Returns the wallet's BIP-39 seed, deriving it from the user's
     * passkey via PRF if not already cached. See class-level KDoc for
     * the full bootstrap contract.
     */
    suspend fun ensureSeedReady(activity: FragmentActivity): ByteArray = bootstrapMutex.withLock {
        devSeedBytes(BuildConfig.DEBUG, BuildConfig.DEV_SEED_HEX)?.let { devSeed ->
            Log.w(TAG, "DEV: bypassing PRF — using dev seed from local.properties (${devSeed.size} bytes)")
            return@withLock devSeed
        }

        if (!sigilStateStore.hasSigil()) {
            throw SigilRequiredException()
        }

        // Cache hit: vault entry exists AND we wrote the PRF flag the
        // last time we populated it. Without the flag, treat as legacy
        // and force-rederive (decision B).
        if (seedVault.hasSeed() && isVaultPrfDerived()) {
            Log.i(TAG, "Loading PRF-derived seed from SeedVault (biometric prompt)…")
            val plaintext = seedVault.loadSeed(activity)
            return@withLock try {
                plaintext.bip39Seed.copyOf()
            } finally {
                plaintext.wipe()
            }
        }

        // Legacy vault: wipe before storeSeed (which refuses overwrite).
        // The flag was never set on this entry, so by contract it's a
        // pre-PRF random seed. Funds at that address are abandoned —
        // the user accepted this in decision B.
        if (seedVault.hasSeed()) {
            Log.w(TAG, "Legacy random seed detected — wiping and re-deriving from passkey PRF")
            seedVault.deleteSeed()
        } else {
            Log.i(TAG, "No seed in SeedVault — deriving from passkey PRF…")
        }

        // Keystore master key needed for SeedVault's AES-GCM. Idempotent.
        if (!walletKeyManager.hasKey()) {
            val strongBox = walletKeyManager.generateKey()
            Log.i(TAG, "Generated Keystore master key (${if (strongBox) "StrongBox" else "TEE"})")
        }

        // ONE PRF authentication serves both outputs: the 32-byte
        // entropy (= raw PRF output, fills PlaintextSeed.mnemonicEntropy)
        // and the 64-byte BIP-39 seed (entropy → mnemonic → PBKDF2,
        // pure compute — no second biometric prompt).
        val material = SeedDeriver.derivePrfMaterial(activity, passkeyManager)
        var capturedSeed: ByteArray? = null
        try {
            seedVault.storeSeed(activity) {
                // SeedVault wipes the PlaintextSeed it receives, so we
                // hand it owned copies and stash a separate copy of
                // bip39Seed for the caller.
                capturedSeed = material.bip39Seed.copyOf()
                PlaintextSeed(material.entropy.copyOf(), material.bip39Seed.copyOf())
            }
            prefs.edit().putBoolean(KEY_SEED_IS_PRF_DERIVED, true).commit()
        } finally {
            material.wipe()
        }
        requireNotNull(capturedSeed) { "Seed lambda ran but didn't capture (cancelled mid-auth?)" }
    }

    /**
     * Pre-warm SeedVault with a PRF output the caller already
     * derived in a multi-purpose ceremony — used by `SigilSession`
     * to collapse "sign in + first wallet refresh" to one biometric.
     *
     * Flow:
     *  1. If SeedVault already holds a PRF-flagged entry, biometric-
     *     decrypt and return its seed (no new write — the cached
     *     entry is authoritative). This is the cache-hit branch.
     *  2. Legacy unflagged vault → wipe, then go to step 3.
     *  3. Run `entropyToBip39Seed(prfEntropy)` locally (pure compute,
     *     no biometric), store into SeedVault, set the PRF flag.
     *
     * **Caller wipes** the input `prfEntropy` after the call returns —
     * `WalletSeedSource` only reads from it, never retains it.
     *
     * The serialization point with [ensureSeedReady] is the shared
     * [bootstrapMutex] — concurrent calls are safe.
     */
    suspend fun acceptPreDerivedSeed(
        activity: FragmentActivity,
        prfEntropy: ByteArray,
    ): ByteArray = bootstrapMutex.withLock {
        require(prfEntropy.size == 32) { "PRF entropy must be 32 bytes, got ${prfEntropy.size}" }

        if (seedVault.hasSeed() && isVaultPrfDerived()) {
            Log.i(TAG, "acceptPreDerivedSeed: vault already PRF-populated; returning cache")
            val plaintext = seedVault.loadSeed(activity)
            return@withLock try {
                plaintext.bip39Seed.copyOf()
            } finally {
                plaintext.wipe()
            }
        }

        if (seedVault.hasSeed()) {
            Log.w(TAG, "acceptPreDerivedSeed: legacy random seed detected — wiping for PRF replacement")
            seedVault.deleteSeed()
        } else {
            Log.i(TAG, "acceptPreDerivedSeed: populating empty vault with pre-derived PRF entropy")
        }

        if (!walletKeyManager.hasKey()) {
            val strongBox = walletKeyManager.generateKey()
            Log.i(TAG, "Generated Keystore master key (${if (strongBox) "StrongBox" else "TEE"})")
        }

        val bip39Seed = SeedDeriver.entropyToBip39Seed(prfEntropy)
        var capturedSeed: ByteArray? = null
        try {
            seedVault.storeSeed(activity) {
                capturedSeed = bip39Seed.copyOf()
                PlaintextSeed(prfEntropy.copyOf(), bip39Seed.copyOf())
            }
            prefs.edit().putBoolean(KEY_SEED_IS_PRF_DERIVED, true).commit()
        } finally {
            bip39Seed.fill(0)
        }
        requireNotNull(capturedSeed) { "Seed lambda ran but didn't capture (cancelled mid-auth?)" }
    }

    private fun isVaultPrfDerived(): Boolean =
        prefs.getBoolean(KEY_SEED_IS_PRF_DERIVED, false)

    companion object {
        private const val TAG = "WalletSeedSource"

        /**
         * SharedPreferences file. Matches what `WalletPanelViewModel`
         * used pre-extraction so installs upgrading across the
         * refactor see their existing PRF flag without a migration.
         */
        const val PREFS_NAME = "wallet_panel"

        /**
         * True when the SeedVault entry was populated via the passkey
         * PRF path. Absence (with a populated vault) means a legacy
         * random-seed entry that must be wiped + re-derived.
         */
        const val KEY_SEED_IS_PRF_DERIVED = "seedIsPrfDerived"

        /**
         * Dev-only override: when [isDebug] is true and [hex] is a
         * 128-char hex string (64 bytes), decode and return; otherwise
         * return null and let the PRF path run.
         *
         * Extracted so the gate is unit-testable without standing up a
         * BuildConfig fixture. The call site passes
         * [BuildConfig.DEBUG] + [BuildConfig.DEV_SEED_HEX]; tests pass
         * whatever values they need.
         *
         * The 64-byte length is enforced because anything else would
         * silently break BIP-32 derivation downstream — fail loud here
         * instead of producing a wallet at the wrong address.
         */
        fun devSeedBytes(isDebug: Boolean, hex: String): ByteArray? {
            if (!isDebug) return null
            if (hex.isEmpty()) return null
            require(hex.length == DEV_SEED_HEX_LENGTH) {
                "DEV_SEED_HEX must be $DEV_SEED_HEX_LENGTH hex chars (64 bytes); got ${hex.length}"
            }
            require(hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "DEV_SEED_HEX must be hex"
            }
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        private const val DEV_SEED_HEX_LENGTH = 128
    }
}
