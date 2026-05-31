# Sigil V3 — Track A Plan

**Goal:** ship the V3 master-seed-as-data architecture to feature parity
with V2 *plus* cross-app enrollment, *plus* the design hooks that let
Midnight Passport land cleanly as a future plug-in. After Track A,
existing first-party Kuira apps continue to work; the starter (and any
future Kuira-built or third-party app) can join an existing user's
sigil via an explicit biometric-on-both-ends enrollment; and Passport's
account-abstraction + universal-DID + verifiable-credential layers have
a reserved place in the architecture to plug into when their spec is
public.

Background reading:
- V3 architecture and full justification:
  [`docs/research/SIGIL_PORTABILITY_INVESTIGATION_V3.md`](research/SIGIL_PORTABILITY_INVESTIGATION_V3.md)
- Viability addendum (confidence scoring, 14 plan amendments, device-
  testing gates): [`docs/SIGIL_V3_TRACK_A_VIABILITY.md`](SIGIL_V3_TRACK_A_VIABILITY.md)

**Scope corrections vs. earlier revisions of this plan.**
The previous revision included a "V2 → V3 migration" work surface. That
surface has been removed: we are on alpha + testnet, no real funds are
at stake, and existing testnet sigils can be re-forged. Migration
re-enters the conversation only when mainnet ships, as a separate plan.
The previous revision also positioned Passport as a future-update
concern. Passport integration is now a Track A *design constraint*:
the architecture commits to specific extension points so that
post-Passport integration is wiring, not refactoring.

---

## What "ready for Track A ship" means — the acceptance gate

A user on a V3-enabled Kuira-first-party app, with a forged sigil and
on-chain testnet funds, can:

1. **Forge a fresh sigil** with two biometric prompts (one for the
   passkey `create()` ceremony, one for the immediate PRF harvest
   `get()` ceremony) — and afterwards have both Tier 1 (per-app local)
   and Tier 2 (Block Store) recovery copies in place.
2. **Install a second Kuira-built app on the same device and enroll it
   to share the sigil.** Three biometric prompts total — one on the
   existing app to release the seed, two on the new app (`create()` +
   PRF harvest) to seal it under the new app's unlock keys. After
   consent, both apps see the same DID, the same wallet, the same
   balance. Revoking the new app from the existing app's "Connected
   Apps" screen removes its local seed copy.
3. **Install a third-party Kuira-compatible app and enroll it via a
   TOFU consent path.** Same enrollment UX, plus a one-time first-
   contact screen showing a 6-word PGP biometric fingerprint of the
   new app's signing cert. Repeat enrollments skip the first-contact
   screen.
4. **Uninstall and reinstall an enrolled app.** The same-app same-device
   recovery path (Tier 2 / Block Store) restores the sigil with a
   single biometric prompt, no re-onboarding.
5. **Run an internal beta build with a "V3 disabled" flag and
   transparently fall back to "no V3" behaviour** while V3 hardens.
   The flag is the rollback button.

If those five round-trip cleanly across a 10–15 person internal cohort
spanning the BBoard + Kicks + starter acceptance set, Track A ships.

Track A explicitly does NOT close: cross-device recovery on a different
Google account, lost-passkey recovery via PIN, fully delegated signing
for agents, verifiable-credential storage, selective-disclosure
presentation. Those are Track B, Track C (Passport integration), and
Track C+1 (VC store + presentation).

---

## Status carried in from V2

| Property | Source | What it lets Track A skip |
|---|---|---|
| Passkey + PRF on Android works reliably | Shipped V2 (BBoard, Kicks) | No new platform-API research; the assertion path is known-good. Multi-salt PRF is supported per W3C WebAuthn L3 §10.1.4 + Yubico; two independent outputs per ceremony are available with a capability probe at enroll time. |
| Block Store backup of per-app encrypted state | Shipped V2 (BBoard, Kicks, starter) | Tier 2 storage is a refactor, not a new platform integration. Budget arithmetic: V3 fits with 8 of 16 entries used and ~60 KB headroom; the 4 KB-per-entry limit is comfortable for the seed envelope + multi-sigil metadata forever. (Block Store cannot fit Passport VCs; storage-tier abstraction below makes the future VC tier a drop-in.) |
| `SigilSession.forge` + `signIn` + the `SigilStatusPanel` UI surface | Shipped V2 | The user-facing biometric ceremony shell is already proven; Track A reskins the inside. |
| Android Keystore wrapping for local seed material | Shipped V2 | Tier 1 storage inherits the existing hardware-backed wrapping. |
| Existing PRF assertion API in `core:identity` | Shipped V2 | The mechanics are well-understood; Track A's changes are at the seed-handling and envelope layers, not at the passkey layer. |

