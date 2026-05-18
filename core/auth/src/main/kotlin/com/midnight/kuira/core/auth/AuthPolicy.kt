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
 *
 * ## Threat model for this knob
 *
 * **The window mitigates:** post-restore prompt fatigue; per-action UX
 * friction during the natural "I just authenticated to use the wallet"
 * intent window. No security guarantee was traded away on this axis —
 * the window is *additive convenience over a successful auth*.
 *
 * **The window does NOT mitigate:**
 *   - **Snatched unlocked device during the window** — attacker with
 *     physical control of the unlocked phone within 30s of a real user
 *     auth can trigger Keystore decrypts without re-prompting. Mitigate
 *     downstream with app-level "fresh auth for high-value ops"
 *     (independent `BiometricPrompt` regardless of this window).
 *   - **Compromised OS (rooted, EMM-tampered)** — Keystore software-bound
 *     keys are extractable on a sufficiently compromised device. StrongBox
 *     raises the bar; out of scope for this constant.
 *   - **Malware running as our process** — already has memory access to
 *     anything we decrypt. Defense lives in supply-chain controls + memory
 *     hygiene (see `SeedVault.loadSeed` wipe discipline).
 *   - **Biometric spoofing / shoulder-surfed PIN entry** — orthogonal.
 *     Same threat model with or without the window; the window only
 *     amplifies what the attacker can do *after* successful auth.
 *
 * **Defense layers preserved alongside this change:**
 *   - `setUnlockedDeviceRequired(true)` — key unusable when the device
 *     is locked, regardless of remaining window.
 *   - Per-app Keystore isolation.
 *   - GCM-256 authenticated encryption of the seed file.
 *   - `setInvalidatedByBiometricEnrollment(false)` (unchanged trade-off:
 *     adding a fingerprint does not destroy existing wallets).
 *   - StrongBox where the device supports it.
 *
 * See `docs/security/SECURITY_NOTES.md` (2026-05-18 entry) for the full
 * audit + follow-up items.
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
