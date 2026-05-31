# Sigil portability — escaping the WebAuthn rpId trap

*Investigation date: 2026-05-30*
*Status: recommendation pending maintainer approval*

## TL;DR

- **The trap is structural, not a bug.** WebAuthn's PRF/`hmac-secret` extension binds its derived secret to `(credential, rpId)`. Two Kuira apps on two different rpIds (`nel349.github.io` vs `kuiralabs.github.io`) cannot, by spec, derive the same seed/DID/backup-key — not now, and not under any drafted future spec.
- **No clean escape exists at the WebAuthn layer.** Related Origin Requests (ROR, L3-WD-02) does not share credentials across rpIds; it shares one rpId across origins. WG issue #1827 ("same credential across domains") has no draft and no champion — cross-rpId sharing breaks the phishing-resistance invariant WebAuthn exists to guarantee.
- **Six candidate paths were evaluated adversarially.** Five are unviable as primary architectures: hardware-Keystore-only, mnemonic-first, decoupled-identity+mnemonic-portable-wallet, cloud-escrowed-seed, ROR/future-spec-bet. One — a *canonical Kuira rpId* — is viable only as a tightly-scoped band-aid for first-party apps.
- **Recommendation: two-track.** Track A (now, 4-6 weeks): adopt a canonical Kuira rpId `sigil.kuira.app` for first-party apps (Wallet, BBoard, Kicks, starter), with strict per-app salt domain separation so apps share an *identity surface* but not a *seed surface*. Track B (next 2 quarters): build delegated-spend authority (roadmap #27) so cross-app fund access is an explicit, revocable on-chain capability instead of an implicit seed-sharing arrangement.
- **What the user actually wants — "use my current account with funds and a specific sigil from a new Kuira app" — is solvable, but not by sharing a seed.** It is solvable by (a) re-using the same passkey credential under a canonical rpId for identity/DID, and (b) delegating scoped spend rights from the user's flagship Wallet sigil to other apps via signed authorizations. Track A unlocks identity portability immediately; Track B unlocks fund portability without re-centralizing custody.

## The problem

Kuira's identity primitive is a single P-256 platform passkey held by Google Password Manager (GPM). Everything — the user's DID, the wallet seed, the AES key for the Block Store backup blob — chains off PRF outputs derived from that passkey via three domain-separated salts. The PRF computation inside the authenticator is `HMAC-SHA256(credSecret, SHA-256("WebAuthn PRF" || 0x00 || salt))`, and `credSecret` is sealed into the authenticator and bound to `(credential, rpId)` at credential-creation time. Change the rpId, and you change the credential's PRF output for every salt, forever.

The concrete consequence: when the user installs a *second* Kuira application — say, a third-party dApp built on top of `kuira-starter-android` and deployed under `kuiralabs.github.io` — they cannot bring their existing sigil with them. The starter mints a fresh passkey under its own rpId. The DID changes. The wallet seed changes. The Block Store backup blob is undecryptable. The on-chain identity that owned BBoard posts and Kicks games is invisible to the new app. As the maintainer put it: *"imagine I can't use my current account with funds and a specific sigil because of this."*

Today both BBoard and Kicks happen to coexist under `nel349.github.io` (a single registered rpId), which masks the trap. The moment any Kuira app ships under a different domain — and the starter must, because it's a template for arbitrary third-party developers — the per-rpId scoping bites.

## Current architecture

The full derivation chain, distilled from `core/identity/.../sigil/`, `core/identity/.../backup/`, `core/identity/.../passkey/`, `sdk/wallet-seed/`, and `core/auth/`:

**Root primitive.** `PasskeyManager.authenticateWithPrf(activity, challenge, prfSalt, prfSaltSecond?)` returns a `PrfAssertionResult` whose `prfOutput` and `prfOutputSecond` are 32-byte buffers — the HMAC outputs computed inside the authenticator. PRF-on-create is also used at registration via `CreatePublicKeyCredentialRequest`'s `extensions.prf.eval`.

**Three salts**, all `SHA-256` of a constant ASCII string:

| Salt | Source | Purpose |
|---|---|---|
| `SeedDeriver.SIGIL_SALT` | `SHA-256("kuira:sigil:v1")` | DID derivation |
| `SeedDeriver.SEED_SALT` | `SHA-256("kuira:seed:v1")` | Wallet seed entropy |
| `AppStateBackup.BACKUP_SALT` | `SHA-256("kuira:backup:v1")` | AES-256-GCM key for the Block Store blob |

**DID chain** (`Ed25519PrfSigilProvider.deriveFromPrfOutput`): `PRF(passkey, SIGIL_SALT)` → 32-byte Ed25519 seed → 32-byte pubkey → `did:key:z6Mk…` via `DidKeyGenerator.fromEd25519`.

**Wallet seed chain** (`SeedDeriver.entropyToBip39Seed` → `WalletSeedSource`): `PRF(passkey, SEED_SALT)` → 32 bytes treated as BIP-39 entropy → 24-word mnemonic → PBKDF2-HMAC-SHA512 (2048 iters, salt `"mnemonic"`, no passphrase) → 64-byte BIP-39 seed → fed into `MidnightSdk.Builder.seed(...)`.

**Backup AES key** (`PrfKeyDeriver.deriveKey`): `PRF(passkey, BACKUP_SALT)` → HKDF-SHA256 (Extract with empty salt, Expand with info `"kuira-backup-encryption-v1"`) → 32-byte AES-256-GCM key.

**Storage.** None of the derivation intermediates persist — the PRF output, Ed25519 keypair, entropy, mnemonic, and BIP-39 seed all live only inside the function that computes them and are zeroed via `ByteArray.fill(0)` in `finally`. What does persist:

- `SharedPreferences` (`sigil_identity`, MODE_PRIVATE): `did`, `credentialId`, `publicKeyHex`, `backupDismissed`. Public material only.
- `SharedPreferences` (`wallet_panel`): `seedIsPrfDerived: Boolean` marker.
- `filesDir/kuira_seed.bin`: AES-256-GCM-encrypted `PlaintextSeed` (32-byte entropy + 64-byte BIP-39 seed). Wrapping key is `kuira_master_key` in Android Keystore, hardware-backed (StrongBox preferred), gated by `BiometricGate`. Atomic write via `kuira_seed.bin.tmp`.
- `filesDir/kuira_authorizations.bin`: AES-256-GCM-encrypted JSON list of `AuthorizationRecord` (DID-keyed).
- Block Store blob: `[1-byte version=2][12-byte IV][AES-256-GCM(appMetadata)]`. The wallet seed is **never** in this blob; the seed re-derives from the passkey on the new device.

**rpId coupling.** rpId enters at one configuration point — `PasskeyConfig(rpId, rpName, timeoutMs)` in `PasskeyManager.kt` line 361, supplied by the consuming app via `IdentityModule.providePasskeyManager` (no SDK default, fails fast). It propagates into `buildRegistrationRequestJson` (`rp.id = config.rpId`) and every `buildAuthenticationRequestJson` (`rpId = config.rpId`). Downstream, every PRF-derived secret transitively depends on rpId because `credSecret` is rpId-bound inside the authenticator. The salts themselves are rpId-independent constants — the binding is entirely inside the authenticator's opaque PRF computation.

**`hostAppLabel()`** (passed as WebAuthn `user.name`/`user.displayName`) is cosmetic — it changes the biometric prompt's display text and nothing else.

## Constraints we can't change

These constraints define the shape of the solution space. Any candidate path that pretends to relax them is wishful.

1. **WebAuthn rpId scoping is load-bearing security, not a bug.** A credential's `rpId` is mixed into the assertion signature and into the `credSecret` that feeds PRF. Cross-rpId sharing would defeat phishing resistance — the entire reason WebAuthn exists. The W3C Working Group has been explicit on this for years (issues #865, #1372, #1827).

2. **The PRF/hmac-secret extension binds output to `(credential, rpId)`.** Same salt + different rpId = different output, by authenticator firmware design (CTAP2). No spec text, no draft, and no open issue proposes to change this. ROR (W3C WebAuthn L3 PR #2040) does not change it — it lets multiple *origins* share one *rpId*, which is the opposite direction from what most readers initially assume.

3. **Block Store is per-package and Google enforces it inside Play Services.** "An app can only retrieve data stored by itself. Google Play services checks both the package name and signature." 16 entries × 4 KB per package. No `sharedUserId` escape hatch, no ContentProvider surface.

4. **Android Keystore aliases are per-UID/per-package and non-exportable.** Keys created by `app.kuira.wallet` cannot be referenced by `app.kuira.kicks`. This is a TEE/StrongBox guarantee, not a policy knob.

5. **`sharedUserId` is deprecated.** Since API 29, "strongly discouraged, may be removed." Cannot be added to an already-installed app without breaking it. Not a viable cross-app primitive.

6. **There is no Android equivalent of iOS Keychain Access Groups (Team-ID-scoped).** Closest analogue is a signature-protected ContentProvider in a designated host app — explicit IPC, not transparent shared storage.

7. **GPM credential storage is account-scoped, not user-controlled per-app.** Two devices on the same GPM account share a synced passkey; two GPM accounts produce two distinct sigils even with identical rpId. This is the constraint behind `project_sigil_gpm_account_constraint`.

8. **`did:key` is non-rotatable.** A DID derived from a public key is permanently bound to that key. Rotating the underlying key produces a new DID with no spec-defined link to the old one.

9. **`*.github.io` is on the Public Suffix List.** Neither `nel349.github.io` nor `kuiralabs.github.io` can serve as an rpId, full stop. WebAuthn clients raise `SecurityError`. ROR is structurally blocked for user-sites; only custom domains qualify.

## Candidate paths

Six paths were evaluated, each with adversarial refutation, 7-criterion scoring (1–5), and a verdict.

### Path 1 — Hardware-Keystore-backed seed (passkey eliminated)

**One-liner.** Replace passkey-PRF derivation with a seed generated by `SecureRandom`, wrapped under an Android Keystore master key, mirrored to Google Drive (`drive.appdata` or `drive.file` scope) for cross-app retrieval.

| Criterion | Score |
|---|---|
| Portability | 2 |
| Sovereignty | 2 |
| UX cost | 2 |
| Security | 3 |
| Migration cost | 1 |
| Engineering effort | 1 |
| Long-term viability | 2 |

**Killer concerns.**
- Android Keystore aliases are package-scoped and non-exportable — Keystore alone cannot give cross-app access. The "portability" claim secretly depends on Drive REST, which requires per-app OAuth consent with a restricted scope subject to Google's verification + CASA audit.
- Loses the "seed never exists at rest" invariant. The encrypted seed blob becomes a long-lived high-value target whose compromise (e.g., a future StrongBox CVE) leaks every wrapped seed retroactively.
- Breaks every existing user's DID, with no clean `did:key` rotation story.
- Cross-device recovery requires either a portable wrap key (back to user passphrase / PIN — MetaMask's PBKDF2 vault model) or re-pairing per device, defeating the simplicity claim.

**What it would look like in Kuira.** Delete `Ed25519PrfSigilProvider`, `SeedDeriver`, and the PRF call sites in `WalletSeedSource.ensureSeedReady`. Replace `SeedVault` wrapping flow with a Drive REST sync layer (~3 KLOC new). Add OAuth consent + multi-account selection UI. Introduce a per-app pairing handshake (novel, unaudited). Build a DID-rotation migration tool that walks every BBoard contract reference.

**Verdict.** UNVIABLE.

### Path 2 — Mnemonic-first, biometric-as-convenience

**One-liner.** Generate a BIP-39 mnemonic at onboarding, show it to the user, store it AES-encrypted gated by `BiometricPrompt`. Same UX as Phantom / MetaMask / Backpack.

| Criterion | Score |
|---|---|
| Portability | 2 |
| Sovereignty | 4 |
| UX cost | 1 |
| Security | 2 |
| Migration cost | 2 |
| Engineering effort | 4 |
| Long-term viability | 2 |

**Killer concerns.**
- Doesn't actually solve cross-app portability. Mnemonics give *cross-install* recovery on one device (Kuira already has that via Block Store + GPM passkey, with strictly better UX). For two installed apps to share funds, the user re-types 24 words into the second app, or routes through a hub wallet — same gap as today, worse UX.
- Re-introduces the 40–70% onboarding drop-off documented across every self-custody UX study.
- Loses hardware attestation: once the mnemonic is decrypted, anything in the process memory can sign. The current PRF flow has no comparable memory window.
- MetaMask's reference implementation uses PBKDF2 with **5,000 iterations** (OWASP 2026 recommends 600k+). Adopting "industry standard" imports a known-weak crypto baseline.

**What it would look like in Kuira.** `SeedDeriver.entropyToBip39Seed` already exists. New: backup wizard, 24-word verification quiz, import flow, dual-recovery reconciliation logic, forced "record your phrase" migration for existing PRF users (one-way security downgrade).

**Verdict.** UNVIABLE as the primary path. **Viable as an opt-in mnemonic *export* on top of the existing passkey flow** — a sovereignty escape hatch for power users who fear Google account loss, without imposing seed-phrase UX on the 95% who don't need it.

### Path 3 — Decoupled identity (passkey, per-app) + wallet (mnemonic, portable)

**One-liner.** Keep per-app passkey for the DID (privacy default — different DID per app). Make the wallet seed a portable mnemonic shared across apps.

| Criterion | Score |
|---|---|
| Portability | 3 |
| Sovereignty | 4 |
| UX cost | 2 |
| Security | 2 |
| Migration cost | 2 |
| Engineering effort | 2 |
| Long-term viability | 2 |

**Killer concerns.**
- "Apps share the same seed" silently means apps share a secret. A compromised BBoard install drains Kicks funds. The blast radius of every Kuira app becomes the union of every Kuira app's security posture — worse than today, and worse than Phantom/MetaMask which are single-app trust domains by construction.
- Inter-app seed transport is unsolved on Android. Clipboard is catastrophic; Intent extras are logged and screenshot-able; signature-protected ContentProvider requires coordinated permission declarations across 100 apps; "user retypes mnemonic" defeats the point.
- Per-app DIDs + shared funds undoes the privacy benefit of per-app DIDs (on-chain analysis correlates apps through the shared UTXO graph).
- iOS-parity story breaks: Keychain Access Groups are Team-ID-scoped, so the cross-app shared seed only works across same-Team-ID apps. Two-platform SDK ends up with two different portability mechanisms.

**What it would look like in Kuira.** A new `SharedSeedHostService` (AIDL or signature-protected ContentProvider) running in one designated Kuira app, exposing the seed to other Kuira apps after a per-caller-signature check. Plus a new "shared wallet" UI affordance and contract-authority audit (many Midnight contracts assume one-identity-one-spender).

**Verdict.** UNVIABLE.

### Path 4 — Cloud-escrowed seed (Drive / iCloud)

**One-liner.** Generate a seed, encrypt it (under user password, PRF, or cloud KMS), store the ciphertext in user-controlled cloud storage so any Kuira app on any device can fetch and decrypt it.

| Criterion | Score |
|---|---|
| Portability | 3 |
| Sovereignty | 1 |
| UX cost | 2 |
| Security | 1 |
| Migration cost | 2 |
| Engineering effort | 3 |
| Long-term viability | 2 |

**Killer concerns.**
- Solves the wrong problem. Cross-app fund access is fundamentally a *delegation* problem, not a *secret-distribution* problem. Escrowing the seed answers the latter by abolishing the boundary that makes delegation interesting.
- Destroys the no-seed-at-rest invariant — the canonical 256-bit secret now sits in cloud storage forever as an offline-brute-force target.
- "User-controlled cloud storage" is a euphemism: any OAuth-granted app holds programmatic read/write to the blob; the user controls the storage *location*, not the *access*.
- On iOS, App Group / iCloud container isolation is Team-ID-scoped — the "any Kuira app fetches the same blob" promise is structurally impossible across independent developers without funneling through one org's Team ID.
- The `drive.appdata` scope is per-OAuth-client-ID. To get cross-app sharing on Android, you must use the *visible* `drive.file` or `drive` scope, which triggers Google's restricted-scope verification and scares users at consent time.

**What it would look like in Kuira.** Mature Drive/iCloud SDKs exist. New: versioned AES-GCM envelope format, key-derivation story (which is itself a research project — every option recreates an existing problem), conflict resolution across concurrent writers, OAuth flows, account-loss recovery UX, security audit.

**Verdict.** UNVIABLE as the primary architecture. Possibly viable as one *optional* tier alongside the existing model — but not as the cross-app mechanism.

### Path 5 — WebAuthn Related Origin Requests / "future cross-rpId spec"

**One-liner.** Use WebAuthn L3 ROR (PR #2040, merged 2024-07-17) to share credentials across Kuira app rpIds. Or wait for a hypothetical future cross-rpId mechanism.

| Criterion | Score |
|---|---|
| Portability | 2 |
| Sovereignty | 1 |
| UX cost | 3 |
| Security | 1 |
| Migration cost | 2 |
| Engineering effort | 2 |
| Long-term viability | 1 |

**Killer concerns.**
- **ROR does not do what the path claims.** It lets multiple web *origins* request credentials scoped to one shared *rpId*. The credential's rpId remains a single value; the PRF output is identical across origins *because they're all the same RP*. This is the same outcome you get by picking one rpId and putting it in every app's `assetlinks.json` — ROR adds nothing here.
- The "future cross-rpId spec" line item has no champion, no draft, no PR. WG issue #1827 sits open precisely because cross-rpId sharing would break the phishing-resistance invariant. Betting on it is betting against WebAuthn's load-bearing design choice.
- Even granting the misreading, on Android the ROR `/.well-known/webauthn` document is consumed by browser user-agents, not by native `androidx.credentials`. Native callers already use `assetlinks.json`, which is the proper analogue.
- Public Suffix List blocks `*.github.io` from being an rpId at all. ROR cannot help; only a custom domain qualifies.
- CTAP2 hmac-secret binding is `(credSecret, rpId)` at the authenticator-firmware level. Any future "cross-rpId" mechanism would require a CTAP3-class change, multi-year FIDO certification cycles, and would exclude every security key currently in the field.

**Verdict.** UNVIABLE.

### Path 6 — Canonical Kuira rpId convention (band-aid)

**One-liner.** Pick one rpId (e.g., `sigil.kuira.app`), register every Kuira app's signing-cert SHA-256 in `https://sigil.kuira.app/.well-known/assetlinks.json`. Every app shares one passkey credential.

| Criterion | Score |
|---|---|
| Portability | 4 |
| Sovereignty | 1 |
| UX cost | 4 |
| Security | 1 (as stated) / 3 (with per-app salt domain separation) |
| Migration cost | 2 |
| Engineering effort | 4 |
| Long-term viability | 2 |

**Killer concerns (as stated, without modifications).**
- Centralizes the entire ecosystem under one DNS name. Domain lapse, seizure, or compromise = every Kuira app loses passkey access simultaneously.
- Kuira-org becomes a gatekeeper deciding which third-party apps qualify as "Kuira apps." Contradicts the open-ecosystem framing.
- **With shared salts, any allowlisted app reconstructs the full wallet seed.** A single compromised partner app drains every user. This is strictly worse than per-app rpId isolation.
- Cert rotation is catastrophic — JSON parse failure or fingerprint mistake breaks domain verification for every app in the file.
- Doesn't survive Kuira-org demise: if the domain is repurposed, the identity layer evaporates.
- Doesn't help the GPM-account constraint (`project_sigil_gpm_account_constraint`) at all — sigil is still captive to one Google account.

**The unlock.** *With strict per-app salt domain separation* — `PRF(passkey, SHA-256("kuira:seed:v1:" || appId))` instead of the current `SHA-256("kuira:seed:v1")` — the seed-sharing failure mode disappears. Apps share an *identity surface* (DID via shared SIGIL_SALT, optionally) but not a *seed surface*. This costs the "same funds in every app" property, which is exactly what we want to give to delegated authority (Path B below) instead.

**Verdict.** UNVIABLE as a permanent architecture. **VIABLE-WITH-CAVEATS as a first-party band-aid** when scoped strictly: first-party apps only, public sunset commitment, per-app salt domain separation enforced, user-visible disclosure at forge time, domain ops SLA + dead-man's switch.

## Comparison matrix

Bold = best score in column (ties broken by listing all winners).

| Criterion | P1 Keystore | P2 Mnemonic | P3 Decoupled | P4 Cloud | P5 ROR | P6 Canonical rpId |
|---|---|---|---|---|---|---|
| Portability | 2 | 2 | 3 | 3 | 2 | **4** |
| Sovereignty | 2 | **4** | **4** | 1 | 1 | 1 |
| UX cost | 2 | 1 | 2 | 2 | 3 | **4** |
| Security (as stated) | 3 | 2 | 2 | 1 | 1 | 1 |
| Security (best variant) | 3 | 2 | 2 | 1 | 1 | **3** (with per-app salts) |
| Migration cost | 1 | 2 | 2 | 2 | 2 | **2** (tied) |
| Engineering effort | 1 | **4** | 2 | 3 | 2 | **4** |
| Long-term viability | 2 | 2 | 2 | 2 | 1 | 2 |

No path scores top across the board. P6 (canonical rpId) wins portability, UX, and engineering — the three criteria that map directly to "the user's first complaint goes away in 6 weeks." Its security and sovereignty failures are real but addressable through scoping and per-app salt domain separation. Every other path either fails to solve the actual problem (P2, P3 same-install only; P5 doesn't do what it claims) or trades a strong existing property for a weaker one (P1 loses no-seed-at-rest; P4 loses sovereignty).

## Recommendation

**Adopt a two-track strategy. Ship Track A now to unblock identity portability. Build Track B over the next two quarters to unblock fund portability without re-centralizing custody.**

### Track A — Canonical Kuira rpId for first-party apps (4–6 weeks)

Adopt **`sigil.kuira.app`** (or whichever custom domain Kuira-org commits to operating with a 99.99% SLA — *not* `*.github.io`, which the Public Suffix List blocks) as the single rpId for first-party Kuira applications: Kuira Wallet, BBoard, Kicks, and the `kuira-starter-android` template.

Required mitigations, non-negotiable:

1. **Per-app salt domain separation.** Modify `SeedDeriver.SEED_SALT` and `AppStateBackup.BACKUP_SALT` from rpId-independent constants to `SHA-256("kuira:seed:v1:" || appId)` and `SHA-256("kuira:backup:v1:" || appId)`, where `appId` is the Android package name. This means: same passkey, same `credSecret`, *different* PRF outputs per app for seed and backup. Each app keeps its own seed and its own backup blob — blast radius stays where it is today.
2. **`SIGIL_SALT` stays shared.** This is the deliberate exception: the DID derivation uses the rpId-independent salt so every first-party app produces the *same* `did:key:z6Mk…` for the user. The DID is the identity surface that's allowed to be shared.
3. **`assetlinks.json` curation discipline.** First-party apps only. Every entry reviewed by two Kuira-org engineers, staged on a pre-prod domain, fingerprint-verified against Play Console output before merge to prod. Cert rotations gated by a runbook. Document the policy.
4. **Public sunset commitment.** This is a band-aid. Architecture-record entry says: *"Track A is a transitional mechanism. Track B (delegated-spend authority) supersedes it. New third-party developers should target Track B once shipped."*
5. **User-visible disclosure at forge.** Onboarding screen says: *"This sigil is shared with all Kuira-built apps."* No silent cross-app credential reuse.
6. **Domain ops.** Multi-person registrar access, monitored auto-renewal, redundant DNS, dead-man's-switch for domain loss (which forces every user to re-forge — accept and document this).

This gives the maintainer's friend their answer: install BBoard, install Kicks, install the starter, all under `sigil.kuira.app`, and the user gets the same DID across all three. Funds are still per-app (correctly, for blast-radius reasons), and the user can move funds between sigils via normal on-chain transfers — or, once Track B ships, via a delegated authorization.

### Track B — Delegated-spend authority (~2 quarters)

Build out roadmap issue #27. Concretely:

1. **`AuthorizationStore` extension.** A new `SpendAuthorization` record type alongside the existing `AuthorizationRecord` for access-key signing. Fields: `granterDid`, `granteeDid`, `scope` (max amount, contract whitelist, expiry block height), `signature` over a canonical payload, `revoked`.
2. **On-chain witness.** Spend authorizations are recorded as a witness on Midnight so contracts can verify scope at execution time. Withdrawals against a granter's UTXOs by a grantee are gated by an on-chain `assert_authorized(granter, grantee, amount)` predicate.
3. **Wallet UI.** "Grant Kicks 50 DUST for the next 24 hours" becomes a one-tap action in Kuira Wallet. Revoke is a second tap.
4. **`KeyAuthorization` reuse.** The existing passkey-assertion-signed authorization payload primitive already exists in `PasskeyManager` + `AuthorizationStore`. Extend it; don't rebuild it.

Track B delivers the actual cross-app fund-portability story without sharing a seed. Backpack's xNFT model collapses dApps into one wallet process; Coinbase Smart Wallet collapses signing into one hosted relying party. Neither model fits Kuira's "sovereign sigil + open SDK" thesis. Delegated authority is the model that fits — the user keeps the master sigil, apps get scoped revocable capabilities, and an ecosystem of 100 third-party Kuira apps doesn't require trusting all 100 with the seed.

### Why not just Track A?

Track A's per-app salt separation means apps don't share funds — which is the security-correct answer but doesn't fully satisfy the user's literal request ("use my current account with funds"). Track B closes that gap by making "use my funds from another app" an explicit, scoped, revocable operation rather than implicit seed sharing. Without Track B, users will keep asking "but why does Kicks have a separate balance from my main Wallet" and the answer "design tradeoff for blast-radius isolation" satisfies engineers but not users.

### Why not just Track B?

Track B is a several-month build. Track A unblocks identity portability in weeks and gives the SDK a credible "ship a Kuira-built app and it inherits the user's sigil" demo for the starter. The starter is currently the most visible piece of the SDK story; it can't be stranded on a separate sigil while Track B cooks.

### Acknowledged tradeoffs

- **Track A creates a Kuira-org operational dependency on `sigil.kuira.app`.** This is real centralization. We mitigate by treating it as transitional and committing publicly to Track B. Users who want full sovereignty can use the mnemonic-export escape hatch (P2 variant) any time.
- **Per-app salt separation means re-forging breaks fund continuity within a single app if its `appId` ever changes.** Document the policy: Kuira `appId`s are stable forever; treat them as part of the public API surface.
- **First-party-only allowlist disappoints third-party devs.** This is intentional and temporary. They get Track B in two quarters, which is *better* than the band-aid — explicit authority delegation is more sovereign than implicit seed sharing.
- **GPM-account captivity (`project_sigil_gpm_account_constraint`) is unchanged.** No path on the table fixes this without rebuilding the credential storage layer. It remains Kicks wishlist #28.

## Migration plan

Existing users have sigils on per-app rpIds today (BBoard and Kicks happen to share `nel349.github.io`; the starter would have minted on `kuiralabs.github.io`). Migration to the canonical rpId is one-way and re-forges the sigil.

**Phase 0 — Decide and announce (week 1).**
- Maintainer approves the recommendation.
- Register `sigil.kuira.app` (or chosen domain). Set up TLS, monitoring, registrar 2FA.
- Publish architecture record + public roadmap note.

**Phase 1 — Plumbing (weeks 2–4).**
- Add `appId` parameter to `SeedDeriver` and `AppStateBackup`. Update `SeedDeriver.SEED_SALT` and `AppStateBackup.BACKUP_SALT` derivations.
- Keep the old constants as `LEGACY_SEED_SALT` / `LEGACY_BACKUP_SALT` for migration paths.
- Add `KuiraSigilRpId` constant to a shared SDK module. Replace per-app `PasskeyConfig.rpId` wiring in first-party apps.
- Ship `assetlinks.json` to `https://sigil.kuira.app/.well-known/` with all first-party app fingerprints.

**Phase 2 — In-app migration (weeks 5–6).**
- On first launch under the new rpId, detect a sigil on the legacy rpId via `SigilStateStore`.
- Display a migration screen: *"Your Kuira sigil is moving to a unified identity across all Kuira apps. You'll keep your current funds — they'll be transferred to your new sigil."*
- Flow: forge new sigil under canonical rpId → derive new wallet seed under per-app salt → send all funds from legacy wallet to new wallet via standard on-chain transfer → mark legacy sigil deprecated in `SigilStateStore` (model after `migrateLegacyP256DidIfPresent`).
- For apps with on-chain identity references (BBoard posts, Kicks games): publish a one-time **DID rotation attestation** signed by the legacy DID asserting "my new DID is X." Contracts that look up history can chain through the attestation.
- Funds are at risk only during the on-chain transfer window (single confirmation). Use the existing tip-aware Dust sync to confirm before deleting the legacy seed.

**Phase 3 — Starter and SDK polish (weeks 7–8).**
- Update `kuira-starter-android` to bake in `sigil.kuira.app` as the default rpId.
- Update SDK docs: how to register a first-party app, what the per-app salt means, the public commitment about delegated-authority (Track B) replacing the allowlist.
- Add diagnostics in `SigilPanelViewModel` to surface "this sigil is on canonical rpId, shared across N installed Kuira apps."

**Phase 4 — Track B kickoff (week 8 onward).**
- Spec out `SpendAuthorization`. Coordinate with Midnight on the on-chain witness primitive.
- Ship in stages: granter-side UI → grantee-side consumption → on-chain enforcement → revocation flow.

**Rollback plan.** If `sigil.kuira.app` is compromised mid-rollout, users on Phase 2 retain their legacy sigil until they explicitly migrate. We can pause the migration screen via remote config and let users keep operating on per-app rpIds while the domain is recovered or replaced. No funds are lost from a domain compromise — only future forges fail until the assetlinks.json is verified again.

## Open questions

1. **`sigil.kuira.app` vs another domain.** Does Kuira-org want the canonical rpId to live under `kuira.app`, a new dedicated domain, or something else? This is a brand/ops decision the maintainer should make, not a technical one.
2. **Should the per-app salt include the app's signing cert SHA-256 rather than the package name?** Package names can be squatted on F-Droid or alternative stores; signing cert fingerprints can't. Tradeoff: signing-cert-based salts break if a partner ever rotates their key (which they may need to do for security reasons), forcing a re-forge for that app only. Needs a prototype to feel the tradeoff.
3. **DID rotation attestation format.** No existing W3C standard fits cleanly. Options: a custom verifiable credential signed by the legacy DID; an `did:key:z6Mk…` → `did:key:z6Mk…` link in a Midnight smart contract; a SPRIND-style DID-rotation registry. Worth a one-week design spike before Phase 2.
4. **iOS parity.** The Track A model needs an `apple-app-site-association` equivalent for iOS once that platform ships. Apple's WebAuthn implementation uses ASAA the same way Android uses Digital Asset Links — verification effort should be modest, but worth checking that ROR's `webcredentials` entitlement composes cleanly with Apple's WebAuthn rpId resolution.
5. **Track B on-chain witness primitive.** Does Midnight contract runtime already expose `assert_authorized(...)` semantics, or does this need to be a new built-in? Coordinate with Midnight team early.
6. **User research.** Does the "shared sigil across all Kuira apps" mental model actually land with non-technical users, or does it confuse them? Worth a 5-user diary study before Phase 3.
7. **Domain-loss tabletop exercise.** Run a Severity-1 simulation: what exactly happens to a user mid-transaction if `sigil.kuira.app` goes dark? Document the runbook before Phase 1 ships.
8. **Mnemonic-export escape hatch (P2 variant).** Should this ship alongside Track A or be deferred? Recommend ship-alongside to give power users a sovereignty answer when they ask about Kuira-org dependency on the canonical rpId.

## References

**WebAuthn / FIDO specs and discussions.**
- W3C WebAuthn Level 3 — <https://www.w3.org/TR/webauthn-3/>
- PR #2040, Related Origin Requests, merged 2024-07-17 — <https://github.com/w3c/webauthn/pull/2040>
- Issue #1827, "same credential across domains" (open, no proposal) — <https://github.com/w3c/webauthn/issues/1827>
- Issue #1372, "cross-domain credential use" (closed → ROR) — <https://github.com/w3c/webauthn/issues/1372>
- Issue #1665, synced credentials — <https://github.com/w3c/WebAuthn/issues/1665>
- Issue #865, portability of private keys — <https://github.com/w3c/webauthn/issues/865>
- ROR explainer — <https://github.com/w3c/webauthn/blob/main/explainers/related-origin-requests.md>
- passkeys.dev — Related Origins — <https://passkeys.dev/docs/advanced/related-origins/>
- web.dev — RP ID deep dive — <https://web.dev/articles/webauthn-rp-id>
- web.dev — ROR guide — <https://web.dev/articles/webauthn-related-origin-requests>
- Corbado — RP ID & Public Suffix List — <https://www.corbado.com/blog/webauthn-relying-party-id-rpid-passkeys>
- MDN — WebAuthn extensions (PRF) — <https://developer.mozilla.org/en-US/docs/Web/API/Web_Authentication_API/WebAuthn_extensions>
- Levi Schuck — PRF demo — <https://levischuck.com/blog/2023-02-prf-webauthn>
- Corbado — CXP / CXF — <https://www.corbado.com/blog/credential-exchange-protocol-cxp-credential-exchange-format-cxf>

**Android platform.**
- Android Credential Manager — Credential Provider — <https://developer.android.com/identity/sign-in/credential-provider>
- Overview of digital credentials — <https://developer.android.com/identity/digital-credentials>
- Block Store — <https://developer.android.com/identity/block-store>
- Android Keystore system — <https://developer.android.com/privacy-and-security/keystore>
- Announcing Android support of digital credentials (Apr 2025) — <https://android-developers.googleblog.com/2025/04/announcing-android-support-of-digital-credentials.html>
- Stytch — Android Keystore pitfalls — <https://stytch.com/blog/android-keystore-pitfalls-and-best-practices/>
- CommonsWare — `sharedUserId` deprecation — <https://commonsware.com/blog/2019/06/06/random-musings-q-beta-4.html>

**Apple platform.**
- Sharing access to keychain items — <https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps>
- `keychain-access-groups` entitlement — <https://developer.apple.com/documentation/bundleresources/entitlements/keychain-access-groups>
- Common pitfalls when using Keychain Sharing — <https://www.rambo.codes/posts/2020-01-16-common-pitfalls-when-using-keychain-sharing-on-ios>
- Supporting passkeys — <https://developer.apple.com/documentation/authenticationservices/supporting-passkeys>
- ASCredentialProviderViewController — <https://developer.apple.com/documentation/authenticationservices/ascredentialproviderviewcontroller>
- WWDC25 — What's new in passkeys — <https://developer.apple.com/videos/play/wwdc2025/279/>
- iOS 26 passkey import/export — <https://9to5mac.com/2025/06/13/ios-26-passkeys-password-transfer/>
- About the security of passkeys (iCloud Keychain) — <https://support.apple.com/en-us/102195>

**Solana Mobile Wallet Adapter and Seed Vault.**
- MWA 2.0 spec — <https://solana-mobile.github.io/mobile-wallet-adapter/spec/spec.html>
- MWA 1.0 spec — <https://solana-mobile.github.io/mobile-wallet-adapter/spec/spec1.0.html>
- MWA Android integration guide — <https://github.com/solana-mobile/mobile-wallet-adapter/blob/main/android/docs/integration_guide.md>
- Mobile dApp architecture deep dive — <https://docs.solanamobile.com/developers/mobile-wallet-adapter-deep-dive>
- Seed Vault SDK integration guide — <https://github.com/solana-mobile/seed-vault-sdk/blob/main/docs/integration_guide.md>
- Seed Vault docs — <https://docs.solanamobile.com/developers/seed-vault>

**Phantom, MetaMask, Backpack, Coinbase.**
- Phantom — deep dive on Log in with email (Juicebox) — <https://phantom.com/learn/blog/deep-dive-log-in-to-phantom-with-email>
- Phantom deeplinks docs — <https://docs.phantom.com/phantom-deeplinks/deeplinks-ios-and-android>
- Phantom Help — view recovery phrase — <https://help.phantom.com/hc/en-us/articles/25334064171795-How-to-view-your-recovery-phrase-or-private-key-in-Phantom>
- MetaMask vault storage teardown — <https://www.wispwisp.com/index.php/2020/12/25/how-metamask-stores-your-wallet-secret/>
- MetaMask Embedded Wallets SDK — Android — <https://docs.metamask.io/embedded-wallets/sdk/android/>
- MetaMask SDK overview — <https://docs.metamask.io/sdk/>
- MetaMask iCloud backup advisory — <https://www.theblock.co/post/142304/metamask-advises-users-to-disable-automatic-icloud-backups-of-its-wallet-data-to-prevent-hacks>
- Para — Why passkey-only wallets will fail — <https://blog.getpara.com/passkey-wallets/>
- Backpack — best EVM wallets — <https://learn.backpack.exchange/articles/best-evm-wallets>
- xNFTs explainer (CoinMarketCap) — <https://coinmarketcap.com/academy/article/what-are-xnfts-executable-nfts>
- xNFTs deep dive (Alchemy) — <https://www.alchemy.com/overviews/xnft>
- QuickNode — Backpack Wallet builder's guide — <https://www.quicknode.com/builders-guide/tools/backpack-wallet-by-coral>
- Coinbase Smart Wallet — <https://github.com/coinbase/smart-wallet/blob/main/README.md>
- Coinbase Wallet SDK overview — <https://www.coinbase.com/developer-platform/products/wallet-sdk>
- Coinbase Smart Wallet passkeys — <https://help.coinbase.com/en/wallet/getting-started/smart-wallet-passkeys>

**Internal Kuira references (this repo).**
- `core/identity/.../passkey/PasskeyManager.kt`
- `core/identity/.../sigil/Ed25519PrfSigilProvider.kt`
- `core/identity/.../sigil/SigilStateStore.kt`
- `core/identity/.../backup/SeedDeriver.kt`
- `core/identity/.../backup/AppStateBackup.kt`
- `core/identity/.../backup/PrfKeyDeriver.kt`
- `core/identity/.../backup/BlockStoreBackupStorage.kt`
- `core/identity/.../auth/AuthorizationStore.kt`
- `core/auth/.../SeedVault.kt`
- `sdk/wallet-seed/.../WalletSeedSource.kt`
- `sdk/wallet-seed/.../SigilSession.kt`
- Memory: `project_sigil_gpm_account_constraint`, `project_sigil_concept`, `project_passkey_investigation`, `project_sdk_platform_roadmap`, `reference_sigil_recovery_flow`