---

## Work surfaces

Decomposed by concern, not by file. Each surface has its own acceptance
criteria; surfaces sequence loosely but mostly land in parallel.

### Surface 1 — Master seed lifecycle + Passport-extensible envelope

The primitive that V1 and V2 didn't have: a 32-byte master seed that
exists as data, is forged once, persists immutably, and is encrypted
under as many independent unlock keys as the user authorizes.

The envelope that carries the encrypted seed is **versioned** from day
one, so the same on-disk layout absorbs Passport's verifiable-
credential reference and account-abstraction delegation pointer when
they land — without breaking v1 envelopes that have already been
written.

Signing through the seed is **plug-in**: today there is a wallet-spend
signer and a DID-proof signer, both deriving their keys from the master
seed via HKDF under a documented, frozen namespace. Future signers (VC
presentation, AA intent) take their reserved namespace slots when
Passport ships.

**Acceptance:**
- A forged sigil produces a master seed that survives uninstall +
  restore, cross-process restart, and unlock-key rotation.
- The seed is never on disk in plaintext.
- Forge of a new sigil produces statistically random seeds (smoke test
  for entropy quality).
- Sign-in re-derives the unlock key from the passkey assertion and
  unwraps the persisted seed; no PRF derivation chain assumes
  re-derivability of the seed itself.
- The envelope payload slot is parsed via a versioned, length-prefixed
  codec. v1 = bare 32-byte seed (current behaviour, no on-disk change).
  The codec contract is documented and the v2 (extensible) layout is
  reserved but not implemented in Track A.
- A signer interface exists with wallet-spend and DID-proof concrete
  impls. Future signers (VC presentation, AA delegation) derive keys
  via HKDF from the master seed under a documented `kuira:sign:*`
  info-label namespace. The namespace is frozen as a public contract
  at ship time, with a pinned test vector.
- The forge flow incurs exactly two biometric prompts (one for the
  passkey `create()` ceremony, one for the immediate PRF harvest
  `get()` ceremony). This is unavoidable until platforms ship
  PRF-eval-at-`create()`-time; the UX absorbs this honestly.

### Surface 2 — Tiered storage (Tier 1 + Tier 2) + storage-tier abstraction

Two storage tiers, each holding the same envelope encrypted under a
**different PRF output from the same single-ceremony multi-salt
assertion**, so a tier compromise does not unlock the other tier and
the user does not pay a second biometric prompt for tier separation.

**Tier 1:** per-app local file, biometric-gated unwrap.
**Tier 2:** Block Store backup of the same envelope under a different
HKDF leg of a different PRF output; carries cross-install recovery for
the *same* app on the *same* device.

Storage tiers are routed through an interface so that future tiers —
Tier 1.5 (per-app encrypted SQLite for VCs), Tier 3 (opaque cloud
bucket for Track B's PIN recovery), Tier 4 (Drive `appDataFolder` for
large credential blobs) — plug in without re-engineering Surface 2.
The `tier_tag` AAD values are an open enum (`L1`, `BS`, `CL` shipped;
additional values reserved); changing the set of tiers does not break
envelope compatibility.

**Acceptance:**
- A fresh install of the same app on the same device + Google account
  restores the sigil from Tier 2 with a single biometric prompt, no
  re-prompting forge.
- Tier 1 contents are unreadable without a successful PRF assertion;
  Tier 2 contents are unreadable without a successful PRF assertion
  binding the Tier-2 leg.
- Compromise of a Tier 1 ciphertext does not compromise Tier 2 (key
  separation is verified by inspection of the envelope codec + the
  two independent PRF outputs from the multi-salt ceremony, with HKDF
  fallback gated behind a capability probe).
- Storage is routed through a tier interface; an additional tier can
  be introduced without changing the envelope codec.
- At least 8 of 16 Block Store entries are reserved for future
  control-plane growth. The current Track A footprint is documented as
  ~4.8 KB / 64 KB used.

