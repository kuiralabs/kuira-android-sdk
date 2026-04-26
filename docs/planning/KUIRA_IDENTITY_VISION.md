# Kuira — The Sigil for Midnight

**Status:** Research / Direction-setting
**Last updated:** 2026-04-23
**Triggered by:** Midnight CTO's "shouldn't need a wallet" working group
+ rvcas midnightOS Passkeys (`passkeys.rvcas.dev`)

---

## What is Kuira?

Kuira is not a wallet.

A wallet holds things. Kuira **proves things, authorizes things, and
protects things.** It is a new category of product for the Midnight
ecosystem — one that doesn't have a name yet.

We call it a **Sigil.**

The word comes from the Latin *sigillum* — a personal seal pressed into
wax to authenticate documents. A sigil didn't just say "this is me."
It said: *"I am this person, I authorize this content, and I vouch for
its authenticity."* Three declarations in one stamp.

That is exactly what Kuira does.

## Tagline

**"Where night protects the day."**

The night (Kuira's TEE-backed security, encrypted state, biometric
gates) protects the day (your active use of Midnight apps, your
transactions, your identity across the ecosystem).

---

## The Sigil — three facets

A Kuira Sigil is not just an identity. It is the complete system of
proof, authority, and protection that a user carries across every
Midnight interaction.

```
Your Sigil
├── Identity    — "I am"        — the root (passkey + biometric)
├── Authority   — "I authorize" — what you've delegated to each app
└── State       — "I protect"   — your encrypted private data
```

### Facet 1 — Identity ("I am")

The root of the sigil. Proves you are you.

- **Passkey (P-256)** as the root credential — stored in device TEE,
  synced via Google Password Manager for cross-device recovery
- **Access keys (secp256k1)** delegated per-dApp — signed by the root
  passkey via keyAuthorization
- **DIDs** (Decentralized Identifiers) — interop with rvcas midnightOS
  Passkeys ecosystem
- **Biometric gate** — every delegation requires biometric confirmation

The user experience: one tap + biometric → authenticated everywhere.
No seed phrase, no "connect wallet" modal, no extension.

### Facet 2 — Authority ("I authorize")

The delegation layer. Controls what passes through and what doesn't.

Three permission tiers for dApp interactions:

| Tier | Actions | Approval |
|------|---------|----------|
| Silent | Read state, query balances, view history | Access key signs directly |
| Notify | Small spends, agent actions within policy | Access key signs, notification shown |
| Approve | Large spends, deploy contracts, key delegation | Push notification → biometric on mobile |

Per-app policies: *"Fog Arena can spend up to 5 NIGHT/day silently."*
The user configures thresholds in Settings.

An app "bears your sigil" — it operates under your authority, within
the bounds you set. Revoke the sigil and the app loses all access
instantly.

### Facet 3 — State ("I protect")

The encrypted storage layer. What the sigil seals.

- **Per-app namespaces** — each dApp gets its own AES-256-GCM encrypted
  state (already built: `KeyStorePrivateStateProvider`)
- **Cloud sync** — encrypted blobs stored in Google Block Store, GDrive,
  or OneDrive. Auto-restores on new device.
- **State browser** — Kuira shows private state across ALL connected
  dApps (game inventories, DEX positions, agent policies)
- **ZK witnesses** — private inputs for ZK proofs, stored locally,
  never leave the device unencrypted
- **Assets** — balances, UTXOs, dust tokens, transaction history.
  The "wallet" view is one window into the state facet.

---

## The vocabulary

Establishing consistent language across the product, SDK, protocol,
and ecosystem.

| Old term | Sigil term | Where it appears |
|----------|-----------|-----------------|
| Connect wallet | **Present your Sigil** | dApp auth button |
| Create wallet | **Forge your Sigil** | Onboarding |
| Disconnect | **Revoke Sigil** | Connected apps |
| Approve session | **Delegate Sigil** | Connector approval |
| Restore wallet | **Restore your Sigil** | Recovery flow |
| Connected apps | **Apps bearing your Sigil** | State browser |
| Wallet | **Sigil** | Product category |

### Connector protocol (JSON-RPC)

```
kuira_requestSigil     → dApp requests authentication
kuira_presentSigil     → user presents sigil (biometric + access key)
kuira_delegateSigil    → scoped access key issued to dApp
kuira_revokeSigil      → user revokes dApp access
kuira_querySigilState  → dApp reads its private state namespace
kuira_signWithSigil    → dApp requests transaction signature
```

### SDK (developer-facing)

The SDK wraps the protocol with ergonomic names. The mapping:

