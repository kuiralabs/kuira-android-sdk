# Sigil V2 — Track A Viability addendum

> **Naming note.** This addendum supports **Sigil V2** — Kuira's
> next-generation sigil architecture (publicly named "Sigil V2";
> internally the design the third investigation round arrived at, so
> the body below sometimes still says "V3"). The currently *shipped*
> sigil architecture is publicly **Sigil V1**, called "V2" in the
> body's Status references. The historical phrase "V2 → V3 migration"
> = "Sigil V1 → Sigil V2 migration." Rejected investigation rounds
> live under `docs/research/archive/`.

*Investigation date: 2026-05-30*
*Status: confidence-building before implementation commits.*
*Companion to: [`docs/SIGIL_V2_TRACK_A_PLAN.md`](SIGIL_V2_TRACK_A_PLAN.md)*
*Scope corrections from prior revisions: Sigil V1 → V2 migration dropped (alpha + testnet, no real-fund risk, re-forge is free); Midnight Passport integration added as a Track A design constraint.*

---

## TL;DR

- **Go.** Track A is technically shippable at **≥95% aggregate confidence** with three plan amendments (see §"Updated plan recommendations"). No surface scores below 80% after corrections.
- **Surface 1 (master seed lifecycle + Passport-extensible envelope): 92%.** PRF multi-salt is supported per W3C WebAuthn L3 and Yubico; two independent outputs per ceremony confirmed. The "second salt = rotation only" reading was wrong on mechanics. Add a versioned, length-prefixed payload codec and the envelope is Passport-ready without breaking v1 layouts.
- **Surface 2 (Tier 1 + Tier 2 + storage-tier abstraction): 88%.** Block Store's 4 KB × 16-entry budget is sufficient for the V3 seed envelope plus multi-sigil metadata forever. It is **not** sufficient for Passport VCs — a `SigilStorageTier` interface is required so a future Tier 1.5 (per-app encrypted VC store) or Tier 4 (Drive `appDataFolder` for VCs) drops in without re-engineering.
- **Surface 4 (cross-app enrollment): 84%.** AIDL bound service with HPKE(P-256/HKDF-SHA256/AES-256-GCM) sealing, two-biometric gating (release on A, re-wrap on B), and signature-permission for first-party plus TOFU for third-party is the right shape. Three things must land: 6-word PGP fingerprint (not 4 — 32-bit grinding is too cheap), pre-cleared Play Console Financial Features Declaration, and explicit per-app revocation copy that does not lie about what revoke achieves.
- **Surface 5 (beta + rollout): 90%.** Closed internal cohort of 10–15 (Kuira team + Midnight dev-chat contacts + recent Compact users), opt-in telemetry with three closed-enum counters, no public beta. Pattern matches Tailscale/Linear/Arc early phases.
- **Passport-readiness: 94%.** Three deltas — versioned envelope codec, open-enum tier_tag with `SigilStorageTier` interface, `SigilSigner` interface with frozen HKDF namespace — make V3 a clean plug-in for Passport when its spec lands. Track C reframes from "build own AA contract" to "integrate Passport" — saves a quarter, avoids forking the AA story.
- **Requires device testing before ship:** (a) Credential Manager chooser sheet across GMS versions, (b) Block Store cloud-restore round-trip on a clean second device, (c) AIDL handoff under mid-flight process kill on at least one StrongBox and one TEE-only device, (d) PGP-fingerprint readability on low-DPI displays.

---

## Scope corrections from the original plan

Two changes from the prior addendum revision; both reduce scope and increase clarity.

**1. V2 migration is removed.** The original plan's Surface 3 ("V2 → V3 migration path") is dropped entirely. Justification: alpha + testnet only, no real funds at stake, re-forge is free, and shipping a one-shot migration adds engineering surface and a coupled rollback risk for zero user value. Existing V2 sigils on testnet can be re-forged. The Track A surface count drops from 5 to 4; downstream surface numbering shifts (Surface 4 → 3, Surface 5 → 4) in any plan revision, but this document keeps the original numbering for cross-reference with the in-flight plan doc.

**2. Midnight Passport integration becomes a design constraint, not a future feature.** Passport is Midnight's protocol-native account-abstraction + universal-DID + verifiable-credentials system (Hoskinson confirmation, transcript L100/L130). V3 Track A is architected so Passport plugs in as a natural extension — versioned envelope codec, open storage-tier enum, frozen HKDF signer namespace. None of these *implement* Passport; all three *enable* Passport to land cleanly post-spec. Track C reframes from "build a Kuira-specific on-chain AA contract" to "integrate Passport when its spec is public."

Neither change touches the user-visible scope of Track A: forge a sigil with two recovery tiers under one biometric ceremony, enroll a second app onto the same sigil with two biometric prompts, see/revoke connected apps, recover from same-device reinstall with a single biometric.

---

## Confidence per surface

