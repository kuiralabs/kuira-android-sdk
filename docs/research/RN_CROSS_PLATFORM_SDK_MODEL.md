# Cross-Platform SDK Architecture: Why the Sigil Needs Native Code on Every Platform

**Status:** Research / architecture analysis (not an approved plan)
**Created:** 2026-05-24
**Question it answers:** *Can another developer rebuild Kuira's Sigil SDK design in pure React Native, or must they write native code for both Android and iOS?*

---

## TL;DR

**No, this design cannot be done in pure React Native.** The Sigil — passkey/PRF, secure storage, biometric gating, and cloud backup — touches platform OS APIs and a native Rust library. React Native has **no JavaScript access** to any of these. Every one of them must be implemented as a **Turbo Native Module**, which by definition is written **twice**: once in **Kotlin** (Android) and once in **Swift** (iOS), behind a single shared TypeScript interface.

So: **a complete Sigil RN SDK is gated on having both native implementations.** Building the iOS-native Sigil *is* building the iOS half of the RN package — they are the same code.

---

## The Mental Model

React Native is **a JavaScript runtime plus a bridge to native code.** That is the entire model. It gives you JavaScript, and it gives you a way to *call into* native code — but it gives you **nothing from the operating system for free.**

A capability needs native code whenever it touches either of these:

1. **A native library** — e.g. our Rust crypto core (`kuira-crypto-ffi`, compiled to `.so`/`.a`). JavaScript cannot call a compiled binary directly.
2. **A platform OS API** — e.g. passkeys, biometrics, secure storage, cloud backup. These are exposed only to Kotlin/Java (Android) and Swift/Objective-C (iOS).

This gives us **three layers**, and every piece of the SDK belongs to exactly one. We use these three names consistently throughout this document:

| Layer | Name | What it is | Written how many times | Runs where |
|-------|------|-----------|------------------------|-----------|
| **L1** | **Shared Logic** | Pure TypeScript — computation + network only | **Once** | Everywhere (Android, iOS, agents, web) |
| **L2** | **Native Bridge** | A **Turbo Native Module**: Kotlin impl + Swift impl behind one TS interface | **Twice** (one per OS) | Only where its native half exists |
| **L3** | **Presentation** | UI screens | **Per framework** (Compose / SwiftUI / RN JSX) | Per app |

The whole question of "what's doable in RN" reduces to: **which layer is each capability in?** L1 is free across platforms. L2 costs you two native implementations. L3 is rewritten per UI framework.

> **On terminology:** a *Turbo Native Module* is React Native's New-Architecture native-module API — the default since RN 0.76, with the legacy bridge removed as of RN 0.82. Wherever this document says **L2**, it means a Turbo Native Module.

---

## Kuira's Capabilities, Mapped to Layers