(Tier 3 — opaque cloud bucket for cross-device cross-account recovery
— is Track B. Tier 1.5 / Tier 4 — Passport VC stores — are Track C+1.)

### Surface 3 — Cross-app enrollment protocol

The mechanism that makes "I have a sigil; let this new app share it" a
two-app, three-biometric-prompt operation with no shared salt
convention.

Two trust tiers in one protocol:

- **Signature-permission fast path** — apps signed by the same cert
  (first-party Kuira suite, via `<knownSigner>` for cert-rotation
  flexibility) enroll without a fingerprint-comparison screen.
- **TOFU consent path** — apps signed by different certs get a
  first-contact screen showing a **6-word PGP biometric word-list
  fingerprint** of the new app's signing cert. Repeat enrollments
  skip the first-contact screen. 4-word fingerprints (32 bits) are
  grindable in roughly four seconds of CPU and are explicitly out of
  scope; 6 words (48 bits) are the safe floor.

The handoff channel itself is an AIDL bound service with HPKE
(P-256 / HKDF-SHA-256 / AES-256-GCM) sealing. The receiver verifies
the caller's signing cert via the kernel-attested `Binder` chain
(`Binder.getCallingUid()` → `getPackagesForUid()` →
`getPackageInfo(GET_SIGNING_CERTIFICATES)` → `hasSigningCertificate()`).
This is Google's recommended approach as of 2026.

Revocation is exposed as a **two-tier model** with copy that does not
overstate what each tier achieves:

- **Soft revoke** ("Stop sharing with [App]"): removes the peer from
  the local Connected Apps list and sends a polite delete request via
  AIDL. The peer holds its own seed copy until it complies; the UI
  explicitly says so.
- **Hard revoke** ("Rotate sigil"): generates a new master seed,
  re-enrolls trusted peers, transfers all on-chain assets to the new
  sigil, and deprecates the old. Fee-bearing, multi-minute,
  heavy-confirmation. The only path that guarantees a revoked peer
  loses access on a public blockchain.

The bare word "Revoke" is banned in all UI copy; it implies a
guarantee the protocol cannot deliver.

**Acceptance:**
- A user with a Kuira-first-party sigil can launch a newly installed
  first-party app, tap "Use my Kuira sigil," and end up with the new
  app showing their wallet balance. Three biometric prompts total —
  one on the existing app, two on the new app — no fingerprint
  comparison screens.
- A user can do the same for a third-party Kuira-compatible app —
  same flow, plus one first-contact screen showing a 6-word PGP
  fingerprint.
- The existing app maintains a Connected Apps list. The user can
  initiate soft revoke from this list. The UI distinguishes soft
  revoke from sigil rotation in copy and friction.
- Enrollment is robust to: the existing app not being installed, the
  user denying on either end, the device going offline (irrelevant —
  AIDL is local), the app being killed mid-flow (no plaintext on
  disk, no orphan biometric prompt, clean retry).
- The handoff channel is HPKE-sealed; a malicious app intercepting
  the AIDL transcript cannot recover the seed.
- The manifest declares `<queries>` entries for first-party Kuira
  package names; otherwise `bindService` returns false on API 30+
  for un-declared packages.

### Surface 4 — Internal beta + production rollout

The release path that lets V3 reach real users without risk of
stranding funds.

The beta is **closed**, 10–15 people drawn from Kuira contributors,
Midnight dev-chat contacts, and the small number of external devs who
have engaged with the Compact toolchain recently. No public testflight
during Track A. The cohort's job is intentional adversarial use across
diverse device matrices, not population.

Telemetry is **opt-in, off by default**, three closed-enum counters
only — enrollment completed/failed (with closed-enum reason class),
forge completed/abandoned (with wizard step index), biometric denied
per day (aggregated, no timestamps). No addresses, DIDs, transaction
data, or IP-derived geo. The closed-enum constraint on reason classes
is load-bearing — it makes it impossible for a future contributor to
widen the surface by appending exception messages, which is the
canonical PII-leak path for telemetry pipelines.

**Acceptance:**
- A "V3 enabled" build flag exists end-to-end (forge, enrollment,
  recovery all gated).
- Internal beta cohort runs V3 for at least one full week without a
  fund-loss incident.
- Telemetry surfaces (when opted in): enrollment success rate, forge
  completion rate, biometric denial rate, Block Store ack timing,
  any silent failure modes. No PII, no widening surface.
