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
- Passkey used for authentication (not signing) — unlocks hardware-encrypted keys
- **Requires:** No protocol changes. Pure client-side.
- **UX:** Near-passkey UX with full self-custody
- **Status:** Buildable now with Android APIs

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
  Every signing operation requires biometric → hardware decrypts key → sign → wipe
  Recovery via passkey-synced encrypted cloud backup
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
│  │                  │   │  Encrypted         │  │
│  │  Biometric-bound │   │  Private Keys      │  │
│  │  (unlock only    │   │  (derived on       │  │
│  │  with face/      │   │   demand, wiped    │  │
│  │  fingerprint)    │   │   after use)       │  │
│  └──────────────────┘   └────────────────────┘  │
│                                                  │
│  ┌──────────────────┐                            │
│  │ Credential Mgr   │                            │
│  │                  │                            │
│  │  Passkey (P-256) │  ← syncs via Google        │
│  │  Used for:       │    Password Manager        │
│  │  - Re-auth       │                            │
│  │  - Cloud backup  │                            │
│  │    encryption    │                            │
│  └──────────────────┘                            │
└─────────────────────────────────────────────────┘
```

### Key Distinction: Biometric as Crypto Gate, Not UI Gate

Most wallets use biometrics as a UI check — if biometric passes, app unlocks. The key is in software regardless.

Kuira uses biometrics as a **cryptographic gate**:
- Master key is bound to `setUserAuthenticationRequired(true)` in Android Keystore
- The key literally cannot be used without biometric authentication at the hardware level
- Even if the app is compromised, keys cannot be extracted without the user's biometric

---

## Onboarding Flow (User Experience)

### New Wallet Creation

```
Screen 1: Welcome
  "Your phone is your hardware wallet."
  [Create Wallet]  [Restore Wallet]

Screen 2: Biometric Setup
  "Secure your wallet with biometrics"
  → System BiometricPrompt (fingerprint or face)
  → On success:
    1. StrongBox generates AES-256 master key (biometric-bound)
    2. App generates BIP-39 seed phrase (in memory)
    3. Derives secp256k1 keys from seed
    4. Encrypts seed with master key → stores ciphertext
    5. Wipes plaintext seed from memory
  
Screen 3: Done
  "Wallet created. Your keys are hardware-protected."
  [Optional: Back up to cloud]
  [Optional: View recovery phrase]
  → User lands on home screen
```

**Total time: ~5 seconds.** No seed phrase writing. No PIN creation. One biometric tap.

### Transaction Signing

```
User taps "Send"
  → Enters amount, recipient
  → Taps "Confirm"
  → BiometricPrompt appears
  → On success:
    1. Master key decrypts seed (in TEE)
    2. Derive signing key for this transaction
    3. Sign transaction
    4. Wipe derived key from memory
    5. Submit transaction
```

Every transaction requires biometric. No "remember for 5 minutes" shortcuts.

### Wallet Recovery

Three paths, from easiest to most manual:

**Path A: Cloud Restore (recommended)**
```
New device → Install Kuira → "Restore Wallet"
  → Credential Manager offers synced passkey
  → Biometric authenticates passkey
  → Passkey PRF derives decryption key
  → Fetch encrypted seed from Google Drive / backup
  → Decrypt → derive keys → done
```

**Path B: Seed Phrase Import**
```
New device → Install Kuira → "Restore with phrase"
  → Enter 24 words
  → Biometric setup on new device
  → Re-encrypts with new device's StrongBox key
```

**Path C: Device Transfer (NFC/QR)**
```
Old device → Settings → "Transfer Wallet"
  → Biometric on old device
  → Generates encrypted transfer payload
  → NFC tap or QR scan to new device
  → Biometric on new device
  → Done