| SDK method | Protocol method | What happens |
|-----------|----------------|-------------|
| `Kuira.requestSigil()` | `kuira_requestSigil` | dApp initiates auth |
| `sigil.authenticate()` | `kuira_presentSigil` | Biometric + access key |
| `sigil.sign(tx)` | `kuira_signWithSigil` | Access key signs |
| `sigil.revoke()` | `kuira_revokeSigil` | User revokes access |

```kotlin
// What developers write in their Midnight dApp:
val sigil = Kuira.requestSigil(scope = SigilScope.READ_STATE)
sigil.authenticate()   // → kuira_presentSigil under the hood
sigil.sign(transaction) // → kuira_signWithSigil under the hood
```

### One-sentence definition

For the Play Store, README, docs, talks — the sentence people repeat:

> **Sigil** — a private digital identity that authenticates you across
> all Midnight apps with one tap. Not a wallet. Not a password. A sigil.

---

## App navigation architecture

Kuira the app manages one sigil. One sigil = one root (seed today,
passkey in the future). Multiple accounts are derived from the same
root via HD derivation paths — one per network or purpose:

```
Your Sigil (one root)
├── Account 0 — m/44'/3311'/0'  (PREPROD)
├── Account 1 — m/44'/3311'/1'  (MAINNET)
└── Account N — m/44'/3311'/N'  (future networks)
```

The navigation reflects the three facets:

```
Bottom nav:  [My Sigil]  [Assets]  [Activity]  [Settings]
                  │              │          │
                  │              │          └── Tx history, agent audit log
                  │              └── Balance, Send, Receive, Dust
                  └── Connected apps, state browser, delegation policies
```

- **My Sigil** — the sigil's primary screen. Shows connected apps,
  per-app state, pending approvals, delegation policies, recent
  delegations. This is the HOME screen — the sigil dashboard.
- **Assets** — the financial window into the sigil's state. Balances,
  send, receive, dust. Assets are state (facet 3), surfaced here as
  a dedicated tab because financial operations deserve their own UX.
- **Activity** — transaction history, agent actions, approval log.
  Audit trail for everything the sigil authorized.
- **Settings** — network, security, proof server, about.

**Onboarding — "Forge your Sigil":**
- **v1 (current):** seed phrase generation → biometric enrollment →
  TEE-backed key storage. The seed IS the sigil root.
- **Future (passkey-native):** passkey generation (P-256 in TEE) →
  biometric enrollment → no seed phrase needed. The passkey IS the
  sigil root. Seed phrase becomes a power-user export option.

---

## Interaction flows

### Web dApp authentication (QR code)

```
Browser dApp: "Sign in with Midnight" → shows QR code
User: scans QR with Kuira → biometric → sigil presented
dApp: receives scoped access key → operates under sigil authority
Kuira: shows app in "My Sigil" as bearing user's sigil
```

### Mobile dApp authentication (CredentialManager)

```
Mobile dApp: calls Android CredentialManager
System: routes to Kuira (registered CredentialProvider)
Kuira: biometric prompt → generates per-dApp access key
dApp: receives access key → operates under sigil authority
No QR needed — same device.
```

### Agent authentication (MCP Bridge)

```
Claude Desktop: connects via MCP Bridge (WebSocket)
Kuira: shows pairing PIN → user confirms on mobile
Agent: receives scoped access key + policy
Agent: operates within policy bounds (sigil authority)
Kuira: logs all agent actions in Activity audit trail
```

### Recovery (new device)

```
New phone: passkey auto-available via Google Password Manager
User: installs Kuira (or any Midnight app triggers CredentialManager)
Kuira: passkey re-authenticates → pulls encrypted state from cloud
Result: sigil restored — all apps, state, policies, assets recovered
Zero words. Zero manual steps.
24-word phrase: power-user escape hatch, not primary recovery path.
```

**RESOLVED:** Access key recovery uses PRF (Pseudo-Random Function).
PRF is a WebAuthn extension that derives a deterministic secret from
the passkey during authentication. Google Password Manager supports
PRF by default on Android (100% success rate, Q1 2026 testing).

Recovery flow: passkey syncs to new device → user authenticates with
biometric → PRF produces same deterministic secret → secret decrypts
cloud backup (Block Store / GDrive) → secp256k1 access keys + state
restored. Zero words. Zero passwords. Just biometric.

24-word seed phrase remains as a power-user escape hatch only.

See `docs/planning/IDENTITY_INVESTIGATION.md` for full details.

---

## Why "Sigil" will stick

Three properties shared with every category-defining term that took
hold ("browser," "app," "tweet"):

1. **Short** — one word, two syllables, works in every language
2. **Visual** — people picture a seal, a stamp, a mark of authority
3. **Verb-able** — "Present your sigil." "Forge a sigil." "Revoke the
   sigil." Natural in sentences.

