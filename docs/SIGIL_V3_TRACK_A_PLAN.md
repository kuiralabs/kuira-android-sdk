# Sigil V3 — Track A Plan

**Goal:** ship the V3 master-seed-as-data architecture to feature parity
with V2 *plus* cross-app enrollment, with a non-disruptive migration for
every shipped sigil. After Track A, BBoard and Kicks users continue to
work unchanged; the starter (and any future Kuira-built or third-party
app) can join an existing user's sigil via an explicit biometric-on-both-
ends enrollment.

V3 architecture and full justification: see
[`docs/research/SIGIL_PORTABILITY_INVESTIGATION_V3.md`](research/SIGIL_PORTABILITY_INVESTIGATION_V3.md).

---

## What "ready for Track A ship" means — the acceptance gate

A user on the current V2 architecture, with a forged sigil and on-chain
funds, can:

1. **Migrate to V3 without losing access to their wallet.** A single
   user-initiated action ("Add recovery options") completes in two
   biometric taps, costs zero on-chain fees, leaves the wallet address
   and balance unchanged, and is safe to retry if the device dies
   mid-flow.
2. **Install a second Kuira-built app on the same device and enroll it
   to share their sigil.** Two biometric prompts (one on the existing
   app, one on the new app); after consent, both apps see the same DID,
   the same wallet, the same balance. Revoking the new app from the
   existing app's "Connected Apps" screen removes its local seed copy.
3. **Install a third-party Kuira-compatible app and enroll it via a
   TOFU consent path.** Same enrollment UX, plus a one-time first-
   contact screen showing the new app's identity. Repeat enrollments
   skip the first-contact screen.
4. **Uninstall and reinstall an enrolled app.** The same-app same-device
   recovery path (Tier 2 / Block Store) restores the sigil with no
   re-onboarding, identical to V2 behaviour today.
5. **Run an internal beta build with a "V3 disabled" flag and
   transparently fall back to V2.** The flag is the rollback button if
   any unanticipated failure mode surfaces during early production.

If those five round-trip cleanly across the BBoard + Kicks + starter
acceptance set, Track A ships.

Track A explicitly does NOT close: cross-device recovery on a different
Google account, lost-passkey recovery via PIN, fully delegated signing
for agents. Those are Track B and Track C.

---

## Status carried in from V2

| Property | Source | What it lets Track A skip |
|---|---|---|
| Passkey + PRF on Android works reliably | Shipped V2 (BBoard, Kicks) | No new platform-API research; the assertion path is known-good. |
| Block Store backup of per-app encrypted state | Shipped V2 (BBoard, Kicks, starter) | Tier 2 storage is a refactor, not a new platform integration. |
| `SigilSession.forge` + `signIn` + the `SigilStatusPanel` UI surface | Shipped V2 | The user-facing biometric ceremony shell is already proven; Track A reskins the inside. |
| Android Keystore wrapping for local seed material | Shipped V2 | Tier 1 storage inherits the existing hardware-backed wrapping. |
| The existing V2 PRF output for every shipped user | Shipped V2 | **This is the V3 master seed for those users.** No on-chain migration. |

---

## Work surfaces

Decomposed by concern, not by file. Each surface has its own acceptance
criteria; surfaces sequence loosely but mostly land in parallel.

### Surface 1 — Master seed lifecycle

The primitive that V1 and V2 didn't have: a 32-byte master seed that
exists as data, is forged once, persists immutably, and is encrypted
under as many independent unlock keys as the user authorizes.

**Acceptance:**
- A forged sigil produces a master seed that survives uninstall + restore,
  cross-process restart, and PIN change.
- The seed is never on disk in plaintext.
- Forge of a new sigil produces statistically random seeds (smoke test
  for entropy quality).
- Sign-in re-derives the unlock key from the passkey assertion and
  unwraps the persisted seed; no PRF derivation chain assumes
  re-derivability of the seed itself.

### Surface 2 — Tiered storage (Tier 1 + Tier 2)

Two storage tiers, each holding the same envelope encrypted under a
tier-specific HKDF leg of the same PRF, so a tier compromise does not
unlock the other tier.

**Tier 1:** per-app local file, biometric-gated unwrap.
**Tier 2:** Block Store backup of the same envelope under a different
HKDF leg; carries cross-install recovery for the *same* app on the
*same* device.

**Acceptance:**
- A fresh install of the same app on the same device + Google account
  restores the sigil from Tier 2 without re-prompting forge.
- Tier 1 contents are unreadable without a successful PRF assertion;
  Tier 2 contents are unreadable without a successful PRF assertion
  under the Tier-2 HKDF leg.
- Compromise of a Tier 1 ciphertext does not compromise Tier 2 (key
  separation is verified by inspection of the envelope codec).

(Tier 3 — opaque cloud bucket — is Track B.)

### Surface 3 — V2 migration

The non-disruptive path that turns the existing V2 PRF output into the
V3 master seed for every shipped user.

**Acceptance:**
- An existing V2 user, on next intentional security-touching action
  (not on an unsolicited popup), sees a single prompt to "Add recovery
  options."
- After two biometric taps, the user's wallet address, balance, DID,
  and on-chain history are unchanged; their sigil is now backed by a
  V3 envelope.
- The migration is **idempotent on re-entry** — interrupted halfway
  resumes cleanly on next launch.
- A `migrate-only` release window exists before V2 code is removed,
  so users who skip the migration UI still complete it before V2 is
  permanently retired.
