# Sigil Identity — How It Works

## The Problem

Midnight uses **secp256k1** for transaction signing (Schnorr signatures).
Android passkeys use **P-256** (WebAuthn standard).
Different curves — a passkey can't sign Midnight transactions directly.

## The Solution: Two-Key Hierarchy

```
┌─────────────────────────────────────────────────────┐
│                   YOUR SIGIL                        │
│                                                     │
│   ┌───────────────┐         ┌──────────────────┐    │
│   │  Root Key      │ ──────▶│  Access Key       │    │
│   │  (P-256)       │ signs  │  (secp256k1)      │    │
│   │                │ authz  │                    │    │
│   │  Lives in:     │        │  Lives in:         │    │
│   │  Google PWM    │        │  HD Wallet         │    │
│   │  (hardware)    │        │  (derived from     │    │
│   │                │        │   seed)            │    │
│   │  You NEVER see │        │                    │    │
│   │  the private   │        │  Signs Midnight    │    │
│   │  key           │        │  transactions      │    │
│   └───────────────┘         └──────────────────┘    │
│         │                                           │
│         ▼                                           │
│   ┌───────────────┐                                 │
│   │  DID           │                                 │
│   │  did:key:zDn.. │                                 │
│   │                │                                 │
│   │  Your permanent│                                 │
│   │  identity      │                                 │
│   └───────────────┘                                 │
└─────────────────────────────────────────────────────┘
```

**Root key (P-256):** Created by Android's CredentialManager, stored in Google
Password Manager (hardware-backed on physical devices). The private key never
leaves the secure hardware. Syncs to new devices automatically.

**Access key (secp256k1):** Derived from the HD wallet seed at path
`m/44'/2400'/0'/5/0`. This is the key that actually signs Midnight
transactions. Deterministically recoverable from the seed.

**DID (did:key):** Derived from the root key's P-256 public key. One DID per
user, stable across all dApps. This is the user's permanent identity.

## The Flow

### Step 1: Forge Sigil (one-time setup)

```
User taps "Forge Sigil"
         │
         ▼
┌─────────────────────┐
│ Android              │
│ CredentialManager    │
│                      │
│ "Create a passkey    │
│  for nel349.github.io│
│  user: BBoard User"  │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Google Password      │     ┌──────────────────┐
│ Manager              │     │ User sees:        │
│                      │────▶│ Biometric prompt  │
│ Generates P-256      │     │ (fingerprint/PIN) │
│ keypair in TEE       │     └──────────────────┘
└──────────┬──────────┘
           │
           │ Returns attestation response
           │ (contains P-256 public key)
           ▼
┌─────────────────────┐
│ AttestationParser    │
│                      │
│ Extracts x,y coords  │
│ from CBOR/DER        │
│ response             │
└──────────┬──────────┘
           │
           │ Compressed P-256 public key (33 bytes)
           ▼
┌─────────────────────┐
│ DidKeyGenerator      │
│                      │
│ [0x80,0x24] +        │
│ compressed_pubkey    │
│ → base58btc          │
│ → "did:key:zDn..."   │
└──────────┬──────────┘
           │
           ▼
    Sigil is forged!
    DID: did:key:zDnaeQgBKgbY...
    Root key: 0203a4033fced9b4...
```

### Step 2: SDK Initialization (derives access key)

```
App creates MidnightSdk with seed
         │
         ▼
┌─────────────────────┐
│ HDWallet.fromSeed()  │
│                      │
│ Derives keys at      │
│ standard Midnight    │
│ paths:               │
│                      │
│ m/44'/2400'/0'/0/0   │──▶ Unshielded address
│ m/44'/2400'/0'/2/0   │──▶ Dust seed
│ m/44'/2400'/0'/3/0   │──▶ Coin public key (ZSwap)
│ m/44'/2400'/0'/5/0   │──▶ Access key (IDENTITY) ◀── NEW
│                      │
└──────────┬──────────┘
           │
           ▼
    SDK ready with:
    - Wallet address
    - Coin public key
    - Access key: 0288a5cf7a8e73af... (secp256k1)
    - Access key path: m/44'/2400'/0'/5/0
```

### Step 3: Authorize Access Key (passkey signs delegation)

This is the critical step — the root key cryptographically authorizes
the access key. Self-verifiable, no server trust needed.