| Capability | Today (Android) | Layer | Needs iOS-native first? | Risk |
|-----------|-----------------|-------|:----------------------:|------|
| Connector / ConnectedAPI / tx-orchestration / indexer (GraphQL + WebSocket) | Kotlin (Ktor) | **L1 — Shared Logic** | No | Low |
| Sigil DID derivation (PRF output → Ed25519 → `did:key`) | Kotlin (pure compute) | **L1** (math) — but depends on the L2 PRF ceremony below | No (math) | Low |
| Rust crypto core (shielded keys, Schnorr signing, dust, contract VM, ZK proving) | Rust `.so` via JNI | **L2 — Native Bridge** | **Yes** | Medium |
| Passkey + PRF ceremony | `androidx.credentials.CredentialManager` | **L2** | **Yes** | Medium |
| Secure storage + key wipe | Android Keystore / EncryptedSharedPreferences | **L2** | **Yes** | Medium |
| Biometric gate | `BiometricPrompt` | **L2** | **Yes** | Low–Med |
| Cloud backup of the encrypted blob | Block Store (Google Play services) | **L2** | **Yes** | Med–High |
| dApp / wallet UI | Jetpack Compose | **L3 — Presentation** | No (it's JS) | Expected rewrite |

**Read the "Layer" column:** the entire Sigil — every security-critical capability — is **L2**. That is the crux.

---

## Why the Sigil Is Unavoidably L2 (Native on Both Platforms)

Each Sigil capability binds to an OS API or native library that has **no JavaScript equivalent**. The two native sides are *different APIs*, not a copy-paste:

| Sigil capability | Android native (Kotlin) | iOS native (Swift) |
|------------------|-------------------------|--------------------|
| Passkey + PRF | `androidx.credentials.CredentialManager` + WebAuthn PRF extension | `AuthenticationServices` (`ASAuthorizationPlatformPublicKeyCredentialProvider`) + PRF extension |
| Secure storage | Android Keystore / EncryptedSharedPreferences | Keychain Services + Secure Enclave |
| Biometric gate | `BiometricPrompt` | `LocalAuthentication` (`LAContext`) |
| Cloud backup | `Blockstore` byte store + cloud backup | iCloud Keychain / `NSUbiquitousKeyValueStore` / CloudKit |
| Rust crypto core | `.so` loaded via JNI | `.a`/framework cross-compiled to `aarch64-apple-ios`, called from Swift |

For **each** row: there is no RN JS API, so it must be an L2 Native Bridge; an L2 module is two native implementations; therefore the Sigil requires **both** the Kotlin stack and the Swift stack. The shared TypeScript interface on top is inert on an iPhone until the Swift half is written.

Note the cloud-backup row especially: iOS has **no Block Store**. The iOS side is a *different* mechanism (iCloud Keychain / `NSUbiquitousKeyValueStore` / CloudKit) with different semantics — so it's a re-design, not a port.

---

## The Consequence for the RN Roadmap

Because the Sigil is entirely L2:

1. **"RN fully" is mathematically gated on iOS-native.** You cannot ship Sigil sign-in, PRF-derived keys, or cloud recovery to an iPhone through React Native until the Swift implementations exist. No JS library or WASM trick substitutes for Secure Enclave, iCloud, or the iOS PRF API.

2. **Building iOS-native *is* building the iOS half of the RN package.** If the iOS work is structured as **RN-consumable Turbo Native Modules from day one** (clean TS interface, Swift implementation behind it), then "the native iOS app" and "RN-on-iOS" collapse into nearly the same artifact. Build a standalone SwiftUI app first and you'd have to re-bridge all of it later — wasted effort.

3. **Only L1 (Shared Logic) is build-anytime and iOS-independent.** The TypeScript connector/orchestration layer is the same code the agent SDK can share. It is never wasted and never blocked on iOS. Everything security-critical is not.

---

## What a Third-Party Dev Would Actually Have to Build

To replicate this *specific* SDK design (private self-custodial Sigil over a privacy chain), a developer must produce, **per capability**:

- **one** TypeScript interface (the JS API surface),
- **one** Kotlin implementation (Android native module),
- **one** Swift implementation (iOS native module, + an Objective-C bridge file for legacy modules), and
- the **Rust crypto core** cross-compiled to *both* `arm64-android` and `aarch64-apple-ios`. Mozilla's **UniFFI** can generate the Kotlin *and* Swift bindings — and Python, for an agent SDK — from one Rust interface definition, so the core is written once and bound three ways.

The package skeleton matches the established RN native-module template (see `../../../../android/docs/projects/elevenLabs.md`):

```
react-native-<sdk>/
├── android/   → *.kt        (Kotlin native module)
├── ios/       → *.swift, *.m (Swift native module + ObjC bridge)
├── src/       → *.ts         (shared JS API + native interface)
└── example/                  (example RN app, both platforms)
```

This is the real barrier to entry, and it is **why the Sigil is defensible**: it is not a weekend `npm install`. It is two parallel native security implementations plus a cross-compiled cryptographic core plus the PRF-derivation choreography — across two operating systems with different credential, storage, and backup models.

---

## What's Portable vs Not (Summary)

- **Portable, write once (L1):** the connector/ConnectedAPI, transaction orchestration, indexer client (GraphQL + WebSocket), and the pure-math Sigil DID derivation. Shared with the agent SDK.
- **Not portable, write twice (L2):** the Rust crypto core binding, passkey/PRF, secure storage, biometrics, cloud backup. The entire Sigil.
- **Rewritten per UI framework (L3):** all screens. Compose does not cross over; design translates, code does not.

---

## Open Decisions & Risks

1. **iOS Rust cross-compile spike (long pole).** Confirm the Midnight Rust crates (`midnight-zswap`, `midnight-ledger`, `midnight-zkir`, etc.) build for `aarch64-apple-ios`. Every L2 crypto feature on iOS depends on this. Resolve before committing the iOS/RN order.
2. **PRF specifics (verify, don't block).** A maintained RN library (`react-native-passkeys`) supports the WebAuthn PRF extension — `eval` and `evalByCredential`, on create and assert — and iOS exposes the native PRF APIs since iOS 18 (via iCloud Keychain). Remaining unknowns to *verify, not assume*: multi-salt PRF (`eval.first` + `eval.second`) for our dual sigil/seed derivation; PRF-on-create for the single-biometric forge; the early-iOS-18 data-loss caveat. (No external security-key PRF on iOS yet — not relevant to platform passkeys.)
3. **Block Store ↔ iCloud semantics mismatch.** The iOS backup is a different API with different defaults; needs a UX/semantics design pass, not a port.
4. **Agent-layer language fork.** Prior docs envision agents = TypeScript sharing the L1 connector with RN (maximum reuse). A Python (PyO3) agent SDK would not share with RN. If RN reuse matters, the TS connector is higher-leverage; alternatively keep the Rust core shared and ship two thin facades.

---

## Verification Status

Checked 2026-05-24. One claim in the first draft was corrected (passkey PRF maturity).

**Verified against this codebase (prior session investigations):**
- The Rust crypto core is Android-only via JNI (`kuira-crypto-ffi`, `System.loadLibrary`); there is no Kotlin Multiplatform anywhere in the repo.
- The identity layer (passkey/PRF, secure storage, biometric) and Block Store are Android-API-bound and isolated from the crypto core.
- The dApp/wallet UI is Jetpack Compose, explicitly scoped Android-only (`docs/projects/dapp-ui-extraction.md`).
- No prior comprehensive RN/iOS analysis existed; cross-platform intent appears only as hints in `docs/PLAN.md` ("RN future: TS + native modules — shares code with agents"; "iOS future: Swift SDK") and `docs/planning/archive/DAPP_CONNECTOR_PLAN.md` ("all clients talk the same ConnectedAPI").

**Verified externally (web, 2026-05-24):**
- **Turbo Native Modules** are the React Native New-Architecture native-module API, **default since RN 0.76** (Oct 2024); the legacy bridge is removed as of RN 0.82. So L2 = Turbo Native Module is the standard, not an optional path. ✅
- **iOS PRF**: the `ASAuthorizationPublicKeyCredentialPRF…` APIs are **public since iOS 18**, operating via iCloud Keychain. Caveats: an early iOS 18 bug could cause data loss; external security keys are not yet supported on iOS. ✅
- **CORRECTION — RN passkey PRF is not immature.** The first draft of this document claimed RN passkey PRF support was immature and would likely require a fork. In fact `react-native-passkeys` (peterferguson) supports the PRF extension — `eval` and `evalByCredential`, on both create and assert. The passkey row's risk was downgraded **High → Medium** accordingly. (Still verify multi-salt and PRF-on-create against our specific flow.)
- **UniFFI** (Mozilla) has first-party support for **Kotlin, Swift, and Python** from a single Rust interface definition — the same crypto core can bind to both mobile native bridges *and* a Python agent SDK. ✅

**Sources:**
- [React Native New Architecture — Turbo Modules](https://github.com/reactwg/react-native-new-architecture/blob/main/docs/turbo-modules.md)
- [Apple — ASAuthorizationPublicKeyCredentialPRFAssertionInput](https://developer.apple.com/documentation/authenticationservices/asauthorizationpublickeycredentialprfassertioninput-swift.struct)
- [Passkeys & WebAuthn PRF for End-to-End Encryption (Corbado, 2026)](https://www.corbado.com/blog/passkeys-prf-webauthn)
- [react-native-passkeys (peterferguson)](https://github.com/peterferguson/react-native-passkeys)
- [UniFFI — multi-language bindings generator for Rust (Mozilla)](https://github.com/mozilla/uniffi-rs)