- Rollback safety: if a problem surfaces in early production, the
  V3-enabled flag flips off cleanly and users continue on V2.

### Surface 4 — Cross-app enrollment protocol

The mechanism that makes "I have a sigil; let this new app share it"
a two-biometric-tap operation with no shared salt convention.

Two trust tiers in one protocol:
- **Signature-permission fast path** — apps signed by the same cert
  (first-party Kuira suite) enroll without a fingerprint-comparison
  screen.
- **TOFU consent path** — apps signed by different certs get a
  first-contact screen showing the new app's identity; repeat
  enrollments skip the screen.

**Acceptance:**
- A user with an existing Kuira-first-party sigil can launch a newly
  installed first-party app, tap "Use my Kuira sigil," and end up with
  the new app showing their wallet balance — two biometric prompts,
  one on each side, no fingerprint screens.
- A user can do the same for a third-party Kuira-compatible app — same
  flow, plus one first-contact screen identifying the new app.
- The existing app maintains a list of "Connected Apps" (the
  authorized-peers list); the user can revoke a peer's local seed copy
  from this list at any time.
- Enrollment is robust to: the existing app not being installed, the
  user denying on either end, the device going offline mid-handoff,
  the app being killed mid-flow.
- The handoff channel is ephemeral-key encrypted; a malicious app
  intercepting the channel cannot recover the seed.

### Surface 5 — Internal beta + production rollout

The release path that lets V3 reach real users without risk of stranding
funds.

**Acceptance:**
- A "V3 enabled" build flag exists end-to-end (forge, enrollment,
  migration all gated).
- Internal beta cohort (Kuira-team users, opt-in volunteers) runs V3
  for at least one full week with no fund-loss incidents.
- Telemetry surfaces: migration completion rate, enrollment success
  rate, biometric denial rate, Block Store ack timing, any silent
  failure modes.
- A documented kill-switch + rollback procedure exists and has been
  tested on a non-production account.

---

## Sequencing notes

Surfaces 1, 2, and 3 are sequenced first because the migration story
depends on them existing. Surface 4 (enrollment) can be drafted in
parallel since it consumes Surface 1's master seed but its UI work is
independent. Surface 5 is continuous — flag work begins on day one and
hardens through the cycle.

The user-facing milestone order:
1. V2 migration lands behind the flag. Existing users opt in via
   "Add recovery options." No new user-visible behaviour beyond this
   prompt.
2. Cross-app enrollment lands behind the flag. First-party apps
   (BBoard ↔ Kicks ↔ Wallet) can enroll each other.
3. Starter testing uses enrollment to inherit the user's existing
   sigil — closing the test-of-fire loop that this whole investigation
   started from.
4. Internal beta opens.
5. Public rollout: flag flips on by default, V2 enters its
   migrate-only release window.

---

## Open questions

These need answers before code starts; some are decisions, some are
small spikes.

1. **What's the `appScope` story under V3?** V2 introduced an opt-in
   `appScope` for apps that *want* an isolated wallet (a "game wallet"
   vs main wallet). V3's enrollment model makes this default — every
   new app is isolated until explicitly enrolled. Do we still need an
   `appScope` knob for the "I want a SEPARATE wallet within the same
   app" use case? Probably yes for advanced users, but not on the
   Track A critical path.

2. **What identifies a user's "sigil" for enrollment purposes when
   they have more than one?** A user could have a personal sigil and
   a treasury sigil on the same device. The enrollment UI needs a
   chooser when more than one is present.

3. **How aggressive is the mnemonic-export prompt?** V3 accepts
   "forgotten PIN with no mnemonic = funds lost" as a residual risk;
   that's mitigated by forge-time mnemonic display. How visible should
   this be? Required (blocking)? Strongly recommended (skip with
   confirmation)? Optional (Settings only)?

4. **Beta cohort recruitment.** Who runs the internal beta? What's
   the opt-in mechanism? What's the cohort size sufficient to surface
   silent failure modes before public rollout?

5. **Telemetry vs sovereignty.** V3 telemetry would help catch
   regressions but would also add network calls from a wallet app.
   What's the minimum acceptable telemetry surface, and is it
   opt-in?

---

## Out of scope (deferred)

| Surface | Track | Why deferred |
|---|---|---|
| Tier 3 (Cloudflare R2 cloud bucket) | B | Requires its own ops surface; not required for "shared funds across apps on this device" goal. |
| PIN-based recovery flow + UI | B | Needs Tier 3 first. |
| Argon2id PIN stretching + profile versioning | B | Same. |
| Cross-device, cross-account recovery | B | Same. |
| On-chain Sigil Account contract | C | Larger work; addresses delegated agent signing, not consumer cross-app sharing. |
| iOS port of the V3 architecture | future | iOS is committed but separate; iOS will inherit the same primitive once cross-compile spike completes. |

---

## What "done" looks like for Track A

A user with the current V2 architecture, with on-chain funds, who:

- Sees a friendly migration prompt on next intentional security action
- Completes the migration in two taps with their existing biometric
- Installs the kuira-starter-android, opens it, taps "Use my Kuira sigil"
- Sees their existing wallet balance immediately in the starter
- Can deploy + increment the counter contract using their existing funds

…has experienced the entire happy path Track A is designed for. The
starter testing that began this whole investigation closes naturally
once that user-visible flow lands.
