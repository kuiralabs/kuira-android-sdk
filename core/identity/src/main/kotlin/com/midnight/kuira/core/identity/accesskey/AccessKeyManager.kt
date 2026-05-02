package com.midnight.kuira.core.identity.accesskey

import com.midnight.kuira.core.crypto.bip32.DerivedKey
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole

/**
 * Manages secp256k1 access keys derived from the HD wallet.
 *
 * Access keys are the bridge between P-256 passkeys and Midnight's secp256k1 signing.
 * The passkey can't sign Midnight transactions (wrong curve), so the access key does it.
 * The passkey authorizes the access key via [KeyAuthorization].
 *
 * Derivation path: `m/44'/2400'/account'/5/index`
 * - Role 5 = IDENTITY (Kuira-specific, beyond the standard Midnight roles 0-4)
 * - Index 0 = default access key
 * - Index 1+ = per-dApp access keys (future)
 *
 * Because the key is HD-derived, it's deterministically recoverable from the seed —
 * no separate backup needed for the access key itself.
 */
class AccessKeyManager(
    private val hdWallet: HDWallet,
    private val accountIndex: Int = DEFAULT_ACCOUNT_INDEX,
) {
    /**
     * Derives the default access key at index 0.
     *
     * This is the primary signing key for the sigil — used for all Midnight
     * transactions unless per-dApp keys are configured.
     *
     * @return [DerivedKey] with 32-byte secp256k1 private key and 33-byte compressed public key
     */
    fun deriveDefaultAccessKey(): DerivedKey {
        return deriveAccessKeyAt(DEFAULT_KEY_INDEX)
    }

    /**
     * Derives an access key at a specific index.
     *
     * Use different indices for per-dApp isolation (future).
     *
     * @param index Key index (0 = default, 1+ = per-dApp)
     * @return [DerivedKey] at path `m/44'/2400'/account'/5/index`
     */
    fun deriveAccessKeyAt(index: Int): DerivedKey {
        return hdWallet
            .selectAccount(accountIndex)
            .selectRole(MidnightKeyRole.fromIndex(IDENTITY_ROLE_INDEX))
            .deriveKeyAt(index)
    }

    companion object {
        /**
         * Role index for identity/access keys.
         * Beyond the standard Midnight roles (0=NightExternal, 1=NightInternal,
         * 2=Dust, 3=Zswap, 4=Metadata).
         */
        const val IDENTITY_ROLE_INDEX = 5

        private const val DEFAULT_ACCOUNT_INDEX = 0
        private const val DEFAULT_KEY_INDEX = 0
    }
}
