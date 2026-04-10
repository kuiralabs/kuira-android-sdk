# Wallet Authentication & Onboarding — Plan

**Status:** Planning
**Last Updated:** 2026-04-09
**Goal:** Build the best mobile wallet onboarding in crypto — no seed phrases, hardware-backed security, biometric-gated signing

---

## Problem Statement

Every crypto wallet today forces one of these on new users:

1. **Write down 24 words** — confusing, error-prone, terrible UX
2. **Custodial login** — "Sign in with Google" but someone else holds your keys
3. **Hardware wallet** — great security, but $80+ device and USB cables

Kuira can do better. Android phones already have:
- Dedicated secure hardware (StrongBox / Titan M2)
- Biometric authentication (fingerprint, face)
- Passkey infrastructure (Credential Manager, synced via Google Password Manager)
- TEE-backed key storage (Android Keystore in TrustZone)

**The insight:** A modern Android phone IS a hardware wallet. We just need to use it properly.

---

## Industry Research

Three emerging approaches were studied from Midnight CTO research:

### 1. Passkey-Native Signing (Sui / Tempo)
- Passkey (P-256) IS the signing key — no separate seed phrase
- **Requires:** Blockchain must verify P-256 (secp256r1) signatures
- **Midnight gap:** Midnight uses secp256k1. P-256 not supported in consensus.
- **UX:** Best possible — passkey is the account
- **Status:** Not feasible without Midnight protocol changes

### 2. zkLogin / Keyless (Sui / Aptos)
- "Sign in with Google" → ZK proof links social identity to ephemeral blockchain key
- Account address = hash(Google sub + app ID + salt)
- **Requires:** RSA verification + Groth16 verifier in consensus, salt/pepper service, prover service
- **Midnight gap:** None of this infrastructure exists
- **UX:** Lowest friction — just a Google sign-in
- **Status:** Not feasible without significant protocol + infrastructure work

### 3. Client-Side Hardware Protection (our approach)
- Standard secp256k1 keys, but hardware-protected with biometric gating
- Android Keystore (StrongBox/TEE) + BiometricPrompt for local auth — no passkey needed for daily use
- Passkey used optionally for backup encryption (PRF extension) — enables zero-friction cloud recovery
- **Requires:** No protocol changes. Pure client-side. Passkey PRF requires a relying party server.
- **UX:** Near-passkey UX with full self-custody
- **Status:** Core (local auth) buildable now. Cloud backup with PRF requires RP server setup.

**Decision:** Build approach #3 (Tier 1). Advocate for #1 at protocol level as future enhancement.

### 4. Solana Mobile Stack (Lessons Learned)

Solana built a full mobile wallet infrastructure: Seed Vault (TEE key custody), Mobile Wallet Adapter (dApp↔wallet protocol), dApp Store, and supporting Kotlin libraries. Studied for patterns to adopt and mistakes to avoid.

**Adopt from Solana:**
- Keys never leave the secure boundary — `sign(bytes) → signature` over IPC. Wallet is coordinator, never key holder.
- Auth token persistence — first connection requires approval, subsequent connections silently re-auth.
- Pre-derived public key cache — derive standard key roles at wallet creation, cache public keys to avoid re-auth per account.
- Spec-first protocol design with explicit versioning.