- A documented kill-switch + rollback procedure exists and has been
  tested on a non-production account.
- A device-testing manual at `docs/testing/track-a-manual.md`
  prescribes per-release-tag screen-recording capture for the seven
  device gates (see Viability addendum §"What requires device testing
  before Track A ships").

---

## Midnight Passport — design hooks in Track A

Passport bundles three orthogonal Midnight protocol features that Track
A must architect for without implementing:

1. **Protocol-native account abstraction.** Replaces V3's earlier
   "build a Kuira-specific AA Compact contract" plan. Wallets controlled
   by something other than a single secp256k1 keypair, at the protocol
   layer.
2. **Universal DID** — one DID per user, reusable across regulated
   services, created at wallet creation.
3. **Selective-disclosure VCs over ZK** — partner-issued credentials,
   client-side stored, ZK-presented.

Track A's commitment to Passport is **architectural, not behavioural**.
We add three design hooks now so that post-Passport-spec integration is
weeks of wiring rather than a quarter of refactoring:

- **Versioned envelope codec** (Surface 1 acceptance). v1 stays unchanged
  on disk; v2 reserved for tagged-union with seed + VC store ref + AA
  delegation ref + extensions slot.
- **Storage-tier interface** (Surface 2 acceptance). A future Tier 1.5
  for VCs or Tier 4 for large credential blobs plugs in without
  changing the envelope.
- **Frozen signer namespace** (Surface 1 acceptance). HKDF info-labels
  `kuira:sign:wallet-spend:v1`, `kuira:sign:did-proof:v1`,
  `kuira:sign:vc-presentation:v1` (reserved), `kuira:sign:aa-intent:v1`
  (reserved) — documented and pinned at ship time.

**DID stance.** Track A commits to its derived `did:key` being stable
for the sigil's lifetime. The HKDF info-label, curve, encoding, and a
pinned test vector are documented as a public contract at ship time.
This positions Kuira for Scenario A (Passport adopts the user's
existing DID — the most user-friendly outcome). Scenario B (Passport
issues its own DID and we store a linkage proof in the envelope's
extensions slot) is supported architecturally but not implemented in
Track A.

A `DeviceAttestationCapability` probe ships as a Track A diagnostic
(no backend, no UI) — ~100 lines that gives Track B real population
data on StrongBox vs TEE availability before Track B commits to a
specific Keystore-attestation design.

---

## Sequencing notes

Surfaces 1 and 2 are sequenced first because Surface 3 depends on them
existing. Surface 3 (enrollment) can be designed in parallel since it
consumes Surface 1's master seed but its UI work is independent.
Surface 4 (beta + rollout) is continuous — flag work begins on day one
and hardens through the cycle.

The user-facing milestone order:

1. Forge with two-tier storage lands behind the flag. New users opt in
   via the V3-enabled build; their sigil is forged into the new
   envelope format from day one.
2. Cross-app enrollment lands behind the flag. First-party apps
   (BBoard ↔ Kicks ↔ Wallet) can enroll each other.
3. Starter testing uses enrollment to inherit the user's existing
   sigil — closing the test-of-fire loop that this whole investigation
   started from.
4. Internal beta opens with the closed 10–15 cohort.
5. Public rollout: flag flips on by default. No "migrate-only" release
   window — testnet users who haven't opted in re-forge under V3.

---

## Open questions

These need answers before code starts; some are decisions, some are
small spikes.

1. **What's the `appScope` story under V3?** V2 introduced an opt-in
   `appScope` for apps that *want* an isolated wallet (a "game wallet"
   vs main wallet). V3's enrollment model makes isolation default —
   every new app is isolated until explicitly enrolled. Do we still
   need an `appScope` knob for the "I want a SEPARATE wallet within
   the same app" use case?

   **Resolved (per Viability addendum):** keep `appScope` as an
   advanced opt-in for users who want a second sigil within one app
   (the game-wallet pattern), but not on Track A's critical path; can
   ship in a follow-up minor release.

2. **What identifies a user's sigil for enrollment purposes when
   they have more than one?**

   **Resolved (per Viability addendum):** user-chosen nickname (1–24
   characters, no uniqueness constraint), rendered as the primary
   label, with the wallet address tail (`mn_shield1qx7…k2p`) as a
   subtitle for spoofability mitigation. DID is in Settings →
   Advanced, never in primary UI.

3. **How aggressive is the mnemonic-export prompt?**

   **Resolved (per Viability addendum):** strongly recommended,
   skippable with typed confirmation. Full-screen "Back up your
   recovery phrase" after forge. **Back up now** primary, **Skip** as
   gray text — Skip opens a modal requiring the user to type "I
   understand I can lose access" before resolving. Settings always
   offers re-export, biometric-gated.

4. **Beta cohort recruitment.**

   **Resolved (per Viability addendum):** internal-only, 10–15 people,
   opt-in via existing dev-chat. No public beta during Track A.
   Precedent: Tailscale, Linear, Arc early phases.

5. **Telemetry vs sovereignty.**

   **Resolved (per Viability addendum):** opt-in, three closed-enum
   counters, no default network egress. Batched daily over HTTPS to a
   Kuira endpoint that discards source IP before storage. Schema
   documented in the Play Console Data Safety form. Precedent: Brave
   P3A.

6. **DID stability commitment vs Passport-issued DID linkage.**

   **Resolved (per Viability addendum):** commit to stability. Document
   HKDF info-label + curve + encoding + pinned test vector at Track A
   ship. Scenario A primary (Passport adopts Kuira's existing DID);
   Scenario B fallback supported via the envelope's `extensions` slot
   in the v2 codec layout — architecturally enabled, not implemented
   in Track A.

---

## Out of scope (deferred)

| Item | Track | Why deferred |
|---|---|---|
| Tier 3 (Cloudflare R2 cloud bucket) | B | Requires its own ops surface; not required for "shared funds across apps on this device." |
| PIN-based recovery flow + UI | B | Needs Tier 3 first. |
| Argon2id PIN stretching + profile versioning | B | Same. |
| Cross-device, cross-account recovery | B | Same. |
| Passport integration (was: On-chain Sigil Account contract) | C | Midnight Passport supplies native account abstraction at the protocol layer; building a Kuira-specific AA contract is redundant work that ships just in time to be obsoleted. Scope shifts from "build own AA" to "integrate Passport when spec is public." If pre-Passport delegated signing is needed for the agent runtime, a minimal off-chain capability-token shim in the envelope's `extensions` slot is the bridge — not a new on-chain contract. |
| VC store + selective-disclosure presentation | C+1 | Requires Passport spec for credential schema and presentation protocol. Architecturally enabled by Surface 1 (extensible envelope), Surface 2 (storage-tier interface), and the frozen signer namespace — no architectural retrofit required when work begins. |
| iOS port of the V3 architecture | future | iOS is committed but separate; iOS will inherit the same primitive once cross-compile spike completes. |
| V2 → V3 migration (mainnet) | post-mainnet | Drops from Track A for testnet; re-enters as a mainnet plan with real fund-at-risk constraints. |

---

## What "done" looks like for Track A

A user on a V3-enabled Kuira-first-party app, with on-chain testnet
funds, who:

- Forges a sigil with two biometric prompts and ends up with both
  Tier 1 and Tier 2 recovery
- Installs the kuira-starter-android, opens it, taps "Use my Kuira sigil"
- Sees the existing wallet balance immediately in the starter after
  three biometric prompts (one on Wallet, two on starter)
- Can deploy + increment the counter contract using the existing funds
- Sees the starter listed in Wallet's Connected Apps screen with a
  working "Stop sharing" path

…has experienced the entire happy path Track A is designed for. The
starter testing that began this whole investigation closes naturally
once that user-visible flow lands.

---

## What this plan does NOT promise

- **No migration of mainnet sigils.** Track A is testnet-only by
  scope.
- **No protection from forgotten PIN.** PIN recovery is Track B.
  Track A's recovery story is same-app reinstall (Tier 2 / Block
  Store) only.
- **No cross-Google-account recovery.** Block Store is per-Google-
  account; a different account on a fresh device has no Tier 2 to
  restore from. Track B closes this with cloud-bucket recovery.
- **No verifiable-credential storage or presentation.** Track C+1.
- **No on-chain account abstraction.** Track C, as Passport
  integration when Passport ships.
- **No guarantee that "Stop sharing" prevents the peer from signing.**
  Only sigil rotation does that. The UI is explicit about the
  distinction.
- **No public beta.** Closed 10–15 cohort only during Track A.