```

---

## Android APIs & Components

### Core Security APIs

| API | Purpose | Min SDK |
|-----|---------|---------|
| `AndroidKeyStore` provider | Hardware-backed key storage | API 23 (6.0) |
| `KeyGenParameterSpec.Builder` | Configure key properties (biometric binding, StrongBox) | API 23+ |
| `setIsStrongBoxBacked(true)` | Use dedicated secure element (Titan M2 etc) | API 28 (9.0) |
| `setUserAuthenticationRequired(true)` | Biometric-gated key access | API 23+ |
| `BiometricPrompt` | System biometric authentication | API 28+ |
| `BiometricPrompt.CryptoObject` | Bind biometric auth to crypto operation | API 28+ |
| `CredentialManager` | Passkey creation + authentication | API 34 (14) / Jetpack backport |
| `Cipher` (AES/GCM/NoPadding) | Encrypt/decrypt seed with master key | API 23+ |

### StrongBox Fallback Strategy

Not all devices have StrongBox. Fallback chain:
1. **StrongBox** (Titan M2, Samsung Knox) — dedicated secure element
2. **TEE** (TrustZone) — hardware-backed but shared with OS
3. **Software Keystore** — last resort, still better than plaintext

```
// Detection
val hasStrongBox = packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
```

### Passkey PRF Extension

The WebAuthn PRF (Pseudo-Random Function) extension allows deriving deterministic encryption keys from a passkey:

```
// Passkey creates a hardware-bound 32-byte key
// Same passkey on any device → same derived key
// Perfect for encrypting cloud backups
```

- Android support: Google Password Manager (2025+)
- iOS support: iOS 18.4+
- This means: passkey syncs → backup decryption key syncs automatically

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
- `SeedVault` — encrypt/decrypt/store seed phrase using master key
- `BiometricGate` — wraps BiometricPrompt + CryptoObject for signing operations
- `SecurityCapabilities` — detect StrongBox, TEE, biometric types available

### 2. `core:backup` Module (new)

Cloud backup and recovery.

**Responsibilities:**
- Encrypted seed backup to Google Drive (app-specific folder)
- Passkey PRF key derivation for backup encryption
- Backup metadata (creation date, device info, version)
- Restore flow orchestration

**Key classes:**
- `BackupManager` — create/restore encrypted backups
- `PasskeyKeyDeriver` — PRF extension for deterministic encryption keys
- `CloudStorageProvider` — Google Drive API for backup storage

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
| **Keys never in software** | Master key lives in StrongBox/TEE, non-extractable |
| **Biometric-gated signing** | Every tx requires face/fingerprint at hardware level |
| **Seed phrase encrypted at rest** | AES-256-GCM with hardware-backed key |
| **Memory safety** | Derived keys wiped after each operation |
| **Cloud backup security** | Backup encrypted with passkey-derived key (hardware-bound) |
| **Device loss recovery** | Passkey syncs via Google Password Manager → decrypt backup on new device |
| **No PIN/password** | Biometric is the only factor (hardware enforced) |

### Threat model

| Threat | Mitigation |
|--------|------------|
| App compromise (malware) | Master key is non-extractable from StrongBox. Even with root access, biometric is required. |
| Device theft (locked) | Biometric required. Key access counter can lock after N failures. |
| Device theft (unlocked) | Each crypto operation requires fresh biometric. No session caching. |
| Cloud backup leak | Encrypted with passkey PRF key. Useless without the passkey. |
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
  │     - Stored in: Google Wallet / credential provider
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
| **Digital Credentials API** | Browser/platform API for requesting + issuing credentials — Android routes to wallet app |

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
     │ navigator.credentials.get({      │                                │
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

### Kuira as Android Credential Provider

Android's `CredentialProvider` API lets apps register as credential sources. Kuira would:

1. Register as a `CredentialProviderService`
2. When Chrome/apps request credentials via Digital Credentials API, Android routes to Kuira
3. Kuira shows biometric prompt + approval UI
4. Root key (in StrongBox) signs the access key credential
5. Returns W3C VC to the requesting app

This is similar to how Google Password Manager provides passkeys — but Kuira provides blockchain access key credentials.

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

| Feature | MetaMask | Phantom | Lace | **Kuira (Tier 1)** |
|---------|----------|---------|------|---------------------|
| Seed phrase in onboarding | Yes (required) | Yes (required) | Yes (required) | **No** (hidden) |
| Hardware-backed keys | No | No | No (browser) | **Yes** (StrongBox/TEE) |
| Biometric signing | No | UI gate only | No | **Crypto-gated** (hardware) |
| Cloud backup | Manual only | Manual only | No | **Automatic** (encrypted) |
| Passkey auth | No | No | No | **Yes** |
| Recovery without seed phrase | No | No | No | **Yes** (cloud + passkey) |
| Time to first wallet | ~2 min | ~1 min | ~2 min | **~5 seconds** |

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

Both can be sent over the **Digital Credentials API** instead of HTTPS — this is exactly what the CTO proposes for local secure enclave issuance.

### Platform Support

| Platform | Credential Storage | Digital Credentials API | Notes |
|----------|-------------------|------------------------|-------|
| **Android** | Google Wallet, CredentialManager, 3rd party apps | Yes (via `navigator.credentials`) | Full support — Kuira can register as credential provider |
| **Apple** | Apple Wallet (closed partners only), 3rd party apps via Identity Document Services | Yes (via Digital Credentials API) | Cannot mint custom IDs into Apple Wallet — need 3rd party app |
| **Web** | Browser Credential Management API | Yes | Routes to registered credential providers |

**Android advantage:** Google's ecosystem is more open here. Kuira can register as a `CredentialProviderService` and handle credential requests from any app/browser. Apple's ecosystem requires 3rd party wallet apps for non-government credentials.

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