**Avoid from Solana:**
- **Hardware lock-in** — Seed Vault only works on Saga/Seeker devices (0.1% of Android). Our Tier 1 uses Android Keystore (StrongBox/TEE) available on every modern phone.
- **WebSocket lifecycle fragility** — power saving kills background WS, 30s timeout races with wallet picker. We have 4 transports including Bound Service (no WS needed for native apps).
- **No transaction validation** — Seed Vault signs any byte blob (TODO comment open 4 years). Our approval UI parses and displays tx details.
- **Over-engineered distribution** — dApp Store CLI started as NFT-minting complexity, had to be gutted. Our SDK is a single Gradle dependency.
- **Broken core APIs on real hardware** — `importSeed`/`createSeed` return invalid tokens on Saga (Issue #548). Test on real devices, not just simulators.
- **Interoperability gaps left open** — Ed25519-BIP32 missing since 2022 (Issue #4). Don't leave derivation path gaps.

**Stack comparison:**

| Solana Component | Kuira Equivalent | Status |
|---|---|---|
| Seed Vault SDK (TEE key custody) | `core:auth` (StrongBox/TEE via Android Keystore) | Planned |
| MWA walletlib (WS server, RPC, encryption) | `core:connector` (ConnectedAPIHandler, JSON-RPC, approval) | ✅ Complete |
| MWA clientlib (dApp-side signing) | `core:compact-engine` (MidnightContract.call()) | ✅ Complete |
| web3-core (tx types, signing) | `core:crypto` + `core:ledger` (Schnorr, tx building, Rust FFI) | ✅ Complete |
| rpc-core (JSON-RPC to nodes) | `core:indexer` (GraphQL + WebSocket to Midnight indexer) | ✅ Complete |
| Seed Vault Simulator | Emulator testing (x86_64 .so, no hardware needed) | ✅ Complete |
| dApp Store | N/A — standard Play Store | N/A |

---

## Architecture Overview

### The Core Idea

```
Traditional Wallet:
  User writes seed phrase → stores on paper → types it back to recover
  Keys live in app memory → software encryption at best

Kuira Wallet:
  User taps fingerprint → hardware generates + stores master key
  Seed phrase exists but user never sees it (unless they choose to)
  Every signing operation requires biometric → hardware-backed key decrypts seed → derive → sign → wipe
  Recovery via cloud backup (encrypted with PRF or backup password)
```

### Security Model

```
┌─────────────────────────────────────────────────┐
│ Android Device                                   │
│                                                  │
│  ┌──────────────────┐   ┌────────────────────┐  │
│  │ StrongBox / TEE  │   │ App Sandbox        │  │
│  │                  │   │                    │  │
│  │  AES-256 Master  │──▶│  Encrypted Seed    │  │
│  │  Key (non-       │   │  (AES-256-GCM)     │  │
│  │  extractable)    │   │                    │  │
│  │                  │   │  Signing keys      │  │
│  │  Biometric-bound │   │  derived from seed │  │
│  │  (per-use auth   │   │  on demand, wiped  │  │
│  │  with face/      │   │  after each use    │  │
│  │  finger/PIN)     │   │  (in app memory)   │  │
│  └──────────────────┘   └────────────────────┘  │
│                                                  │
│  ┌──────────────────┐                            │
│  │ Credential Mgr   │                            │
│  │                  │                            │
│  │  Passkey (P-256) │  ← syncs via Google        │
│  │  Used for:       │    Password Manager        │
│  │  - Cloud backup  │                            │
│  │    encryption    │  ⚠️ Requires relying       │
│  │    (PRF ext)     │    party server             │
│  └──────────────────┘                            │
└─────────────────────────────────────────────────┘
```

### Key Distinction: Biometric as Crypto Gate, Not UI Gate

Most wallets use biometrics as a UI check — if biometric passes, app unlocks. The key is in software regardless.

Kuira uses biometrics as a **cryptographic gate**:
- Master key is bound to `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)`
- Duration `0` = per-use auth (every crypto operation requires fresh authentication)
- The key literally cannot be used without authentication at the hardware level
- Even if the app is compromised, keys cannot be extracted without the user's biometric/PIN
- Verify with `KeyInfo.isUserAuthenticationRequirementEnforcedBySecureHardware()`

---

## Onboarding Flow (User Experience)

### New Wallet Creation

```
Screen 1: Welcome
  "Your phone is your hardware wallet."
  [Create Wallet]  [Restore Wallet]

Screen 2: Biometric Setup
  "Secure your wallet with biometrics"
  → Check BiometricManager.canAuthenticate(BIOMETRIC_STRONG | DEVICE_CREDENTIAL)
  → If no biometrics enrolled: prompt to enroll via Settings.ACTION_BIOMETRIC_ENROLL
  → On biometric/credential ready:
    1. Generate AES-256 master key in Android Keystore:
       - Try StrongBox first (catch StrongBoxUnavailableException → fall back to TEE)
       - setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)
       - setInvalidatedByBiometricEnrollment(false) — see Biometric Enrollment Tradeoff below
    2. Generate BIP-39 mnemonic (24 words → 256-bit entropy) in memory
    3. Derive secp256k1 keys from mnemonic (BIP-32)
    4. Cipher.init(ENCRYPT_MODE, masterKey) — Keystore generates random IV
       Wrap in CryptoObject(cipher)
    5. BiometricPrompt.authenticate(promptInfo, cryptoObject)
       On success: authenticated cipher encrypts mnemonic entropy (32 bytes)
       Store: [cipher.iv + ciphertext + GCM auth tag] to local storage
    6. Wipe plaintext mnemonic + derived keys from memory
  
Screen 3: Done
  "Wallet created. Your keys are hardware-protected."
  [Optional: Back up to cloud]
  [Optional: View recovery phrase]
  → User lands on home screen
```

**Total time: ~5 seconds.** No seed phrase writing. One biometric tap (or PIN/pattern if no biometrics enrolled).

### Transaction Signing

```
User taps "Send"
  → Enters amount, recipient
  → Taps "Confirm"
  → BEFORE prompt: Cipher.init(DECRYPT_MODE, keystoreKey, GCMParameterSpec(128, savedIv))
    This calls KeyMint.beginOperation() which returns a challenge
  → Wrap cipher in CryptoObject(cipher)
  → BiometricPrompt.authenticate(promptInfo, cryptoObject)
  → BiometricPrompt shows (biometric or PIN/pattern)
  → On success (onAuthenticationSucceeded callback):
    1. result.cryptoObject.cipher is now authenticated (HAT with matching challenge)
    2. authenticatedCipher.doFinal(encryptedData) → plaintext mnemonic entropy (32 bytes) enters app memory
    3. Derive signing key via BIP-32 (see note below on what to store)
    4. Sign transaction with derived key (Schnorr via Rust FFI)
    5. Wipe seed + derived key from memory (ByteArray.fill(0) — best effort, JVM may not guarantee; consider Rust FFI path for key derivation where Zeroizing<T> is reliable)
    6. Submit transaction
```

Every transaction requires authentication. Per-use auth (`duration = 0`) means no session caching — each crypto operation requires a fresh biometric/credential prompt.

**What to store locally (design decision):**
- **Option A: Store 32-byte mnemonic entropy.** Smaller. But every signing requires entropy → mnemonic reconstruction → PBKDF2 (2048 rounds, ~100-500ms) → BIP-39 seed → BIP-32 derive. Adds latency to every tx.
- **Option B: Store 64-byte BIP-39 seed.** Slightly larger. Signing skips PBKDF2 — goes directly to BIP-32 derivation. Faster per-transaction.
- **Option C: Store both.** Entropy for backup/export (user can view mnemonic words), seed for fast signing.
- **Recommendation:** Option C — encrypt both with the same Keystore key. Total encrypted payload ~100 bytes.

### Wallet Recovery

Three paths, from easiest to most manual:

**Path A: Cloud Restore (recommended)**
```
New device → Install Kuira → "Restore Wallet"
  → Block Store auto-restores backup blob (encrypted seed)
  → ⚠️ Old device's Keystore master key is GONE (device-bound, non-transferable)
  → Backup blob was encrypted with a TRANSFERABLE key, not the Keystore key:
      IF passkey available (synced via Google Password Manager):
        → Passkey PRF derives the backup decryption key
      ELSE:
        → Prompt for backup password (set during initial backup)
        → Argon2id(password, salt) derives the backup decryption key
  → Decrypt seed → create NEW Keystore master key on this device
  → Re-encrypt seed with new device's Keystore key
  → Done — wallet operational on new device
```

**⚠️ Critical architectural note: Two encryption layers required.**
- **Local storage:** Seed encrypted with device-bound Keystore master key (biometric-gated, non-exportable). Fast, hardware-protected.
- **Backup blob:** Seed encrypted with a transferable key (PRF-derived or password-derived). This is what goes into Block Store / cloud backup.

The Keystore master key CANNOT transfer across devices — it's hardware-bound. So backups must use a separate encryption key that the user can reproduce on a new device (passkey PRF or password).

**Path B: Seed Phrase Import**
```
New device → Install Kuira → "Restore with phrase"
  → Enter 24 words
  → Biometric setup on new device
  → Re-encrypts with new device's StrongBox key
```

**Path C: Device Transfer (NFC/QR)** — future enhancement, not MVP
```
Old device → Settings → "Transfer Wallet"
  → Biometric on old device (decrypt seed)
  → New device shows QR with ephemeral ECDH public key
  → Old device scans QR → derives shared session key
  → Old device encrypts seed with session key → shows as QR / sends via NFC
  → New device decrypts → biometric setup → re-encrypts with new Keystore key
```
Note: Payload is ~80 bytes (32-byte mnemonic entropy + AES-GCM IV/tag overhead), well within QR/NFC limits.
The ECDH key exchange via QR avoids needing Bluetooth or network connectivity.

---

## Android APIs & Components

### Minimum API Level Compatibility

**Current project minSdk: 24 (Android 7.0)**

Key auth APIs require higher API levels:
- `setUserAuthenticationParameters()` → **API 30** (Android 11)
- `setIsStrongBoxBacked()` → **API 28** (Android 9)
- `BiometricPrompt` (AndroidX) → **API 28** (though AndroidX backports some behavior)
- `Settings.ACTION_BIOMETRIC_ENROLL` → **API 30**
- Block Store E2E encryption → **API 28+** (requires screen lock)
- Credential Manager (passkey creation) → **API 28+** (via Play Services Jetpack lib)

**Decision needed:** Either raise `core:auth` minSdk to 30, or provide degraded-but-functional behavior on API 24-29 (e.g., use deprecated `setUserAuthenticationValidityDurationSeconds()` on older APIs, skip StrongBox).

### Core Security APIs

| API | Purpose | Min SDK |
|-----|---------|---------|
| `AndroidKeyStore` provider | Hardware-backed key storage | API 23 (6.0) |
| `KeyGenParameterSpec.Builder` | Configure key properties (biometric binding, StrongBox) | API 23+ |
| `setIsStrongBoxBacked(true)` | Use dedicated secure element (Titan M2 etc) | API 28 (9.0) |
| `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` | Per-use biometric-only key access (Class 3 biometric required) | API 30+ |
| `setUserAuthenticationRequired(true)` | Require user auth (biometric OR device credential — PIN/pattern/password) | API 23+ |
| `BiometricPrompt` | System biometric authentication | API 28+ (AndroidX backports) |
| `BiometricPrompt.CryptoObject` | Bind biometric auth to crypto operation (Signature, Cipher, or Mac). ✅ Usable with `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` on API 30+ (AndroidX biometric 1.1.0 stable, Jan 2021). | API 28+ |
| `CredentialManager` | Passkey creation + authentication | API 28+ via `credentials-play-services-auth` Jetpack lib |
| `CredentialProviderService` | Register app as credential provider | API 34 (14) |
| `Cipher` (AES/GCM/NoPadding) | Encrypt/decrypt seed with master key | API 23+ |

**Important distinctions (verified):**
- `setUserAuthenticationRequired(true)` alone allows PIN/pattern/password fallback — NOT biometric-only
- For biometric-only: must use `setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)`
- Duration `0` = per-use authentication (every operation requires fresh biometric)
- `AUTH_BIOMETRIC_STRONG` = Class 3 biometric only (highest security). `AUTH_DEVICE_CREDENTIAL` = PIN/pattern/password
- Keys are invalidated by default when new biometrics are enrolled (`setInvalidatedByBiometricEnrollment(true)` is default)
- `KeyInfo.isUserAuthenticationRequirementEnforcedBySecureHardware()` — query whether auth is hardware-enforced

### Authentication Strategy Decision (Critical — Verified Against AOSP)

**On API 30+ (Android 11+), CryptoObject works WITH `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`.**

The documentation saying "you can't pass CryptoObject" with device credential fallback is **outdated/misleading** — it applies only to pre-API-30. Since `setUserAuthenticationParameters()` itself requires API 30, we're always on API 30+ when using per-use auth, so CryptoObject is available.

**How it works at the TEE level (HardwareAuthToken):**

| Mode | HAT `challenge` field | Enforcement |
|------|----------------------|-------------|
| WITH CryptoObject | Set to operation handle from `beginOperation()` | TEE-level: key unlocks for exactly ONE crypto operation, then re-locks |
| WITHOUT CryptoObject | Empty (0) | Time-based: `timestamp + AUTH_TIMEOUT > now`. For duration=0, window is ~0 seconds. |

CryptoObject binding is cryptographically stronger: the HAT proves "the user authenticated specifically for THIS cipher operation." Without CryptoObject, the HAT only proves "the user authenticated recently" — a race condition is theoretically possible.

| Mode | CryptoObject | PIN Fallback | Per-use enforcement |
|------|-------------|-------------|---------------------|
| `duration=0, BIOMETRIC_STRONG` | ✅ Yes | ❌ No | ✅ TEE-level (HAT challenge) |
| `duration=0, BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` (API 30+) | ✅ **Yes** | ✅ Yes | ✅ TEE-level (HAT challenge) |
| Without CryptoObject, `duration=0` | N/A | ✅ Yes | ⚠️ Time-based (~0s window) |

**Recommended approach:** `duration=0` + `AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL` + **CryptoObject** (API 30+).

This gives us the strongest possible security (TEE-level per-operation binding) WITH PIN fallback. Best of both worlds.

**The code pattern:**
```kotlin
// 1. Init cipher with Keystore key — this calls beginOperation() in KeyMint
//    For DECRYPT: must provide GCMParameterSpec with IV saved during encryption
//    For ENCRYPT: Keystore generates random IV automatically (retrieve via cipher.iv after init)
val cipher = Cipher.getInstance("AES/GCM/NoPadding")
val gcmSpec = GCMParameterSpec(128, savedIv)  // 128-bit auth tag, IV from encryption
cipher.init(Cipher.DECRYPT_MODE, getKeystoreKey(), gcmSpec)

// 2. Wrap in CryptoObject and authenticate
val cryptoObject = BiometricPrompt.CryptoObject(cipher)
biometricPrompt.authenticate(promptInfo, cryptoObject)

// 3. On success, use the authenticated cipher
override fun onAuthenticationSucceeded(result: AuthenticationResult) {
    val authenticatedCipher = result.cryptoObject!!.cipher!!
    val seed = authenticatedCipher.doFinal(encryptedSeed)
    // ... derive key, sign, wipe
}
```

**Storage format for encrypted seed:** `[12-byte IV] + [ciphertext] + [16-byte GCM auth tag]`
The IV must be saved alongside the ciphertext — it's needed for decryption. GCM IVs should be unique per encryption but are not secret.

**Edge case: `KeyPermanentlyInvalidatedException`** — thrown during `cipher.init()` if the key has been permanently invalidated. With `setInvalidatedByBiometricEnrollment(false)`, new biometrics won't trigger this. Exact remaining triggers are not fully documented but likely include: screen lock removal, device factory reset, or security downgrade. Recovery: decrypt seed from backup (PRF or password), then recreate Keystore key and re-encrypt seed on the device.

**Sources:** AOSP [HardwareAuthToken.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/android16-release/security/keymint/aidl/android/hardware/security/keymint/HardwareAuthToken.aidl), [Android Developers Blog: Using BiometricPrompt with CryptoObject](https://medium.com/androiddevelopers/using-biometricprompt-with-cryptoobject-how-and-why-aace500ccdb7), AndroidX Biometric [1.1.0-alpha02 release notes](https://developer.android.com/jetpack/androidx/releases/biometric).

### StrongBox Fallback Strategy

Not all devices have StrongBox. **Fallback is NOT automatic** — must be handled explicitly:

```kotlin
fun generateMasterKey(alias: String): SecretKey {
    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    
    fun buildSpec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
        alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    ).apply {
        setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        setKeySize(256)
        setUserAuthenticationParameters(0, 
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        setInvalidatedByBiometricEnrollment(false)
        if (strongBox) setIsStrongBoxBacked(true)
    }.build()
    
    return try {
        keyGenerator.init(buildSpec(strongBox = true))
        keyGenerator.generateKey()
    } catch (e: StrongBoxUnavailableException) {
        keyGenerator.init(buildSpec(strongBox = false))  // Fallback to TEE
        keyGenerator.generateKey()
    }
}
```
```

Fallback chain:
1. **StrongBox** (Google Titan M2, Samsung eSE, Qualcomm SPU) — dedicated secure element, tamper-resistant
2. **TEE** (TrustZone) — hardware-backed but shares processor with OS
3. **Software Keystore** — no hardware backing, last resort

```
// Detection
val hasStrongBox = packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
```

### Biometric Enrollment Tradeoff (Verified)

`setInvalidatedByBiometricEnrollment` controls what happens when the user adds a new fingerprint or face:

| Setting | What Happens | Risk |
|---------|-------------|------|
| `true` (default) | Key permanently invalidated. `KeyPermanentlyInvalidatedException` thrown. Encrypted seed becomes inaccessible. | User adds fingerprint → **locked out of wallet**. Must restore from backup. |
| `false` | Key remains valid after biometric change. | Slightly reduced security — new biometrics can unlock old keys. |

**Decision: Use `false`.** A wallet that locks users out when they add a fingerprint is unacceptable. The security reduction is minimal — the attacker would need physical access to the device AND the ability to enroll a new biometric (which requires the existing screen lock). Banking apps use `false` for the same reason.

**Mitigation:** If biometric enrollment changes are detected, prompt user to re-verify identity (e.g., enter backup password) on next wallet access — but don't invalidate the key.

### Passkey Server Requirement (Verified)

**⚠️ Passkeys require a relying party server.** You cannot create a passkey purely locally. The server must:
1. Generate cryptographic challenges
2. Provide credential creation options (rp.id, rp.name, user info)
3. Verify and store the public key after registration
4. Digital Asset Links must link the Android app to the server domain

**Implication for Kuira:** If we want passkeys (for PRF-based backup encryption or cross-device recovery), we need to run or use a relying party server. Options:
- **Host a minimal WebAuthn RP** at e.g. `auth.kuira.app` — just challenge generation + public key storage
- **Use a managed service** (e.g. Google Identity Platform, Auth0) as the RP
- **Skip passkeys entirely** for MVP — use Android Keystore biometric for local auth, and backup password for cloud backup encryption

**Local authentication does NOT need passkeys.** Android Keystore + BiometricPrompt handles re-auth entirely on-device. Passkeys are only needed for the cloud backup PRF flow and cross-device credential sync.

### Passkey PRF Extension

The WebAuthn PRF (Pseudo-Random Function) extension allows deriving deterministic encryption keys from a passkey. The authenticator evaluates a PRF using a device-stored secret + relying party input, producing a deterministic 32-byte value.

**Platform support (verified April 2026):**

| Platform | Provider | PRF Support |
|----------|----------|-------------|
| Android (Chrome ≥ 130) | Google Password Manager | ✅ Yes |
| Android (Chrome ≥ 130) | Third-party providers (1Password, etc.) | ❌ No (API limitation) |
| Android native app | Google Password Manager via CredentialManager | ✅ Yes (via requestJson passthrough) |
| Android native app | Third-party providers | ❌ No |
| macOS 15+ | iCloud Keychain | ✅ Yes (Safari 18+, Chrome 132+) |
| iOS 18.4+ | iCloud Keychain | ✅ Yes |
| Windows 11 25H2 | Windows Hello | ✅ Yes |

**⚠️ Critical limitation for backup flow:**
PRF on Android ONLY works when Google Password Manager is the active provider. If the user has switched to a third-party provider (Android 14+ allows this), PRF will silently fail. There is no API to check PRF availability in advance.

**Implication for Kuira:** The cloud backup encryption flow that relies on passkey PRF needs a robust fallback — e.g., a user-chosen backup password when PRF is unavailable. PRF should be the preferred path (zero friction) but cannot be the only path.

**PRF status:** WebAuthn PRF extension is still a W3C Editor's Draft (not yet a Recommendation). It maps to the CTAP2 `hmac-secret` extension at the authenticator level.

---

## Components to Build

### 1. `core:auth` Module (new)

Wallet authentication and key protection layer.

**Responsibilities:**
- Master key lifecycle (generate, biometric-gate, destroy)
- Seed encryption/decryption
- Biometric prompt orchestration
- StrongBox/TEE detection and fallback
- Key attestation

**Key classes:**
- `WalletKeyManager` — master key generation + biometric binding via Android Keystore
- `SeedVault` — two-layer seed storage: (1) local: encrypt with Keystore master key (device-bound), (2) backup: encrypt with PRF or password-derived key (transferable)
- `BiometricGate` — wraps BiometricPrompt + CryptoObject for per-use auth with PIN fallback (API 30+). Cipher.init() before authenticate(), doFinal() in onAuthenticationSucceeded(). Callback runs on main thread executor. **⚠️ Implementation note:** BiometricPrompt requires `FragmentActivity`, but our activities extend `ComponentActivity` (which does NOT extend FragmentActivity). Must either: (a) change `MainActivity` to extend `FragmentActivity`, or (b) add `androidx.fragment` dependency and use `FragmentActivity` for auth screens specifically.
- `SecurityCapabilities` — detect StrongBox, TEE, biometric types available

### 2. `core:backup` Module (new)

Cloud backup and recovery.

**Responsibilities:**
- Encrypted seed backup via Block Store (preferred) or cloud backup
- Dual key derivation: passkey PRF (preferred) OR user backup password (fallback)
- Backup metadata (creation date, device info, version, key derivation method used)
- Restore flow orchestration

**Key classes:**
- `BackupManager` — create/restore encrypted backups
- `BackupKeyDeriver` — derives AES-256 backup encryption key via PRF or password
  - PRF path: zero friction, but only works with Google Password Manager
  - Password path: universal fallback, uses Argon2id KDF
  - Backup stores which derivation method was used. Each method produces its own AES-256 key. At backup creation time, the seed is encrypted with BOTH keys (dual-encrypted blob or two separate encrypted copies), so either path can decrypt on restore.
- `BackupStorageProvider` — abstraction over backup storage options:
  - **Block Store** (preferred): Google Play Services API, 4KB per entry (plenty for encrypted seed), max 16 entries, no user consent needed, auto-restores on new device setup. E2E encryption is opt-in via `setShouldBackupToCloud(true)` and requires screen lock (PIN/pattern/password). Cloud restore: Pixel Android 9+, other devices Android 12+. Requires Google Play Services.
  - **Auto Backup** (fallback): Android 6+, automatic Google Drive backup, 25MB limit, but less control over encryption timing.
  - **Google Drive REST API** (explicit): Full control but requires Google Sign-In consent.

### 3. `feature:onboarding` Module (new)

First-launch onboarding experience.

**Responsibilities:**
- Welcome screen
- Biometric enrollment flow
- Wallet creation orchestration
- Recovery flow (cloud, seed phrase, device transfer)
- Optional seed phrase reveal

### 4. Updates to `core:crypto`

- `SigningSession` — biometric-gated key derivation + signing + wipe (replaces direct key access)
- Integration with `WalletKeyManager` for seed storage

---

## Security Properties

### What we achieve

| Property | How |
|----------|-----|
| **Master key never in app process** | Master key lives in StrongBox/TEE. Crypto operations happen in system process, key material never enters app memory. Private signing keys are derived in-app and wiped after use. |
| **Auth-gated signing** | Every tx requires biometric or PIN/pattern (per-use, `duration=0`). TEE-level per-operation enforcement via CryptoObject + HardwareAuthToken challenge binding (API 30+). |
| **Seed phrase encrypted at rest** | AES-256-GCM with hardware-backed key |
| **Memory safety** | Seed + derived keys wiped after each operation via `ByteArray.fill(0)`. ⚠️ Two caveats: (1) Seed is briefly in app memory during signing (unavoidable — BIP-32 derivation runs in-app, not in TEE). (2) JVM may optimize away `fill(0)` or GC may copy arrays before wiping. No Java equivalent to C's `memset_s`. Best mitigation: minimize time seed is in memory + use Rust FFI for key derivation (Rust has `Zeroizing<T>`). |
| **Cloud backup security** | Backup encrypted with PRF-derived key or Argon2id(password) — NOT with device-bound Keystore key. Two separate encryption layers (local + backup). |
| **Device loss recovery** | Block Store restores backup blob → decrypt with PRF (passkey syncs via Google Password Manager) or backup password → re-encrypt with new device's Keystore key |
| **Biometric-first, PIN fallback** | `AUTH_BIOMETRIC_STRONG \| AUTH_DEVICE_CREDENTIAL` — biometric preferred, device credential as fallback |

### Threat model

| Threat | Mitigation |
|--------|------------|
| App compromise (malware) | Master key is non-extractable from StrongBox/TEE. Key material never enters app process. However: on a rooted/compromised OS, an attacker could potentially *use* keys on-device (though not extract them). StrongBox is more resistant than TEE here due to separate CPU. |
| Device theft (locked) | Biometric/PIN required. Android's built-in biometric lockout applies (device-specific — typically 30s lockout after 5 failures, permanent lockout requiring device credential after 15-20 failures). No Keystore-level failure counter. |
| Device theft (unlocked) | Each crypto operation requires fresh biometric. No session caching. |
| Cloud backup leak | Encrypted with PRF-derived key or Argon2id(password). Useless without the passkey or backup password. |
| Passkey phishing | Passkeys are origin-bound by WebAuthn spec. Cannot be phished. |
| Total loss (device + cloud) | Seed phrase backup (if user exported) is the last resort. Same as any self-custodial wallet. |

---

## Access Key Credential Architecture (CTO Direction)

The Midnight CTO is exploring a standards-based access key model inspired by Tempo but built on W3C credentials. This extends our Tier 1 architecture with scoped delegation.

### The Model: Root Key → Access Keys as Verifiable Credentials

```
Root Key (Secure Enclave, biometric-gated)
  │
  │ issues (signed by root key)
  │
  ├── Access Key VC for DApp A
  │     - W3C Verifiable Credential format
  │     - Scoped: only this contract, 100 tNIGHT limit
  │     - Expires: 7 days
  │     - Stored in: Kuira app / credential holder apps (NOT Google Wallet — custom VCs not supported)
  │
  ├── Access Key VC for Employee B
  │     - Scoped: treasury operations only
  │     - Expires: 30 days
  │     - Distributed via: Digital Credentials API
  │
  └── Access Key VC for Device C (tablet)
        - Full access (user's own second device)
        - Expires: 90 days
        - Transferred via: NFC / QR
```

### Why W3C Verifiable Credentials?

Instead of Tempo's proprietary access key format, the CTO envisions using standards:

| Standard | Role |
|----------|------|
| **W3C Verifiable Credentials Data Model 2.0** | Format for access key credentials — extensible, portable, interoperable |
| **OpenID4VCI** (OpenID for Verifiable Credential Issuance) | Protocol for issuing credentials — but used locally via Digital Credentials API, NOT over HTTPS |
| **Digital Credentials API** | Browser/platform API for credential presentation (holder → verifier). ⚠️ Does NOT support issuance — see corrections below. |

### Credential Structure (from CTO analysis)

```json
{
  "@context": [
    "https://www.w3.org/ns/credentials/v2",
    "https://midnight.network/key-credential/v1"
  ],
  "type": ["VerifiableCredential", "DerivedKeyCredential"],
  "issuer": "did:key:<root-public-key>",
  "validFrom": "2026-04-09T00:00:00Z",
  "validUntil": "2026-04-16T00:00:00Z",
  "credentialSubject": {
    "id": "did:key:<child-public-key>",
    "publicKey": "<child-jwk>",
    "derivationPath": "m/44'/2400'/0'/0/1",
    "parentKey": "did:key:<root-public-key>"
  },
  "proof": { "..." : "signed by root key" }
}
```

The W3C security vocabulary already has `capabilityDelegation`, `capabilityChain`, and `parentCapability` terms — a natural fit for root-to-child key hierarchies.

### How This Works on Android

```
DApp in Chrome                    Android Platform                  Kuira Wallet
     │                                  │                                │
     │ navigator.credentials.create({    │                                │
     │   digital: {                     │                                │
     │     protocol: "openid4vci-v1",   │                                │
     │     data: { scope, expiry }      │                                │
     │   }                              │                                │
     │ })                               │                                │
     │ ─────────────────────────────▶   │                                │
     │                                  │  Route to credential provider  │
     │                                  │ ──────────────────────────────▶│
     │                                  │                                │
     │                                  │              BiometricPrompt   │
     │                                  │              (approve issuance)│
     │                                  │                                │
     │                                  │              Root key signs    │
     │                                  │              child key VC      │
     │                                  │                                │
     │                                  │  ◀──────────────────────────── │
     │                                  │  Return DigitalCredential      │
     │ ◀─────────────────────────────   │                                │
     │                                  │                                │
     │ credential.data = signed VC      │                                │
```

Key insight from CTO: **No HTTPS involved.** The Digital Credentials API passes OpenID4VCI message bodies directly between browser and wallet app via Android's platform layer. The secure enclave acts as both the key holder AND the credential issuer.

### Open Questions (from CTO's thread)

1. **On-chain registration of access keys:** Tempo registers access keys on-chain via the Account Keychain precompile. Does Midnight need an equivalent? Or can access keys be verified purely by checking the VC signature against the root key?

2. **OpenID4VCI flow collapse:** Standard OpenID4VCI is multi-step (authorization → token → credential). Over Digital Credentials API, we get a single request-response. Need to use pre-authorized code flow to collapse into one exchange.

3. **Issuance direction:** Standard OpenID4VCI has external issuers pushing to wallets. Here the wallet IS the issuer (self-issuance from secure enclave). Unconventional but mechanically possible.

4. **Key attestation:** OpenID4VCI's key attestation mechanism (Appendix D) can prove the child key lives in specific hardware — useful for enterprise scenarios.

### How This Fits Our Tier 1

Access keys are an **extension** of Tier 1, not a replacement:

```
Tier 1 (core):
  StrongBox → master key → encrypted seed → biometric signing
  This is the root key. Always present.

Access Key Layer (extension):
  Root key issues scoped child keys as W3C VCs
  Child keys can sign subset of operations
  Distributed to dApps, devices, employees
  Revocable, expirable, scopeable
```

Tier 1 works without access keys. Access keys add delegation capability on top.

### Kuira as Android Digital Credential Holder

**⚠️ Correction:** Digital Credentials use the **Credential Manager Holder API** (`RegistryManager` + `OpenId4VpRegistry`), NOT `CredentialProviderService` (which is for passkeys/passwords only). These are separate Android APIs.

**⚠️ Second correction:** Android's DC API only supports credential **presentation** (Holder → Verifier), NOT credential **issuance** (Issuer → Holder). The CTO's architecture proposes wallet-as-issuer (Kuira issues access key VCs to dApps), which is the **inverse** of what the DC API supports.

**What actually works with the DC API:**
- A dApp (Verifier) requests "show me your access key credential"
- Kuira (Holder) presents an existing credential
- This is **presentation**, not issuance

**What the CTO wants (issuance) would require:**
- Kuira generates and signs a new access key VC on demand
- The "presentation" flow could be repurposed: dApp requests a credential, Kuira mints one on-the-fly and presents it. Semantically it's issuance, mechanically it's presentation.
- Alternatively: use the DApp Connector (our Phase 5 WebSocket/IPC) for issuance, and DC API for subsequent presentation to other verifiers

**Presentation flow (what DC API supports):**

1. Register existing access key credentials with `RegistryManager`
2. Declare Activity with intent filter `androidx.credentials.registry.provider.action.GET_CREDENTIAL`
3. When dApp/browser requests credentials, Credential Manager matches and launches Kuira's Activity
4. Kuira shows biometric prompt + approval UI
5. Returns `DigitalCredential(responseJson)` via `PendingIntentHandler`

**API level:** Credential Manager Holder API supports Android 6+ (API 23).
**Supported formats:** SD-JWT and mdoc (ISO 18013-5). W3C VCDM may require custom handling — potentially wrap in SD-JWT container.

---

## Future Tiers (Protocol-Dependent)

These require Midnight protocol changes but should be advocated for:

### Tier 2: Passkey-Native Signing
- **Requires:** P-256 (secp256r1) signature verification in Midnight consensus
- **Effect:** Passkey IS the signing key. No seed phrase at all. No encryption/decryption layer.
- **Advocacy:** Share Sui/Tempo research with Midnight protocol team. P-256 is becoming an industry standard.

### Tier 3: Social Login (zkLogin)
- **Requires:** RSA verification + Groth16 verifier in consensus, salt service, prover service
- **Effect:** "Sign in with Google" → wallet exists. Zero crypto concepts.
- **Advocacy:** Long-term vision. Aptos and Sui have proven the model.

### Migration Path
Tier 1 → Tier 2 migration: If Midnight adds P-256 support, existing users can add their passkey as a native signer alongside their secp256k1 key. No forced migration.

---

## Comparison to Other Wallets

| Feature | MetaMask Mobile | Phantom | Lace (browser ext) | **Kuira (Tier 1)** |
|---------|----------------|---------|---------------------|---------------------|
| Seed phrase in onboarding | Yes (required) | Yes (required) | Yes (required) | **No** (hidden) |
| Hardware-backed keys | Partial (Keystore for vault password, not signing key) | Unknown (closed-source) | No (browser) | **Yes** (StrongBox/TEE for master key) |
| Biometric signing | UI gate only (retrieves vault password) | UI gate only (likely) | No | **Crypto-gated** (hardware, per-use) |
| Cloud backup | Manual (Android). ⚠️ iOS: iCloud auto-includes vault by default | Manual only | No | **Automatic** (Block Store, E2E encrypted) |
| Passkey auth | No | No | No | **Planned** (requires RP server) |
| Recovery without seed phrase | No | Social login option (separate from seed wallets) | No | **Yes** (cloud backup + password) |
| Time to first wallet | ~3-5 min (with seed phrase step) | ~1-2 min | ~2 min | **~5 seconds** |

**Notes on competitor claims (verified April 2026):**
- MetaMask uses `react-native-keychain` → Android Keystore for vault password only. Private key enters JS heap during signing (confirmed by CertiK audit). Biometric was historically bypassable (GitHub Issue #901).
- Phantom is closed-source. No evidence of hardware-gated crypto signing. Social login uses distributed key management (not cloud backup of seed).
- Lace is browser-only. Mobile V2 on roadmap but not confirmed shipped with biometric features.

---

## Credential Ecosystem Context (from CTO Research)

The credential landscape is converging around three format standards and two protocol standards. This matters because the access key architecture needs to pick the right format and protocol to maximize interoperability.

### Three Credential Formats

| Format | Backing | DID Support | Selective Disclosure | Best For |
|--------|---------|-------------|---------------------|----------|
| **mDocs** (ISO 18013-5/23220) | Governments, Apple | No native support | Yes | Government IDs (driver's license, passport) |
| **SD-JWT VC** (RFC 9901) | Enterprise, OAuth ecosystem | Fields only (no semantics) | Yes (built-in) | Systems already using JWT/OAuth |
| **W3C VCDM 2.0** | W3C, broadest flexibility | Full DID support | Via SD-JWT or ZK | Self-sovereign identity, blockchain use cases |

**For Kuira: W3C VCDM 2.0** is the clear choice — it supports DIDs natively, allows custom credential types (like our `DerivedKeyCredential`), and has the most flexible extensibility model via JSON-LD contexts.

### Two Protocol Standards

| Protocol | Purpose | Transport |
|----------|---------|-----------|
| **OpenID4VCI** | Credential *issuance* (issuer → holder) | HTTPS or Digital Credentials API |
| **OpenID4VP** | Credential *presentation* (holder → verifier) | HTTPS or Digital Credentials API |

**OpenID4VP** (presentation) can be sent over the Digital Credentials API instead of HTTPS. **OpenID4VCI** (issuance) is NOT natively supported by the Android DC API — the DC API only handles presentation flows. The CTO's local issuance proposal would need a workaround (see Access Key section above).

### Platform Support

| Platform | Credential Storage | Digital Credentials API | Notes |
|----------|-------------------|------------------------|-------|
| **Android** | Google Wallet, CredentialManager, 3rd party apps | Yes (via `navigator.credentials`) | Full support — Kuira registers via Credential Manager Holder API (`RegistryManager`). Natively supports SD-JWT + mdoc formats. |
| **Apple** | Apple Wallet (closed partners only), 3rd party apps via Identity Document Services | Yes (via Digital Credentials API) | Cannot mint custom IDs into Apple Wallet — need 3rd party app |
| **Web** | Browser Credential Management API | Yes | Routes to registered credential providers |

**Android advantage:** Google's ecosystem is more open here. Kuira can register as a Digital Credential Holder via `RegistryManager` and handle credential requests from any app/browser. Apple's ecosystem requires 3rd party wallet apps for non-government credentials.

### EU Digital Identity (EUDI)

EUDI mandates every EU member state have a Digital Identity Wallet by end of 2026. Three credential tiers:
- **PuB-EAA** — government-issued (driver's license, health insurance)
- **QEAA** — government-approved entities (universities, licensed professions)
- **Non-qualified EAA** — private companies (no registration required)

**Relevance for Kuira:** A non-qualified EAA wallet that supports EUDI could be interesting for enterprise blockchain identity. The technology (selective disclosure, etc.) is the same across tiers. EUDI supports Digital Credentials API, mandates mDocs + SD-JWT VC, and optionally allows VCDM.

### Why NOT DIDComm?

The CTO analyzed DIDComm (P2P protocol for DID communication) and concluded it's not the right fit because:
- Digital Credentials API needs requests visible to multiple stack layers (browser, OS, apps)
- DIDComm requires both parties to have DIDs (requesting apps often don't)
- DIDComm is for long-running sessions; credential exchange is single request-response
- DIDComm encrypts requests, but DC API needs them inspectable for routing

### ZK Credentials (Future Opportunity)

For privacy-preserving credential verification:
- **Google Longfellow ZK** — selective disclosure + unlinkability for mDLs
- **SD-JWT** — selective disclosure (widely supported)
- **BBS+ signatures** — unlinkable selective disclosure (AnonCred v2 exploring this)

Midnight's ZK capabilities could enable the most privacy-preserving credential verification of any wallet — prove you have a credential without revealing which credential or who issued it.

---

## References

- [Aptos Keyless](https://aptos.dev/build/guides/aptos-keyless) — zkLogin variant with OIDC + ZK proofs
- [Sui Passkeys](https://docs.sui.io/concepts/cryptography/passkeys) — P-256 WebAuthn on-chain
- [Tempo Passkeys](https://docs.tempo.xyz) — Root key + access key model, cross-app iframe
- [Sui zkLogin](https://docs.sui.io/concepts/cryptography/zklogin) — Social login → ephemeral key → ZK proof
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore) — Hardware-backed key storage
- [Android BiometricPrompt](https://developer.android.com/identity/sign-in/biometric-auth) — System biometric API
- [Android Credential Manager](https://developer.android.com/identity/sign-in/credential-manager) — Passkey API
- [WebAuthn PRF Extension](https://w3c.github.io/webauthn/#prf-extension) — Deterministic key derivation from passkeys
- [SIP-9](https://github.com/sui-foundation/sips/blob/main/sips/sip-9.md) — Sui passkey signature scheme specification (flag 0x06)
- [OpenID4VCI](https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html) — Credential issuance protocol (used locally via Digital Credentials API)
- [Digital Credentials API](https://www.w3.org/TR/digital-credentials/) — W3C spec for browser/platform credential exchange
- [W3C VC Data Model 2.0](https://www.w3.org/TR/vc-data-model-2.0/) — Verifiable Credential format for access keys
- LFDT-Nightstream/MVE-Planning — Midnight CTO research on Sui passkeys, Tempo passkeys, zkLogin, credential wallets