| Surface | Confidence | What raises it | What lowers it |
|---|---|---|---|
| **1. Master seed lifecycle (+ Passport-extensible envelope)** | **92%** | PRF multi-salt confirmed in W3C WebAuthn L3 spec, MDN, Yubico, Corbado; two HMAC-SHA256 outputs per UV ceremony. Envelope codec change (v1 = bare seed, v2 = CBOR tagged union) is local and reversible. Existing V3 unlock path is well-understood. | Forge requires `create()` + immediate `get()` (most authenticators don't return PRF on create) — 2 biometric prompts at forge instead of the originally claimed 1. UX needs to absorb this honestly. |
| **2. Tiered storage (Tier 1 + Tier 2 + Passport-aware abstraction)** | **88%** | Block Store budget arithmetic (~130 B real envelope / 256 B reserved per sigil; 16 × 4 KB cap) easily holds V3 + multi-sigil + 8 dApp records + reserved slots. Cloud-restore mechanics documented; E2EE flag is orthogonal to upload (`setShouldBackupToCloud(true)` always). Storage-tier interface is small refactor. | Smart Switch idempotency bug (skipped Accounts step on re-run) means Block Store restore is silently lossy in real D2D scenarios — Tier 2 cannot be the sole copy of anything irrecoverable. Passport VCs cannot fit in Block Store at all; a Tier 1.5 / Tier 4 must exist before VCs ship (Track C+1, not Track A). |
| **4. Cross-app enrollment** | **84%** | AIDL + `Binder.getCallingUid()` is kernel-attested and Google-recommended. `<knownSigner>` (API 31+) works for first-party multi-app suites. HPKE (Tink, BoringSSL) removes hand-rolled ECDH+HKDF+GCM bugs. Two-biometric design is symmetric and prevents silent leakage in either direction. Failure modes (denial, kill, replay) have clean recovery paths. | Play Store review risk for AIDL services moving seed-equivalent material is non-zero (no public safe-harbor). Mitigated by Financial Features Declaration and consent-gated UX, not eliminated. 32-bit PGP fingerprint (4 words) is grindable in ~4 seconds; **must bump to 6 words / 48 bits** for the third-party TOFU path. |
| **5. Beta + rollout** | **90%** | Closed-cohort precedent (Tailscale, Linear, Arc) shows pre-stable products need bug *quality* over bug *count*. Opt-in three-counter telemetry with closed-enum reason classes mirrors Brave P3A — minimal surface, impossible to widen accidentally. | Opt-in telemetry typically yields 5–15% participation; sparse data biased toward power users. Acceptable because cohort is small and direct feedback dominates. |

**Aggregate Track A: 89%.** Weighting: Surface 1 (35%), Surface 2 (25%), Surface 4 (30%), Surface 5 (10%). With the three plan amendments below (6-word PGP fingerprint, codec-versioning gate on Surface 1, storage-tier interface on Surface 2), aggregate rises to **≥95%** and the surface floor is 88%.

The three amendments are not optional — they are the gate between "84% Surface 4" and the ≥95% target.

---

## Technical viability findings

### Master-seed lifecycle and PRF ceremony budget

The "second PRF salt is rotation-only" reading in Phase 1's brief is **incorrect on mechanics, partially correct on framing**. W3C WebAuthn L3 §10.1.4 defines `AuthenticationExtensionsPRFInputs.eval` as a `{first, second}` value pair, and `AuthenticationExtensionsPRFOutputs.results.second` is populated iff `second` was supplied. Yubico's developer guide is unambiguous: two distinct HMAC-SHA-256 outputs in a single user-verification gesture. The key-rotation example is the *motivating* use case; the wire-level behaviour is two independent secrets.

**Conclusion:** V3 should use multi-salt by default. Two PRF outputs give domain separation at the authenticator boundary — an attacker who learns the Tier-1 unwrap key cannot derive the Tier-2 key without another UV ceremony. The HKDF-from-single-output fallback (option 5 in the brief) is unnecessary for any modern authenticator and should be gated behind a capability probe at enroll time (if `results.second` is missing, fall back).

**Honest prompt count** (correcting the V3 plan's claim of "two biometric taps" for everything):

| Flow | Prompts |
|---|---|
| Forge sigil | 2 (`create()` + harvest `get()`) |
| Enroll App-B from App-A | 1 on A, 2 on B (3 total: B's `create()` + B's PRF harvest + A's release) |
| Spend (Tier-1 only) | 1 |
| Admin (Tier-2 only) | 1 |
| Combined Tier-1 + Tier-2 op | 1 |

The plan doc should be updated: enrollment on App-B is 2 taps locally, not 1, because `create()` requires its own UV gesture independent of PRF. This is unavoidable until platforms ship PRF eval at `create()` time (Chromium tracks this; do not bank on it for Track A).

### Block Store budget audit

Hard caps (from `BlockstoreClient` reference and `BlockstoreStatusCodes`):

- **Per-entry: 4 KB** (key + value combined). Violation → `MAX_SIZE_EXCEEDED (40002)`.
- **Per package: 16 byte arrays.** Violation → `TOO_MANY_ENTRIES`.
- **Effective package ceiling: ~64 KB.**
- **No LRU eviction**, no over-quota grace. App must catch and fall back.

V3 envelope realistic size — not the 104 B optimistic figure in the original plan, but ~130 B with proper AAD/KDF salt/CBOR framing. Round to **256 B reserved per sigil envelope** for evolution headroom (curve tag, KDF id, rotation epoch, codec version).

**V3 multi-sigil + control-plane budget** (using 8 of 16 entries, leaving 8 reserved):

| Item | Entries | Bytes |
|---|---|---|
| Sigil 1 envelope | 1 | 256 |
| Sigil 2 envelope | 1 | 256 |
| Sigil 3 envelope | 1 | 256 |
| Connected-apps index | 1 | ~1 KB |
| Per-app authorization records (CBOR list, 8 dApps) | 1 | ~2.4 KB |
| Migration / schema version marker | 1 | 64 |
| Rotation epoch / replay log | 1 | 512 |
| Reserved spare | 1 | — |
| **Subtotal** | **8 / 16** | **~4.8 KB / ~64 KB** |

Track A fits comfortably with ~60 KB headroom and 8 reserved entries. Track A's storage story is **sufficient and durable** within Block Store alone.

**Passport VCs do not fit**, ever. A single LD-VC or mdoc credential is 4–15 KB, exceeding the per-entry cap before chunking, and 3–4 chunked VCs would exhaust the entry budget. This is fine for Track A (Passport VCs are Track C+1) provided the storage-tier interface lands now so a future Tier 1.5 (per-app encrypted SQLite under the same passkey-derived key family) or Tier 4 (Drive `appDataFolder` with our own AEAD wrap) plugs in without re-engineering Surface 2.

### Cross-app enrollment — signature permissions, knownSigner, TOFU

The standard pattern (`Binder.getCallingUid()` → `getPackagesForUid()` → `getPackageInfo(GET_SIGNING_CERTIFICATES)` → `hasSigningCertificate()`) is still Google's recommended approach in 2026 (Android payment-apps developer guide). `<knownSigner>` (API 31+) walks the signing lineage and allows multi-cert trust at signature-permission level, which is the correct mechanism for the Kuira-first-party fast path (Wallet ↔ BBoard ↔ Starter, all signed by the Kuira cert; no TOFU screen).

**Edge cases that bite if not handled:**
- `sharedUserId` ambiguity (deprecated API 29; new installs use `sharedUserMaxSdkVersion="32"`) — if `getPackagesForUid` returns multiple, verify *every* signer cert, not just the first.
- Connection caching — re-verify UID on every IPC call, not just at `onBind` (Google explicitly documents this for payment apps).
- Package-name spoofing — defend with cert hash, not package name. `PackageManager.hasSigningCertificate()` is the right primitive.

For third-party apps (no shared cert), TOFU is mandatory because `<knownSigner>` cannot retrofit forward-trust into an already-shipped v1 wallet that does not know the future third-party cert hash. The TOFU UX must be honest: "If the developer published these N words on their website, compare them now. If they didn't, you're trusting the install channel."

**4 words = 32 bits is too thin.** PGP word-list collision at 32 bits is grindable in ~4 seconds on commodity hardware (Wikipedia/public-key-fingerprint). **Bump to 6 words = 48 bits.** This is the single most important Track A amendment — it costs ~50% more UI real estate and removes the meaningful grinding risk. The PGP list (alternating 2-syllable/3-syllable, designed for voice-channel comparison with built-in transposition/duplicate/omission detection) is the right primitive choice; only the length needs correction.

### HPKE protocol selection

HPKE (RFC 9180) with DHKEM(P-256, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM — Tink, Google, BoringSSL-backed. Picked over hand-rolled ECDH+HKDF+GCM for three reasons:

1. One spec, one ciphersuite ID, one library call — no glue-layer foot-guns.
2. Context binding via `info` parameter is structural, not bolted-on. App-B cannot decrypt if any context field (consumer package, consumer cert SHA-256, nonce_A, nonce_B, payload mask, protocol version) disagrees.
3. P-256 is TEE-hardware-accelerated on every Android device with Keystore.

X25519 would be marginally faster but loses the StrongBox-backed key-attestation path some enterprise deployments may need (Track B problem, not Track A, but free option-value to keep).

### `K_cloud` Keystore attestation — defer to Track B

V3 finding S1 (StrongBox-preferred Keystore attestation binding for `K_cloud`) is mechanically sound but **out of scope for Track A**. Track A ships Tier 1 (biometric-gated Keystore) and Tier 2 (Block Store, which provides E2EE + recovery natively). `K_cloud` only has work to do when Tier 3 exists, which it doesn't in Track A. Shipping attestation plumbing now creates a registry, a verifier service, and a backend round-trip with zero consumer.

**Track A carve-out:** a passive `DeviceAttestationCapability` probe (`hasStrongBox`, `hasTeeAttestation`, `keymintVersion`) surfaced in diagnostics. ~100 LOC, no backend. Gives Track B real population data for its policy floor (StrongBox-only vs StrongBox-preferred-TEE-accepted) instead of guessing. KeyDroid (arxiv 2507.07927) puts StrongBox-requesting apps at ~4.6%; flagship-only StrongBox coverage in Android 14+ devices is ~25–35% globally, much lower in LATAM/SEA. **Designing Track B to require StrongBox would brick most users**; accept TEE as the baseline floor.

---

## UX trade-off summary

### Enrollment UX — discovery, chooser, fast path, TOFU

The walkthrough lands on three correct defaults:

- **Smart discovery, not forced chooser.** If no other Kuira apps are detected, jump to "Forge new sigil" (single primary CTA, with "Already have a Kuira app? Install Kuira Wallet" as a footnote). If one or more are detected, show the fork with "Use my sigil from <App>" primary and "Forge new" secondary. Forcing a chooser for users with zero prior apps is friction without value.
- **First-party fast path bypasses TOFU and fingerprint screens entirely.** Signature-permission handles attestation. Two biometric prompts (one on each side), no first-contact screen, ~3 seconds end-to-end.
- **Third-party TOFU screen tells the truth.** "We can't verify this app is who it says it is. If the developer published these words on their website or store listing, compare them now. If they didn't, you're trusting the install channel."

**Mid-handoff latency is the critical UX hazard.** End-to-end enrollment is 3–6 seconds with a brief app-switch when App-A foregrounds for its biometric. The loading state must say *which app is doing work* ("Waiting for BBoard…") or users hit back/home thinking the app froze, killing the handoff. Add a "Open BBoard" recovery CTA after 4 seconds for OEMs that swallow the trampoline.

### Revocation UX — two-tier model, honest copy

The walkthrough's core insight is that "revoke" is a lie when there is no authority server. Two tiers, named honestly:

- **Stop sharing (soft, free, one tap):** removes peer from local trust list, sends AIDL `delete_seed` request. Cannot force a malicious/offline peer to comply. Toast: "Removed Kicks. Delete request sent." No green checkmark of false security.
- **Rotate sigil (hard, network fee, type-to-confirm):** new sigil, on-chain transfer of all assets, all peer copies become useless. Cannot recover funds drained pre-rotation.

The escalation path lives *inside* the soft-action dialog so users who paused because something felt wrong can upgrade without backing out. The revoke dialog spells out three things the user must understand: (1) the peer keeps its existing copy until uninstalled, (2) funds already spent are spent, (3) full cut requires uninstall. **This is the single most important UX commitment in Track A** — the V3 architecture is honest about peer-held copies; the UI must be too, or it inherits MetaMask's "disconnect ≠ revoke" trust-collapse class.

### Tier 2 recovery — single-biometric, "Welcome back"

Same-app reinstall flow: probe returns `RestorableV3Envelope(found=true)` → render "Welcome back. Your sigil is backed up. Last synced 2 days ago. [ Restore my sigil ]." One biometric prompt (Credential Manager + GPM under the hood) → decrypt Tier 2 envelope → seed in memory → immediately re-write Tier 1 → transition to `Active`. Roughly 2–3 seconds visible.

Edge cases: different Google account → render forge-or-restore fork with hint "Signed in as a different Google account?", do not auto-redirect to system settings. Corrupted envelope → treat as tampering, not transient error; offer "Try again" + "Restore from recovery phrase" but never silently offer "forge new" (which destroys on-chain identity).

### Comparison with production wallets

Track A lands closer to Coinbase Smart Wallet + Backpack xNFT + Signal SVR2 than to Phantom/MetaMask. The reusable copy bets:

- **"Add recovery options"** — verbatim from Coinbase, user-tested better than "Backup your wallet."
- **"Kuira cannot recover your sigil if you lose access to this passkey"** — adapted from Signal's "Signal will not be able to reset it for you." Names the institution as explicitly unable; no "for your security" hand-waving.
- **Capability-list framing on TOFU screen** — Backpack pattern, concrete > abstract. "This app will be able to: see your wallet balance / request transaction signatures / read your DID."
- **Confirm-by-typing for irreversibility gates** — GitHub/AWS S3 pattern, recognized by users, proven to suppress accidental skips while permitting intentional ones.

The active anti-patterns to kill:

- WalletConnect's long-lived sessions with no auto-expiry — the single most-cited drain source. Add "Last unlocked: 14 days ago" + one-tap auto-revoke-on-staleness toggle in Settings.
- MetaMask's "disconnect ≠ revoke" ambiguity — never let any V3 copy drift toward "disconnect."
- Coinbase's silent passkey-provider mismatch — make GPM-default-provider detection a blocking pre-flight check at forge time, with an actionable "Open passkey settings" deep link.
- Argent/Safe vocabulary ("guardians", "co-signers") — those words carry custodial-multisig semantics V3 does not implement. Use plain "Apps."

---

## Engineering risks + mitigations

**S1 — Play Store review of AIDL seed-handoff service.** No public safe-harbor in Play's Malware → Elevated Privilege Abuse policy. Reviewers may flag any AIDL service that moves seed-equivalent material, even with consent. Mitigation: (a) frame as user-initiated migration in the listing copy, (b) ensure both apps declare each other under `<queries>` (mandatory since Android 11 / API 30), (c) submit Financial Features Declaration with the consent + biometric UX documented, (d) pre-clear the design with a Play policy escalation before launch, (e) document the consent UX in the Data Safety form. Residual risk: non-zero but bounded; password-manager autofill suites use structurally similar patterns without policy violations.

**S1 — TOFU fingerprint grindability.** 32 bits (4 PGP words) is collidable in seconds. **Mitigation is mandatory: bump to 6 PGP words (48 bits).** Wider UI footprint, removes the grinding risk. Track A cannot ship with 4 words.

**S2 — Smart Switch idempotency bug silently losing Block Store data.** If Smart Switch re-runs on a target that already has the source's accounts, it skips the Accounts step and Block Store data is not transferred. No callback to signal loss; `retrieveBytes` returning empty is indistinguishable from "never stored." Mitigation: Block Store **must never be the sole copy** of anything irrecoverable. Tier 2 is recoverable from Tier 1 (which the user still has on the source device pre-uninstall) and from the recovery phrase (export at forge with strong recommendation). For cross-device restore where Tier 1 is unavailable, the phrase is the only true safety net — make the export UX correspondingly serious.

**S2 — Mid-handoff app kill leaves orphan state.** App-B killed after receiving sealed payload but before re-wrap; or App-A killed after biometric but before sealing. Mitigation: provider holds no transient state on disk between biometric and seal (existing V3 Tier-1 unlock path already does in-memory zeroization); consumer detects orphan half-state on next launch and resumes from a safe checkpoint or restarts cleanly from Stage 1. Replay-safety enforced by fresh `nonce_A` per session. Recovery is silent.

**S2 — Credential Manager UI flakiness on emulator.** Bottom-sheet timing and localized text break UiAutomator selectors across GMS updates. Mitigation: CI covers ~70% of enrollment (cryptographic correctness, idempotency, cross-version fallback, kill-mid-handoff via `am force-stop`) using a helper-APK pattern (`core:identity:androidTest` driver + `core:identity:androidTestPeer` stripped helper). The remaining ~30% (chooser sheet UI, biometric prompt, two-device revocation) is **manually gated per release tag** on one Pixel physical device + one emulator, with scripted protocol in `docs/testing/track-a-manual.md` and screen recordings archived. Do not block Track A acceptance on UI automation — it will regress every GMS update.

**S3 — `create()` does not return PRF on most Android authenticators.** Forge requires `create()` + immediate `get()` (two biometric prompts). Mitigation: update the V3 plan's "one tap" claim to "two taps at forge"; surface this honestly in the UX walkthrough copy. Watch Chromium for `create()`-time PRF eval but do not bank on it.

**S3 — Tier 2 ack latency 0.5–10 s on flaky LTE.** Mitigation: Tier 1 write completes synchronously; Tier 2 ack is async with a queued retry. Forge completes on Tier 1 + queued Tier 2. UI surfaces "Cloud backup pending — retrying" as a non-blocking banner. Background worker finishes Tier 2.

**S3 — Multi-app collision under one GPM account.** Same as Kicks PvP testing constraint (`project_sigil_gpm_account_constraint`). Two emulators on one GPM account collapse to one sigil. Mitigation: distinct `applicationId` per build (product flavor `dev.kuira.wallet` vs `dev.kuira.wallet.peer`); `hostAppLabel()` already salts `user.id` per package; document the limitation in a Kicks-class wishlist issue so it's tracked alongside the existing one.

**S3 — `<knownSigner>` cannot retrofit forward-trust into shipped v1 wallets.** A v1 wallet shipped before the V3 enrollment design was decided cannot trust a not-yet-existing cert. Mitigation: TOFU path is therefore not optional for third-party apps; its UX must be airtight (see "Cross-app enrollment" UX section). For first-party Kuira apps, `<knownSigner>` works as designed.

---

## Decisions on the open questions

**1. `appScope` under V3 — keep the capability, remove the public API.** The PRF salt dimension that backs `appScope` stays in the derivation. Public surface collapses to a single user-facing primitive: "Add another sigil to this app." Host apps cannot programmatically request scopes (closes the V2 footgun where a host could silently fragment a user's funds by toggling a constructor flag between releases). Multiplicity becomes a user gesture, not a developer flag. Precedent: iOS Keychain `kSecAttrAccessGroup` — capability in platform, gated by entitlements, never freely invented by apps. Trade-off accepted: host apps lose programmatic scope control; a game cannot auto-provision separate "match wallet" / "tournament wallet" sigils without user taps. This is the right loss.

**2. Multi-sigil identification — required user-chosen nickname + address-prefix subtitle.** At forge, 1–24 char nickname, no uniqueness constraint, trimmed. Chooser renders nickname headline + `mn_shield1qx7…k2p` subtitle. DID never in primary UI; lives in Settings → Advanced for off-app coordination (multisig setup, support tickets). Three candidates scored: DID (cold-unrecognizable, uniform prefix), address-only (uniform Bech32m prefix, visual collision), nickname (semantic but spoofable). Combining nickname + address tail mitigates spoofability the way "last four of card" does for credit cards. Every hardware-wallet vendor and Phantom/Rabby converged on naming for a reason — naming is how humans index keyrings.

**3. Mnemonic export prompt — strongly recommended, skippable with typed confirmation.** Full-screen "Back up your recovery phrase" step after forge. **Back up now** primary, **Skip** as gray text button. Skip opens a modal requiring the user to type "I understand I can lose access" before resolving. Settings always offers re-export, biometric-gated. Not blocking (drives photo-the-screen and abandonment), not buried (Track B accepts "no mnemonic = funds lost," and burying it means users discover it post-loss). Typed-confirmation gate defeats reflexive Skip-tapping without blocking the user who genuinely understands. Precedent: GitHub delete-repo, AWS S3 bucket-delete. Trade-off accepted: some alpha users will skip, lose their PIN, lose funds. Consistent with Track B's accepted-loss posture and testnet-only scope.

**4. Beta cohort — internal-only, 10–15 people, opt-in via existing dev-chat.** No public beta. Recruitment by direct DM (Kuira contributors + Midnight dev-chat contacts + the 4–6 external devs who engaged with the Compact toolchain in the last 60 days). Job is to stress-test enrollment success across GPM-account topologies, forge-flow comprehension (typed-skip read rate), and biometric denial recovery — none of which need population, all of which need *intentional adversarial use across diverse device matrices*. Precedent: Tailscale early beta, Linear pre-1.0, Arc invite-only. Wallet software adds the property that confused beta users can lose value, making uncontrolled cohort growth a support liability. Trade-off accepted: lose marketing surface and longer-tail edge cases; both reclaimable post-V3 with a Phase-2 public testflight once enrollment is stable.

**5. Telemetry vs sovereignty — opt-in, three closed-enum counters, no default network egress.** Telemetry off by default. Settings toggle ("Help improve Kuira") enables three counters, batched daily over HTTPS to a Kuira-controlled endpoint that discards source IP before storage:
1. `enrollment.completed` / `enrollment.failed{reason_class ∈ {biometric_denied, gpm_unavailable, storage_write_failed, other}}`
2. `forge.completed` / `forge.abandoned{step}` (wizard index, not content)
3. `biometric.denied{count_per_day}` (aggregated, no timestamps)

No addresses, DIDs, transaction data, mnemonic-prompt outcomes (typed-skip result lives only on-device in a debug log behind `BuildConfig.DEBUG`), or IP-derived geo. The closed-enum constraint on `reason_class` is load-bearing — it makes it impossible for a future contributor to widen the surface by appending exception messages, the canonical PII-leak path for telemetry pipelines. Precedent: Brave P3A. Opt-in rates of 5–15% are sparse but sufficient to detect catastrophic regressions (enrollment success collapsing) and the internal cohort dominates the qualitative signal anyway.

**6. Midnight Passport — DID stability commitment (new question, see Passport section).** Track A commits to its derived `did:key` being stable for the sigil's lifetime, frozen as a public contract documented at ship time (HKDF info-label, curve, encoding, pinned test vector). This positions Kuira for Scenario A (Passport adopts the user's existing DID — most user-friendly outcome) while preserving Scenario B (Passport issues its own DID, linkage proof stored in envelope `extensions` slot). Either scenario requires DID stability now; neither requires DID *linkage implementation* in Track A.

---

## Midnight Passport readiness

Passport bundles three orthogonal Midnight protocol features:

1. **Native account abstraction** — chain-recognized accounts controlled by something other than a single secp256k1 keypair, at the protocol layer (not a contract).
2. **Universal DID** — one DID per user, reusable across regulated services, created at wallet creation.
3. **Selective-disclosure VCs over ZK** — partner-issued credentials, client-side stored, ZK-presented.

Compatibility is therefore not one property but three. V3 needs to hold credential material, sign three kinds of payloads (wallet spend, VC presentation, AA intent), and surface a stable DID. It does **not** need to implement any of those in Track A.

### What V3 needs to add now (cheap, design-only)

**Delta 1 — Versioned, length-prefixed payload codec.** Keep the 104-byte envelope as a primitive (tier_tag AAD binding is real value). Reinterpret the 32-byte ciphertext slot as the v1 case of a tagged union: v1 = bare master seed (current); v2 = CBOR map `{seed, vc_store_ref, aa_delegation_ref, extensions}` with explicit lengths. On-disk envelope grows only when v2 ships; v1 envelopes deserialize unchanged. Codec change only — no key-management impact, no migration.

**Delta 2 — Open-enum tier_tag + `SigilStorageTier` interface.** Refactor `tier_tag` AAD values to be an open enum (`L1`, `BS`, `CL` shipped; additional values reserved). Route storage through a `SigilStorageTier` interface so a future Tier 1.5 (per-app encrypted SQLite/Room file under the same passkey-derived key family for VCs) or Tier 4 (Drive `appDataFolder` with our own AEAD wrap) plugs in without re-engineering Surface 2. The Tier 3 cloud-bucket abstraction already exists in spec form (R2 Worker at `KuiraEndpoints.RECOVERY_R2`); generalizing it costs nothing.

**Delta 3 — `SigilSigner` interface with frozen HKDF namespace.** Today `MidnightWallet` and the Schnorr signer hardcode `seed → spend key`. Introduce `SigilSigner` with concrete impls `WalletSpendSigner` and `DidProofSigner` (both shipped in Track A), plus reserved-but-empty `VcPresentationSigner` and `AaDelegationSigner`. Each signer derives its key via HKDF from the master seed under a documented namespace:
- `kuira:sign:wallet-spend:v1`
- `kuira:sign:did-proof:v1`
- `kuira:sign:vc-presentation:v1` (reserved)
- `kuira:sign:aa-intent:v1` (reserved)

The namespace is **frozen as a public contract**. Establishing it now avoids a painful retrofit when Passport ships and demands "give me a presentation key derived stably from the user's sigil."

These three deltas are the entire Passport-readiness gate for Track A. Estimated cost: ~3–5 days of design rigor during Track A. Estimated saving: a quarter of refactoring when Passport ships.

### What Track C becomes

Today: "On-chain Sigil Account contract" (V3 plan L238) — a Kuira-built Compact contract wrapping the wallet so agents and delegated apps can sign without holding the spend key. Right call when AA was hypothetical at the protocol layer.

With Passport confirmed as Midnight's native AA program (Hoskinson transcript L100/L130), **Track C pivots from "build" to "integrate."** Building our own AA Compact contract is redundant work that will ship just in time to be obsoleted by the protocol-native version. Worse, it forks the AA story (Kuira-AA semantics vs Passport-AA semantics) and forces a second migration when Passport lands.

**Rename Track C to "Passport integration."** Deferred kickoff (when public Passport spec is available). Scope shrinks from ~2 quarters of contract design + audit to ~weeks of wiring the existing `SigilSigner` interface to Passport's intent-submission API.

If genuinely-delegated signing is needed *before* Passport ships (e.g., for the Phase 8 agent-runtime story), a minimal stop-gap lives as an off-chain capability-token system inside V3's envelope (an `aa_delegation_ref` extension via Delta 1) rather than as an on-chain contract. Cheaper, throwaway-able when Passport lands.

### DID stance — Scenario A primary, Scenario B fallback

**Scenario A (preferred): Passport adopts the user's existing DID.** Most user-friendly; matches "wallet creation also creates DID" cleanly. Commitment required *now*: document the HKDF info-label, curve, encoding (`did:key` with multibase prefix), pin a test vector. Documentation-only deliverable, lands with Track A.

**Scenario B (fallback): Passport issues its own DID.** User then has two DIDs (Kuira sigil + Passport). V3 stores a linked-DIDs entry in the envelope's `extensions` slot (Delta 1): `{did, issuer, linked_at, proof_of_linkage}`, where linkage proof is a signature over `(kuira_did || passport_did)` made by both keys. One-screen UX ("Link your Midnight Passport"), gated on Passport availability. Not implemented in Track A.

Both scenarios require Track A to commit to DID stability *now* — both scenarios require Passport to identify a Kuira user across sessions.

### Plan deltas (paste-ready for `SIGIL_V3_TRACK_A_PLAN.md`)

**Surface 1 acceptance criteria, add:**
> The envelope's payload slot is parsed via a versioned, length-prefixed codec. v1 = bare 32-byte seed (current behaviour, no on-disk change). The codec contract is documented and the v2 (extensible) layout is reserved but not implemented in Track A.
>
> A `SigilSigner` interface exists with `WalletSpendSigner` and `DidProofSigner` concrete impls. Future signers (VC presentation, AA delegation) derive keys via HKDF from the master seed under a documented `kuira:sign:*` info-label namespace. The namespace is frozen as a public contract.

**Surface 2 acceptance criteria, add:**
> Storage tiers are routed through a `SigilStorageTier` interface. `tier_tag` AAD values are an open enum — `L1`, `BS`, `CL` shipped; additional values reserved for future per-app credential stores and opaque large-blob buckets without breaking envelope compatibility.

**Open Questions, add #6:**
> **DID stability commitment vs Passport-issued DID linkage.** Does Kuira commit to its derived `did:key` being stable for the sigil's lifetime, so Midnight Passport can adopt it as the user's universal DID? Or do we plan for Passport to issue a parallel DID and store a linkage proof in the envelope's `extensions` slot? Decision needed before Track A's DID derivation is frozen as a public contract. **Resolved (this addendum): commit to stability; Scenario A primary, Scenario B fallback via `extensions` slot.**

**Out-of-scope / deferred table, revise Track C row and add VC-store row:**
> | Passport integration (was: On-chain Sigil Account contract) | C | Midnight Passport supplies native account abstraction at the protocol layer; building a Kuira-specific AA contract is redundant. Scope shifts from "build own AA" to "integrate Passport when spec is public." If pre-Passport delegated signing is needed for the agent runtime, a minimal off-chain capability-token shim in the envelope's `extensions` slot is the bridge — not a new on-chain contract. |
> | VC store + selective-disclosure presentation | C+1 | Requires Passport spec for credential schema and presentation protocol. Architecturally enabled by Surface 1 (extensible envelope), Surface 2 (storage-tier interface), and Surface 1's signer interface — no architectural retrofit required when work begins. |

---

## What requires device testing before Track A ships

Items not verifiable by research alone. Each lists a specific test plan and a pass criterion.

**1. Credential Manager chooser sheet UI across GMS versions.** Forge a sigil on (a) Pixel 8 with current Play services, (b) Pixel 6a with the lowest Play services version the target API supports, (c) one Samsung S24 with One UI's Samsung Pass + GPM coexistence. Confirm: sheet renders, "Use passkey" path is visible, biometric prompt triggers, PRF result is returned, multi-salt produces two distinct outputs (assert byte inequality and length=32 each). Pass criterion: 3/3 devices succeed; if Samsung S24 fails due to Samsung Pass preempting GPM, document the constraint and ship a blocking pre-flight warning.

**2. Block Store cloud-restore round-trip on a clean second device.** On Device A: forge sigil, write Tier 2, verify Block Store contents present. Sign out Device A's Google account, factory-reset Device B (same OEM), restore Device B with same Google account via cloud restore (not D2D). Install Kuira app on Device B. Assert: probe returns `RestorableV3Envelope(found=true)`, single-biometric unlock decrypts, Tier 1 is re-written, sigil is `Active`. Pass criterion: round-trip succeeds end-to-end; if Smart Switch is forced (no cloud restore option), document fallback path via recovery phrase.

**3. AIDL handoff under mid-flight process kill.** On one StrongBox device (Pixel 8) and one TEE-only device (e.g., Pixel 6a or mid-range Samsung), exercise enrollment between two Kuira-cert-signed apps. Kill App-A via `am force-stop` between (a) bind-success and biometric, (b) biometric-success and seal, (c) seal-success and AIDL return. Kill App-B via `am force-stop` between (d) sealed-receive and re-wrap-biometric, (e) re-wrap and Tier-1-write. Pass criterion: each kill leaves no plaintext on disk, no half-state that prevents a clean retry, no orphan biometric prompt. Specifically: case (b) must zeroize seed in App-A on process death; case (e) must not partially-commit Tier 1 in a state that fails the next launch's probe.

**4. PGP 6-word fingerprint readability on low-DPI displays.** Render the 6-word fingerprint (chosen from PGP biometric word list, alternating 2-/3-syllable) on (a) 320 dpi small phone, (b) 480 dpi flagship, (c) 240 dpi older device. Confirm: words fit on one line per pair, font is reproducibly comparable, no truncation. Run informal A/B with 3–5 internal users: read fingerprint aloud, compare against printed copy, time the comparison and count errors. Pass criterion: <5 seconds average comparison time, zero errors on 3-of-3 reads.

**5. Biometric prompt cold-path latency on low-end devices.** Measure tap-to-prompt-visible on (a) Pixel 6a with cold SystemUI (after `pm trim-caches`), (b) one Go-class device if available. Confirm: cold path <1.5 s, warm path <600 ms. Pass criterion: no perceived "did I tap it?" gap; if cold path exceeds 1.5 s, add a "Securing…" interstitial.

**6. Connected Apps revocation propagation across reinstall.** Enroll Kicks (or a test peer APK) on Kuira Wallet. Tap "Stop sharing" in Wallet → Connected Apps. Confirm: peer AIDL `delete_seed` request is delivered or fails gracefully if peer is offline; peer is removed from Wallet's local trust list; next AIDL bind from the peer triggers re-TOFU. Uninstall peer, re-install, attempt enrollment → must require fresh TOFU + biometric, not silent re-attach. Pass criterion: revocation is locally complete on Wallet side regardless of peer reachability; copy never overstates what was achieved.

**7. Same-app reinstall recovery latency.** On a Pixel 6a-class device: forge sigil, uninstall Kuira app, reinstall from APK, time tap-to-`Active` for the Tier 2 recovery flow. Pass criterion: ≤4 seconds typical, ≤10 seconds worst case on flaky network. If worst case exceeds 10 s, add a "Restoring…" progress state with the same shell as the panel.

These seven gates are the device-testing acceptance bar for Track A. Items 1, 2, and 3 are blocking; 4–7 are blocking if they fail but tunable in-flight if they're marginal.

---

## Updated plan recommendations

Concrete changes to `docs/SIGIL_V3_TRACK_A_PLAN.md` arising from this addendum:

1. **Remove Surface 3 (V2 migration) entirely.** Renumber remaining surfaces in any new revision. Reference V2 only as "prior sigils on testnet can be re-forged."
2. **Surface 1 — correct the biometric prompt count claim.** Forge is 2 prompts (`create()` + harvest `get()`), not 1. Enrollment on App-B is 2 prompts locally (3 total across both apps). Update Surface 1 acceptance criteria accordingly.
3. **Surface 1 — add codec versioning + signer interface (Passport Delta 1 + Delta 3).** Acceptance criteria as paste-ready text in §"Plan deltas" above.
4. **Surface 2 — add `SigilStorageTier` interface and open-enum tier_tag (Passport Delta 2).** Acceptance criteria as paste-ready text in §"Plan deltas" above.
5. **Surface 2 — reserve at least 8 of 16 Block Store entries for future control-plane growth.** Document the 256-byte-per-envelope reservation and the 4.8 KB / 64 KB current utilization.
6. **Surface 4 — bump TOFU fingerprint from 4 to 6 PGP words.** Hard requirement; ship-blocker if not done.
7. **Surface 4 — add `<queries>` declarations for first-party Kuira packages.** Mandatory since Android 11; otherwise `bindService` returns false on API 30+ for un-declared packages.
8. **Surface 4 — add explicit revocation copy spec.** Two-tier model ("Stop sharing" / "Rotate sigil"), honest copy that names what each does and does not achieve. Code-review rule: ban the bare word "Revoke."
9. **Surface 5 — pivot from public beta to closed 10–15 person cohort.** No public testflight in Track A.
10. **Add Open Question #6 (Passport DID stability) with the resolution from this addendum.** Commit to Scenario A; document HKDF info-label and pin test vector at ship time.
11. **Add `DeviceAttestationCapability` probe as a Track A diagnostic.** ~100 LOC, no backend; gives Track B real population data on StrongBox vs TEE.
12. **Rename Track C from "On-chain Sigil Account contract" to "Passport integration."** Update the deferred-scope table row. Add a Track C+1 row for "VC store + selective-disclosure presentation."
13. **Add a manual-test protocol document at `docs/testing/track-a-manual.md`** for the seven device-testing gates above, with per-release-tag screen-recording capture.
14. **Telemetry: ship default-off, opt-in, three closed-enum counters only.** Document the schema in the Data Safety form.

---

## Go / no-go recommendation

**Go**, with the 14 plan amendments above. Aggregate confidence rises from 89% (as-planned) to ≥95% (with amendments). No surface scores below 88% post-amendment.

The three amendments that gate the ≥95% target are non-negotiable: **6-word PGP fingerprint** (Surface 4 grindability), **versioned envelope codec** (Passport Delta 1), and **`SigilStorageTier` interface** (Passport Delta 2). Each is small in implementation cost — codec is a parsing change with the v1 case unchanged on disk; interface is a refactor of three storage call sites; fingerprint is a wordlist parameter bump. Together they save a quarter of post-Passport refactoring and remove the single load-bearing security weakness in the TOFU UX.

Track A should ship as the foundation for everything Kuira does next — same-app reinstall recovery, cross-app sigil sharing, the Kicks/CipherDefense/dApp enrollment story, and the Passport plug-in path that lets Midnight's protocol-native AA and universal DID land without a Kuira-side architectural retrofit. The risks that remain (Play Store review of AIDL services, opt-in telemetry sparsity, Smart Switch idempotency in cross-device restore) are bounded, mitigated, and acceptable for the alpha + testnet scope.

The maintainer's ≥95% confidence bar is met. Commit implementation resources.