### Adoption strategy

1. **SDK naming** — developers learn the word by using it in code.
   Every integration = one more person saying "sigil."
2. **Connector protocol** — the JSON-RPC methods use `*Sigil` naming.
   Every protocol message reinforces the term.
3. **UI moments** — used at identity-defining moments only (forge,
   present, revoke). Not plastered on every screen.
4. **Ecosystem alignment** — propose to Midnight CTO working group.
   Get rvcas's midnightOS Passkeys to support sigil terminology.
5. **The narrative** — one blog post: *"Why Kuira isn't a wallet —
   and why Midnight doesn't need one."* Positions Kuira as creating
   a category, not competing in one.

---

## What exists today vs what's needed

| Capability | Status | What we have |
|-----------|--------|-------------|
| TEE key management | ✅ Built | SeedVault + WalletKeyManager + StrongBox |
| Biometric gate | ✅ Built | BiometricGate + per-use auth |
| dApp Connector | ✅ Built | 4 transport layers, 17 ConnectedAPI methods |
| Private state encryption | ✅ Built | KeyStorePrivateStateProvider (AES-256-GCM) |
| Approval UI | ✅ Built | ConnectionApprovalActivity + ApprovalSheet |
| Assets UI (wallet view) | ✅ Built | Balance, Send, Dust screens (8B.3 in progress) |
| Passkey generation | ✅ Decided | CredentialManager client (Tier 1, API 28+) / CredentialProvider (Tier 2, API 34+). See `IDENTITY_INVESTIGATION.md` |
| DID generation | ✅ Decided | `did:key` from root passkey. One DID per user. Standard W3C format. |
| Access key delegation | ✅ Decided | secp256k1 access key, self-verifiable keyAuthorization (P-256 root signs in TEE). Advocate P-256 to Midnight. |
| Recovery (cloud) | ✅ Decided | PRF-encrypted cloud backup (Block Store / GDrive). Passkey syncs → biometric → PRF decrypts. Zero words. |
| My Sigil screen | ⬜ Design | Sigil dashboard — connected apps + state browser |
| Per-app policies | ⬜ Phase 7 | Policy Engine (Agent Runtime pillar 3) |
| MCP Bridge | ⬜ Phase 7 | Agent Runtime pillar 1 |
| Connector protocol rename | ⬜ Planned | `kuira_*Sigil` JSON-RPC methods |
| SDK sigil API | ⬜ Planned | `Kuira.requestSigil()` developer surface |

## Phase mapping

| Phase | Delivers |
|-------|---------|
| 8B (current) | Assets screens, reactive network, Settings, 4-tab bottom nav |
| SDK | Midnight Android SDK — single AAR, embedded wallet, passkey identity, PRF recovery. Midnight Kicks is first consumer. |
| 8C | Cloud backup (PRF-encrypted Block Store / GDrive), SDK GA |
| 7 v1.1 | MCP Bridge, Agent Mode, Policy Engine — the authority facet |
| 7+ | CredentialProvider (Kuira as system-level passkey provider, API 34+) |
| 9+ | My Sigil screen, per-app policies UI, sigil dashboard |

---

## Key references

- rvcas fake-app (consumer pattern): `git@github.com:rvcas/fake-app.git`
- midnightOS Passkeys (identity provider): `https://passkeys.rvcas.dev`
- midnightOS Passkeys embed endpoint: `https://passkeys.rvcas.dev/embed`
- webauthx (P-256 WebAuthn wrapper): `npm:webauthx@0.1.0` (by wevm, wraps `ox/webauthn`)
- Android CredentialManager API: `https://developer.android.com/identity/sign-in/credential-manager`
- Midnight CTO working group: rethinking onboarding, "no wallet needed"
- Kuira Connector: `core:connector/` (4 transports, approval UI)
- PrivateStateProvider: `core:compact-engine/.../state/`
- Agent Store Vision: `docs/planning/AGENT_STORE_VISION.md`
- Kuira Vision v1: `docs/planning/KUIRA_VISION_V1.md`
- Identity investigation: `docs/planning/IDENTITY_INVESTIGATION.md`
- Midnight Kicks plan: `docs/planning/MIDNIGHT_KICKS_PLAN.md`

---

## Historical note

The term "sigil" was chosen on 2026-04-23 during the identity
investigation triggered by the Midnight CTO's working group. The
deliberate departure from "wallet" reflects a product truth: Kuira
manages identity, authority, and encrypted state — not just tokens.
The wallet features (balance, send, receive) remain as the "Assets"
view within the sigil, but they are one facet of a larger system.
