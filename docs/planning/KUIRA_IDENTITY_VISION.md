# Kuira Identity Vision — Beyond the Wallet

**Status:** Research / Direction-setting
**Last updated:** 2026-04-23
**Triggered by:** Midnight CTO's "shouldn't need a wallet" working group
+ rvcas midnightOS Passkeys (`passkeys.rvcas.dev`)

---

## Thesis

Kuira is not a wallet. It's a **private state management system** for
the Midnight ecosystem. It manages identity, encrypted state, and
delegation across all Midnight apps — on mobile, web, and for AI agents.

The wallet UX (balances, send, receive) is one VIEW into the system.
The core product is the **Connector** (authentication + delegation)
and the **PrivateStateProvider** (encrypted state for all apps).

## Tagline

"Where night protects the day."

This was never about a wallet. It's about protection — of identity,
of state, of privacy. The night (Kuira's TEE-backed security) protects
the day (your active use of Midnight apps).

---

## The three layers

### Layer 1 — Identity (authentication)

Kuira generates and manages Midnight identities:
- **Passkey (P-256)** as the root credential — stored in device TEE,
  synced via Google Password Manager for cross-device recovery
- **Access keys (secp256k1)** delegated per-dApp — signed by the root
  passkey via keyAuthorization
- **DIDs** (Decentralized Identifiers) — interop with rvcas midnightOS
  Passkeys ecosystem
- **Biometric gate** — every delegation requires biometric confirmation

The user experience: one tap + biometric → authenticated everywhere.
No seed phrase, no "connect wallet" modal, no extension.

### Layer 2 — Private State (encrypted storage)

Kuira encrypts and manages private state for every Midnight app:
- **Per-app namespaces** — each dApp gets its own AES-256-GCM encrypted
  state (already built: `KeyStorePrivateStateProvider`)
- **Cloud sync** — encrypted blobs stored in Google Block Store, GDrive,
  or OneDrive. Auto-restores on new device.
- **State browser** — Kuira mobile shows private state across ALL
  connected dApps (game inventories, DEX positions, agent policies)
- **ZK witnesses** — private inputs for ZK proofs, stored locally,
  never leave the device unencrypted

### Layer 3 — Delegation (controlled access)

Three permission tiers for dApp interactions:

| Tier | Actions | Approval |
|------|---------|----------|
| Silent | Read state, query balances, view history | Access key signs directly |
| Notify | Small spends, agent actions within policy | Access key signs, notification shown |
| Approve | Large spends, deploy contracts, key delegation | Push notification → biometric on mobile |

Per-app policies: "Fog Arena can spend up to 5 NIGHT/day silently."
The user configures thresholds in Settings.

---

## Interaction flows

### Web dApp authentication (QR code)

```
Browser dApp: "Sign in with Midnight" → shows QR code
User: scans QR with Kuira mobile → biometric → access key issued
dApp: receives access key via Connector → operates autonomously
Kuira: shows connected app + state in the state browser
```

### Mobile dApp authentication (CredentialManager)

```
Mobile dApp: calls Android CredentialManager
System: routes to Kuira provider → biometric prompt
Kuira: generates access key → returns to dApp
dApp: operates with access key
No QR needed — same device.
```

### Agent authentication (MCP Bridge)

```
Claude Desktop: connects via MCP Bridge (WebSocket)
Kuira: shows pairing PIN → user confirms on mobile
Agent: gets scoped access key + policy (e.g., "read only" or "5 NIGHT/day")
Agent: operates within policy bounds
Kuira: logs all agent actions in audit trail
```

### Recovery (new device)

```
New phone: passkey auto-available via Google Password Manager
User: installs Kuira (or any Midnight app triggers CredentialManager)
Kuira: passkey re-authenticates → pulls encrypted state from cloud
Result: all dApp connections, private state, policies restored
Zero words. Zero manual steps.
24-word phrase: power-user escape hatch, not primary recovery path.
```

---

## What exists today vs what's needed

| Capability | Status | What we have |
|-----------|--------|-------------|
| TEE key management | ✅ Built | SeedVault + WalletKeyManager + StrongBox |
| Biometric gate | ✅ Built | BiometricGate + per-use auth |
| dApp Connector | ✅ Built | 4 transport layers, 17 ConnectedAPI methods |
| Private state encryption | ✅ Built | KeyStorePrivateStateProvider (AES-256-GCM) |
| Approval UI | ✅ Built | ConnectionApprovalActivity + ApprovalSheet |
| Balance/Send/Dust UI | ✅ Built | Production screens (8B.3 in progress) |
| Passkey generation | ⬜ Research | Needs CredentialManager provider registration |
| DID generation | ⬜ Research | Needs rvcas interop investigation |
| Access key delegation | ⬜ Research | keyAuthorization model from rvcas |
| Cloud state sync | ⬜ Planned | Block Store (8C) or GDrive encrypted blob |
| State browser UI | ⬜ Design | New screen: "My Midnight" with per-app state |
| Per-app policies | ⬜ Phase 7 | Policy Engine (Agent Runtime pillar 3) |
| MCP Bridge | ⬜ Phase 7 | Agent Runtime pillar 1 |

## Phase mapping

| Phase | Delivers |
|-------|---------|
| 8B (current) | Core wallet screens, reactive network architecture, Settings |
| 8C | Cloud backup (Block Store), SDK GA |
| 7 v1.1 | MCP Bridge, Agent Mode, Policy Engine — the delegation layer |
| 7+ | CredentialManager provider, passkey generation, DID interop |
| 9+ | State browser, per-app policies UI, "My Midnight" dashboard |

---

## Key references

- rvcas fake-app: `git@github.com:rvcas/fake-app.git`
- midnightOS Passkeys: `passkeys.rvcas.dev`
- webauthx: `npm:webauthx@0.1.0` (by wevm, wraps `ox/webauthn`)
- Midnight CTO working group: open for rethinking onboarding
- Kuira Connector: `core:connector/` (4 transports, approval UI)
- PrivateStateProvider: `core:compact-engine/.../state/`
- Agent Store Vision: `docs/planning/AGENT_STORE_VISION.md`
- Kuira Vision v1: `docs/planning/KUIRA_VISION_V1.md`
