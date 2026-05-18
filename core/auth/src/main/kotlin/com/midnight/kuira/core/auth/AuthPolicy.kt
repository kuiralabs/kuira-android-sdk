package com.midnight.kuira.core.auth

import android.security.keystore.KeyProperties

/**
 * Centralized authentication policy for biometric-gated Keystore keys.
 *
 * Single source of truth for the auth-validity window and accepted
 * authenticators applied to every user-auth-required Keystore key
 * generated through this codebase. Changing the duration or
 * authenticator set here affects all consumers (today: [WalletKeyManager];
 * future biometric-gated keys should import these constants too).
 *
 * **History:** Until 2026-05-18 the wallet master key used `duration=0`
 * (per-use), which forced a fresh biometric on every Keystore decrypt.
 * After a cloud restore that worked out to 3 prompts in a row
 * (restore-itself + post-relaunch SeedVault load + first wallet op).
 * Moving to a 30s validity window collapses the post-relaunch prompts
 * into the restore one without weakening the model meaningfully — a
 * snatched-unlocked-phone attacker still needs to act inside that 30s
 * window with the device unlocked.
 */
object AuthPolicy {
    /**
     * Auth-validity window in seconds. After any successful
     * BiometricPrompt or device-credential auth, biometric-gated
     * Keystore keys are usable for this many seconds without
     * re-prompting.
     *
     * Trade-off:
     * - `0`     — per-use (strictest). Every Keystore op prompts.
     *             Drove the post-restore triple-prompt UX bug.
     * - `30`    — current default. Matches "user just authenticated"
     *             intent: SeedVault loads done immediately after a
     *             restore, app launch, or signing flow don't
     *             re-prompt, but a stolen unlocked device has at
     *             most 30s of stale auth credit.
     * - `300+`  — convenience-leaning (5min+). Closer to what
     *             consumer wallets do, weaker against snatch
     *             attacks within the window. Reach for this only
     *             with an explicit UX justification.
     *
     * Increase only with security review.
     */
    const val VALIDITY_DURATION_SECONDS: Int = 30

    /**
     * Authenticators that satisfy the auth-validity window. Class 3
     * biometrics (StrongBiometric) gate the key the same way the
     * device's own credential (PIN / pattern / password) does — we
     * accept both so PIN works as a fallback when biometric isn't
     * available (e.g. after too many failed fingerprint attempts).
     */
    val ALLOWED_AUTHENTICATORS: Int =
        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
}
