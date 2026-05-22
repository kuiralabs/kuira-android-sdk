package com.midnight.kuira.core.identity.backup

/**
 * Thrown by seed-derivation paths (and sigil-derivation paths) that
 * require the user to have a forged passkey.
 *
 * `WalletSeedSource` and `SigilIdentityProvider` both throw this when
 * the sigil prefs are empty. Consumers translate to whatever UI
 * affordance they own: the wallet panel emits
 * `WalletStatus.SigilRequired`; the Kicks activity surfaces "Forge
 * your sigil first (tap the sigil chip up top)."; custom dApps render
 * their own gate.
 *
 * Lives in this package because it sits at the seam between identity
 * (where the sigil is born) and backup/seed-derivation (where it's
 * required). Stays as a checked-via-catch exception so callers can
 * intercept it specifically without swallowing real failures.
 */
class SigilRequiredException(
    message: String = "No sigil found — forge a passkey first",
) : Exception(message)