```
┌─────────────────────────────────────────────┐
│ Build authorization payload (99 bytes)       │
│                                              │
│ ┌──────────────────────────────────────────┐ │
│ │ "KUIRA-AUTH-V1"     (13 bytes, magic)    │ │
│ │ Root P-256 pubkey   (33 bytes)           │ │
│ │ Access secp256k1    (33 bytes)           │ │
│ │ Scope flags         (4 bytes, FULL)      │ │
│ │ Timestamp           (8 bytes)            │ │
│ │ Expiry              (8 bytes, 0=never)   │ │
│ └──────────────────────────────────────────┘ │
└──────────────────┬──────────────────────────┘
                   │
                   │ SHA-256(payload) = challenge
                   ▼
┌─────────────────────┐
│ PasskeyManager      │
│ .authenticate()     │
│                     │
│ Challenge = hash    │
│ of our payload      │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Google Password      │     ┌──────────────────┐
│ Manager              │     │ User sees:        │
│                      │────▶│ Biometric prompt  │
│ Signs:               │     │ (second tap)      │
│ authenticatorData || │     └──────────────────┘
│ SHA-256(clientData)  │
│                      │
│ clientData contains  │
│ our challenge (the   │
│ payload hash)        │
└──────────┬──────────┘
           │
           │ Returns ECDSA P-256 signature (72 bytes)
           ▼
┌──────────────────────────────────────────┐
│ Authorization Record                      │
│                                           │
│ ┌───────────────────────────────────────┐ │
│ │ DID:           did:key:zDn...         │ │
│ │ Payload:       99 bytes (root+access) │ │
│ │ Signature:     72 bytes (P-256 ECDSA) │ │
│ │ AuthData:      from WebAuthn          │ │
│ │ ClientDataJSON: from WebAuthn         │ │
│ │ Access path:   m/44'/2400'/0'/5/0     │ │
│ └───────────────────────────────────────┘ │
│                                           │
│ SELF-VERIFIABLE: anyone with the root     │
│ public key can verify this signature      │
│ without trusting any server.              │
└──────────────────────────────────────────┘
```

### Step 4: Sign Midnight Transactions

```
dApp calls contract.call("post", "Hello!")
         │
         ▼
┌─────────────────────┐
│ Circuit execution    │
│ (QuickJS)            │
│                      │
│ Produces unproven tx │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ ZK Proving           │
│ (local, on-device)   │
│                      │
│ Produces proven tx   │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Balancing            │
│ (dust fee payment)   │
│                      │
│ Access key signs     │◀── secp256k1 at m/44'/2400'/0'/5/0
│ the dust spend       │    (authorized by root passkey)
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Submit to blockchain │
│                      │
│ Node verifies        │
│ Schnorr signature    │
└──────────┬──────────┘
           │
           ▼
    Transaction finalized!
```

## Why This Matters

### vs. rvcas (midnightOS Passkeys)

```
                    rvcas                    Kuira
                    ─────                    ─────
Root key storage    Browser authenticator    TEE / StrongBox
Access key storage  localStorage (cleartext) HD Wallet (encrypted)
Authorization       Server-attested          Self-verifiable
                    (trust rvcas.dev)         (TEE signs directly)
Recovery            None (browser-only)      Google PWM sync + PRF
Offline signing     No (needs server)        Yes (access key local)
```

### The Self-Verifiable Advantage

rvcas's model:
```
1. Server creates challenge with access key
2. Passkey signs challenge
3. Server verifies signature
4. Server DISCARDS signature, stores only metadata
5. Verifiers must trust passkeys.rvcas.dev
```

Kuira's model:
```
1. App builds payload with both keys + scope
2. Payload hash becomes WebAuthn challenge
3. Passkey signs (biometric prompt)
4. Signature is KEPT in the authorization record
5. Anyone can verify: check P-256 signature against payload
   No server trust needed. Cryptographic proof.
```

## Component Map

```
core/identity/
├── passkey/
│   ├── PasskeyManager.kt      ─── CredentialManager wrapper
│   ├── AttestationParser.kt    ─── P-256 extraction (DER + CBOR)
│   └── CborParser.kt          ─── Minimal WebAuthn CBOR parser
├── did/
│   └── DidKeyGenerator.kt     ─── did:key derivation
├── accesskey/
│   └── AccessKeyManager.kt    ─── HD-derived secp256k1 keys
├── auth/
│   ├── KeyAuthorization.kt    ─── Authorization payload builder
│   ├── AuthorizationRecord.kt ─── Persistent record
│   └── AuthorizationStore.kt  ─── AES-256-GCM encrypted storage
├── util/
│   └── ByteArrayUtil.kt      ─── Shared BigInteger conversion
└── di/
    └── IdentityModule.kt     ─── Hilt DI wiring

sdk/midnight-sdk/
└── MidnightSdk.kt             ─── Exposes accessKeyPublicKey + path

examples/bboard/
└── BBoardViewModel.kt          ─── Reference: forgeSigil + authorizeAccessKey
```

## Tested On

- Emulator API 35, Google Play Services
- Google Password Manager as credential provider
- DAL hosted at nel349.github.io
- Full flow: forge → connect → authorize → post/takeDown
