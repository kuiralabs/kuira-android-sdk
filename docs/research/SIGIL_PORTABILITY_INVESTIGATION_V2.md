# Sigil portability v2 — escaping the WebAuthn rpId trap (revised)

*Investigation date: 2026-05-30*
*Status: revised recommendation; supersedes V1*
*Author note: V2 was commissioned because V1 (a) violated the
non-regression constraint by recommending per-app `SEED_SALT` that
would break existing BBoard+Kicks shared-funds behaviour, and (b)
did not consider entire architectural categories. V2 must do better
on both counts.*

## TL;DR

- **Recommended path (different from V1): keep today's shared-`SEED_SALT` behaviour as the invariant, register first-party Kuira apps on a canonical custom-domain rpId (`sigil.kuira.app`) shipped with `<knownSigner>`-gated `assetlinks.json`, and add an explicit opt-in `appScope` knob for the small minority of apps that *want* an isolated wallet.** Cross-app funds keep working out of the box; isolation becomes opt-in instead of a forced regression.
- **What changed since V1:** the per-app `SEED_SALT` recommendation is withdrawn — it broke a shipped product feature in the name of an isolation property that the rpId boundary already provides. V1 mis-diagnosed the salt as the security boundary; the actual boundary is `(rpId, DAL allowlist)` upstream of PRF.
- **V1 was right about:** rpId binding is permanent at the CTAP layer (stress-tested and confirmed); ROR doesn't unbind PRF outputs from rpId; a custom apex domain is required (user-sites on `*.github.io` *are* valid rpIds, contrary to V1, but cross-site reuse still requires an apex the maintainer controls).
- **V1 was wrong about:** the Public Suffix List blocking `*.github.io` rpIds (the bare `github.io` is blocked; `nel349.github.io` is a valid eTLD+1, which is why BBoard+Kicks work today); the framing of per-app salt as a "security" fix (it's a *product* feature flag, not a hardening).
- **V1 didn't seriously consider:** Solana Seed Vault and a Kuira "Sigil Vault" system-app, Android `CredentialProviderService` with custom credential types, EIP-4337 / Compact-native account abstraction, MPC/FROST 2-of-3, zkLogin, mobile-to-mobile WalletConnect/Phantom-style deep links, and `AccountManager + AbstractAccountAuthenticator`. All seven were re-examined; none replaces the recommendation, but two (AccountManager, CredentialProvider custom type) are queued as v2/v3 enablers and one (delegated authority via Compact-native AA) carries over from V1 Track B.
- **Shippable in 4–8 weeks (Track A):** canonical rpId + DAL + optional `appScope`. Shippable in two quarters (Track B, unchanged from V1): delegated-spend authority via a Compact "Sigil Account" contract. The agent-runtime work in #27 *is* this contract.

## What changed since V1

### V1 claims that were verified

| V1 claim | V2 stress-test result |
|---|---|
| WebAuthn rpId scoping is structural and load-bearing, not a configurable knob. | **Confirmed.** Stress-test on the CTAP2 spec, WebAuthn L3, Yubico's hmac-secret deep-dive, and FIDO CXF v1.0 RD shows `credSecret` is per-credential random bytes, and rpId enters via the credential-lookup gate. There is no spec text, draft, or open issue that would let two distinct rpIds derive the same PRF output. The lookup-gate framing in V1 was slightly imprecise (V1 said "rpId is hashed into the HMAC" — it isn't, but the net result is identical). |
| Related Origin Requests does not enable cross-rpId PRF sharing. | **Confirmed.** ROR shipped in WebAuthn L3 (Chrome 128+, Firefox 152). It lets multiple *origins* share one *rpId*; the rpId stays a single value. PRF output is identical across the related origins because they are all the same RP. ROR adds nothing over picking one rpId and listing every app's fingerprint in one `assetlinks.json`. |
| CTAP2 / hmac-secret binds PRF output to `(credSecret, rpId)` with no spec path to change this on a 1–3 year horizon. | **Confirmed.** WG issues #865, #1372, #1665, #1827 are all closed. The dormant successor thread to #1827 (M&A / ccTLD unification) has no draft and no champion. CXF/CXP migration carries `credSecret` inside the credential blob, which makes the per-credential binding harder to change, not easier. Synced passkeys (Corbado Q1-2026 field test, 100% PRF success) confirm `credSecret` is per-credential and travels with the credential — ruling out any "device root key + rpId derivation" alternative model. |
| Block Store is per-package; no `sharedUserId` escape; no ContentProvider surface for the blob itself. | Confirmed; unchanged. |
| Android Keystore aliases are per-UID and non-exportable. | Confirmed; unchanged. |
| `sharedUserId` is deprecated since API 29 and cannot be added to a shipped app. | Confirmed; unchanged. |
| `did:key` is non-rotatable. | Confirmed; unchanged. |
| Hardware-Keystore-only seed (P1), mnemonic-first (P2), decoupled identity (P3), cloud-escrowed seed (P4), and ROR-bet (P5) are unviable as the *primary* architecture. | Confirmed. The killer concerns V1 raised survived adversarial review. P2 (mnemonic) survives as an opt-in *export* escape hatch on top of the shipped passkey flow, unchanged. |

### V1 claims that were wrong or overstated

**1. "Public Suffix List blocks `*.github.io` from being an rpId at all. WebAuthn clients raise `SecurityError`."**
Empirically false. BBoard and Kicks both work today on `nel349.github.io`. The PSL rule blocks the *public suffix itself* from being an rpId; it does **not** block subdomains. `github.io` is forbidden as an rpId; `nel349.github.io` is a valid eTLD+1 and qualifies. Google's own [web.dev RP-ID deep dive](https://web.dev/articles/webauthn-rp-id) explicitly lists `user.github.io` as **VALID**. The V1 paragraph conflated "is on PSL" with "any descendant cannot be rpId" — the spec text only forbids equality with a public suffix.

What V1 *should* have said: the real constraint for cross-app reuse on `*.github.io` is that each `<user>.github.io` is an *isolated rpId silo* with no shared parent the maintainer can authoritatively control. A passkey minted under `nel349.github.io` does not roam to `kuiralabs.github.io` — not because the spec forbids it, but because they are sibling registrable domains. ROR doesn't help here either: ROR requires the rpId to be a registrable domain the publisher controls, and an apex on `github.io` does not exist for anyone. So the *conclusion* (need a custom apex domain) survives; the *reason* changes.

**2. "Track A: per-app `SEED_SALT` is a security fix."**
Mis-diagnosed. The maintainer's objection that this breaks BBoard+Kicks shared funds is correct, and the security justification doesn't survive scrutiny. The isolation boundary that matters has already collapsed *upstream* of `SEED_SALT`: two apps with the same `rpId` and same DAL share the same GPM credential. Either app can call `authenticateWithPrf` with any salt and derive any descendant secret. Per-app salting the PRF leaf doesn't restore isolation; it re-keys a deterministic suffix the attacker can compute equally well.

Per-app salt is a legitimate *product* feature ("this app wants a separate wallet from my main one") but not a security hardening. It should be opt-in per app, not flipped on globally as V1 proposed.

**3. "Six candidate paths were evaluated."**
Six were evaluated; at least seven more architectural categories were missing. They are not obviously inferior — they are untested. Section *V1 claims that were under-explored* enumerates them, and the *Updated candidate paths* section gives each a real score.

### V1 claims that were under-explored

V1 did not seriously examine any of the following. Each is now in scope (one section per path, below):

- **Solana Seed Vault model — system-app + IPC.** A privileged signing service exposed to client apps via Intent + ContentProvider, with hardware-backed key custody. Reference architecture for a "Kuira Sigil Vault."
- **Android `CredentialProviderService` with custom credential type.** Platform-mediated trust boundary instead of bespoke AIDL; uses the same plumbing as passkeys but with our own type string.
- **EIP-4337 / Compact-native account abstraction.** Identity becomes a *contract address*, not a key. Passkey is one of N signers. Cross-app sharing is contract-level, not credential-level.
- **MPC / FROST 2-of-3 threshold Schnorr.** Seed never reconstructed; signing requires a quorum. Vendor comparison: Web3Auth, Privy, Lit Protocol, Magic, Dynamic.
- **zkLogin (Sui).** OAuth JWT → ZK proof → on-chain identity. Cross-device portable, but trust-shifted to OAuth provider + salt service.
- **Mobile-to-mobile WalletConnect / Phantom deep links / Coinbase Mobile Wallet Protocol.** Dedicated "Kuira Wallet" app, dApps reach it via deep link or relay. The Solana/Ethereum mobile pattern.
- **`AccountManager` + `AbstractAccountAuthenticator` + signature-protected ContentProvider.** Pre-Credential-Manager, never deprecated; Google's first-party apps still use it. Account-type is an opaque cross-app handle; no rpId binding.
- **Apple Credential Provider extension + Keychain Access Groups.** iOS-side reference for what cross-app credential sharing looks like under a single Team ID.

## The non-regression constraint

**INVARIANT.** Any recommended architecture must preserve or improve on today's behaviour: when two first-party Kuira apps share an rpId and a DAL, the user's funds, DID, and Block Store backup remain accessible from both apps without re-onboarding.

This is a hard constraint, not a preference. V1 was rejected because its primary recommendation (per-app `SEED_SALT`) violated this invariant in pursuit of an isolation property the rpId boundary already provided. V2 treats the invariant as a check that *every candidate path must pass before any other score matters*. Paths that strictly worsen it are eliminated unless they offer extraordinary, near-term-shippable replacement value for the lost behaviour.

Practical reading:

1. If the user installs Kuira Wallet, BBoard, and Kicks today (all on `nel349.github.io`), funds are shared. **V2 must keep that working.**
2. If the user installs a third Kuira-built app under the *canonical* rpId, funds should also be shared by default. **V2 should make this work — the V1 starter case.**
3. If a user or app explicitly wants per-app wallet isolation (a "game wallet" vs "main wallet"), V2 must offer an opt-in mechanism — but it cannot be the default.
4. Third-party non-Kuira apps that don't sign with a trusted cert and are not on the DAL get a separate rpId (their own) by virtue of *not being on the canonical DAL at all*. They never could share funds with first-party apps under the current model; that is unchanged.

The salt is not the boundary. The `(rpId, DAL allowlist)` tuple is.

## Updated candidate paths

V1's six paths plus V2's seven new ones. Each section includes: one-liner, updated score table (1–5; 8th column is "Non-regression compatible?"), killer concerns from stress-tests, and what it would look like in Kuira code.

Scoring criteria are unchanged from V1:
1. **Portability** — does it let the same user use the same identity across multiple apps?
2. **Sovereignty** — does it avoid concentrating power in a third party or single domain?
3. **UX cost** — onboarding friction, install-funnel drop-off, per-action overhead.
4. **Security** — blast radius, key custody, attestation, recovery model.
5. **Migration cost** — does it strand existing BBoard+Kicks users?
6. **Engineering effort** — calendar weeks; lower = less work.
7. **Long-term viability** — survives a 3-year horizon, vendor changes, spec churn.
8. **Non-regression compatible?** — preserves shared-fund behaviour for first-party apps. **Hard pass/fail.**

### Path 1 — Hardware-Keystore-backed seed (passkey eliminated)

V1 verdict UNVIABLE. Stress-tests did not change this. Loses the no-seed-at-rest invariant; portability claim secretly depends on Drive REST; breaks every existing DID. **Non-regression: FAIL** (forced DID rotation).

### Path 2 — Mnemonic-first, biometric-as-convenience

V1 verdict: unviable as primary, viable as opt-in export. **Unchanged.** Non-regression: PASS as opt-in export (doesn't change defaults); FAIL as primary (forces full re-onboarding).

### Path 3 — Decoupled identity + portable wallet mnemonic

V1 verdict UNVIABLE. Unchanged. **Non-regression: FAIL** (the "shared seed via IPC" mechanism doesn't exist on Android without a `SharedSeedHostService` that doesn't exist).

### Path 4 — Cloud-escrowed seed (Drive / iCloud)

V1 verdict UNVIABLE. Unchanged. **Non-regression: FAIL** (cloud-escrow would require new key-derivation story that doesn't preserve existing PRF-derived seeds).

### Path 5 — WebAuthn Related Origin Requests / "future cross-rpId spec"

V1 verdict UNVIABLE. Stress-tests confirmed ROR doesn't unbind PRF from rpId. **Unchanged.** Non-regression: VACUOUSLY PASS (doesn't change anything; doesn't help either).

### Path 6 — Canonical Kuira rpId convention (V1's recommendation, revised)

| Criterion | Score |
|---|---|
| Portability | 4 |
| Sovereignty | 1 |
| UX cost | 4 |
| Security | 2 (shared salt, first-party only via DAL) |
| Migration cost | 2 |
| Engineering effort | 4 |
| Long-term viability | 2 |
| **Non-regression compatible?** | **PASS — and improves on it** (extends shared-funds to canonical-rpId apps) |

**Killer concerns from stress-tests, addressed.**

- *V1 worried about "shared salt → one compromise drains everyone."* True, but this is the **already-shipped behaviour** that the maintainer asked us to preserve. The blast radius is the union of first-party Kuira apps under one DAL — the same as today's `nel349.github.io`. The mitigation is **DAL discipline + `<knownSigner>` gating**, not salt domain separation. Apps that want their own pool opt into `appScope`.
- *Domain centralization.* Real and unavoidable for any apex-domain-based approach. Mitigations carry over from V1: registrar 2FA, multi-person access, monitored auto-renewal, runbook. Public commitment that Track B (delegated authority) is the long-term answer.
- *Doesn't help GPM-account captivity.* Correct; no rpId change does. Tracked separately as Kicks wishlist #28.

**What it would look like in Kuira code.**

```kotlin
// core:identity / SeedDeriver.kt — unchanged constants, NEW optional scope
val SEED_SALT: ByteArray = sha256("kuira:seed:v1")
val SIGIL_SALT: ByteArray = sha256("kuira:sigil:v1")
val BACKUP_SALT: ByteArray = sha256("kuira:backup:v1")

fun seedSaltFor(appScope: String?): ByteArray =
    if (appScope == null) SEED_SALT
    else sha256("kuira:seed:v1:" + appScope)
```

```kotlin
// core:identity / PasskeyConfig.kt — NEW optional appScope
data class PasskeyConfig(
    val rpId: String,
    val rpName: String,
    val timeoutMs: Long = 60_000,
    /**
     * Opt-in app-scoped salt. When null (default), the app shares funds
     * with every other first-party app on the same canonical rpId.
     * When set, derives an isolated seed for this app only.
     *
     * Stable forever once set — change = full re-onboard + fund loss.
     */
    val appScope: String? = null,
)
```

```kotlin
// First-party app DI — canonical rpId, default null appScope
val passkeyConfig = PasskeyConfig(
    rpId = "sigil.kuira.app",
    rpName = "Kuira",
    // appScope = null — shared with all canonical-rpId apps
)

// Opt-in isolation example (a hypothetical "Kuira Games" app)
val gamesPasskeyConfig = PasskeyConfig(
    rpId = "sigil.kuira.app",
    rpName = "Kuira Games",
    appScope = "games.v1", // separate wallet from main
)
```

**Verdict (revised). VIABLE.** This is the recommended Track A. The change from V1: drop the forced per-app `SEED_SALT` regression; add `appScope` as an opt-in feature flag instead.

### Path 7 — Sigil Vault: dedicated system-app + AIDL (Solana Seed Vault analogue) — NEW

**One-liner.** A separate "Kuira Sigil Vault" Android app holds the master seed in StrongBox-wrapped Keystore. Other Kuira apps bind a signature-protected AIDL service to request signatures. Mirrors Solana Mobile's Seed Vault architecture, minus the OEM-only Trusted UI and trustedvm.

| Criterion | Score |
|---|---|
| Portability | 5 |
| Sovereignty | 3 |
| UX cost | 2 (install funnel hurts) |
| Security | 3 (no TEE display = consent prompts can be overlaid by root) |
| Migration cost | 1 (every existing user re-onboards into Vault) |
| Engineering effort | 2 (AIDL + allowlist + consent UI + StrongBox seed wrap; ~5–6 weeks) |
| Long-term viability | 4 |
| **Non-regression compatible?** | **FAIL as v1 architecture** (BBoard+Kicks users would need to migrate their funded sigils into a Vault that doesn't yet exist; nothing connects today's wallet to the Vault's wallet) |

**Killer concerns from stress-test.**

- **Install funnel kills v1.** Asking a BBoard-curious user to install *two* apps before posting once is a non-starter for a pre-PMF ecosystem. Phantom and MetaMask both treat "install the wallet first" as their #1 growth blocker. The current passkey-PRF in-flow forge is the *only* reason Kicks-as-validator is feasible on the World Cup timeline.
- **Doesn't escape the rpId trap; relocates it.** The Vault's master credential is itself a passkey with its own rpId, its own GPM-account coupling, its own recovery story. We move the cross-device portability problem from N apps to 1 vault — real consolidation, but paid for with install friction + suite-wide blast radius + a migration nobody asked for.
- **Suite-wide blast radius.** Vault compromise = every Kuira app drained. Today's per-rpId / per-DAL model bounds the blast radius to apps on one DAL.
- **No Trusted UI on stock Android.** SAGA's "display unplugs from Android and reconnects to invisible device" is unattainable without OEM partnership. Without it, a rooted Android can overlay the consent prompt. Vault is a meaningful jump for cross-app key reuse but does not approach SAGA-grade security.

**When does Vault make sense?** Post-suite consolidation (when Kuira has ≥3 first-party surfaces and the per-app sigil juggling becomes a real pain), third-party dApp enablement *after* SDK maturity, hardware/enterprise scenarios, and as a host process for the agent runtime (#27). It arrives *via* the agent runtime, not as a wallet replacement.

**Verdict.** DEFER. Re-evaluate when the agent-runtime work needs a process to live in, or when Kuira has ≥3 first-party surfaces and a real third-party dApp asking for it. Not the v1 architecture.

### Path 8 — Android `CredentialProviderService` with custom credential type — NEW

**One-liner.** Kuira Wallet registers a `CredentialProviderService` with a custom capability `app.kuira.SIGNING_REQUEST`. Other Kuira apps call `CredentialManager.getCredential(...)` with a matching `GetCustomCredentialOption`; the system picker routes to Kuira, biometric-gates, returns a signed blob.

| Criterion | Score |
|---|---|
| Portability | 4 |
| Sovereignty | 3 |
| UX cost | 3 (system picker breaks "in-dApp" feel) |
| Security | 4 (OS-enforced trust boundary, `CallingAppInfo.signingInfo` verifiable) |
| Migration cost | 2 |
| Engineering effort | 3 (~3–4 weeks; mostly known territory from existing passkey work) |
| Long-term viability | 4 (Google's strategic direction for Android identity) |
| **Non-regression compatible?** | **PASS** if Kuira Wallet derives the same seed from the same canonical-rpId passkey it does today; the Vault-like surface is overlay, not replacement |

**Killer concerns from stress-test.**

- **System picker UX.** `CustomCredential` renders with generic chrome — "Use saved credential from Kuira" — no branded "Sign with Sigil" CTA. Inherit Google's UX choices forever, can't differentiate.
- **API 34+ adoption.** Half the user base falls back to whatever the secondary path is, which dilutes the investment case for it as *primary*.
- **Custom credentials don't appear in browser AutoFill, don't sync via GPM, and `androidx.credentials` is alpha-churn-prone.** The trust boundary is your signing-cert allowlist, not the platform.

**Why it's still attractive.** Unlike a bespoke AIDL Vault, this is platform-mediated — no process to lifecycle-manage, no IPC contract to version forever, no separate install (the provider app *is* the wallet, just exposing one extra service entry). It composes with the existing CredentialProviderService passkey work.

**Verdict.** RESEARCH PROTOTYPE. Worth a 1–2 week spike behind the canonical rpId rollout to validate the routing, picker UX, and `CallingAppInfo` verification path. Ship as a Tier-D enabler when API 34 crosses ~85% install base (2027–2028 horizon). Don't bet Track A on it.

### Path 9 — EIP-4337 / Compact-native account abstraction — NEW

**One-liner.** Identity = a Compact "Sigil Account" contract address, not a key. Passkey is one of N signers registered in the contract. Cross-app sharing = each app/device registers its own signer under the same contract address.

| Criterion | Score |
|---|---|
| Portability | 5 |
| Sovereignty | 4 |
| UX cost | 3 (each new app = one `add_signer` proof) |
| Security | 3 (contract bugs are catastrophic; circuit soundness is the new attack surface) |
| Migration cost | 1 (existing UTXO funds must move into the contract; on-chain linkage leaks the correlation Midnight is supposed to prevent) |
| Engineering effort | 1 (Compact contract + WebAuthn P-256 verification in-circuit + recovery flow; ~2 quarters) |
| Long-term viability | 5 |
| **Non-regression compatible?** | **FAIL as v1 architecture** (forced fund migration with on-chain correlation leak); **PASS as v2 architecture** when shipped alongside the existing model |

**Killer concerns from stress-test.**

- **EIP-4337 substrate doesn't exist on Midnight.** No UserOperation mempool, no Bundler, no EntryPoint. We'd be lobbying the protocol team. *But* — Midnight skips all of 4337's protocol surgery because its proof-based auth already *is* "whatever the ZK proof attests to." We can build the AA pattern in **contract code**, not in a new mempool. This is a fit, not a mismatch.
- **Contract security pedigree.** Safe, Argent, Wallet.js have all shipped exploitable bugs. Compact contract security is a new discipline; one buggy `add_signer` circuit = catastrophic.
- **Migration leaks privacy.** Existing UTXOs moved into a contract address publish a tx linking old and new identities — the exact correlation Midnight is supposed to prevent. Worth a separate design study on whether a shielded-spend witness can avoid the link.

**Why it's still the right long-term answer.** Identity = contract address (Bech32m, like today's wallet). Each app onboarding = one `add_signer` proof from an already-trusted device. Loss = guardian-driven `recover` after timelock. This is the Sigil pattern made native: the *contract* is the sigil, passkeys are interchangeable seals. It's also the natural home for delegated agent keys (#27), per-circuit spend policies, and treasury/social recovery.

**Verdict.** TRACK B. Carry over V1's Track B intent but reframe it as a "Sigil Account" Compact contract (the EIP-4337 spirit applied to Midnight's proof-native model) rather than just a `SpendAuthorization` record. Same calendar (~2 quarters). The agent-runtime work in #27 *is* this contract.

### Path 10 — MPC / FROST 2-of-3 threshold Schnorr — NEW

**One-liner.** Replace the single-seed model with FROST-secp256k1 threshold Schnorr. Three shares: `{device-biometric, Kuira-coordinator, user-recovery}`. Any two sign. Vendor reference: Web3Auth, Privy, Lit Protocol, Magic, Dynamic.

| Criterion | Score |
|---|---|
| Portability | 4 (Kuira owns one share, can lift it across devices) |
| Sovereignty | 2 (Kuira-coordinator is functional custody — refusal = freeze) |
| UX cost | 3 |
| Security | 3 (no single share suffices; coordinator sees signing metadata) |
| Migration cost | 1 (rebuild key management, recovery, core:crypto) |
| Engineering effort | 1 (FROST library integration + coordinator service + recovery flow + ops; ~2+ quarters) |
| Long-term viability | 3 |
| **Non-regression compatible?** | **FAIL** (forced re-keying; existing PRF-derived seeds don't map onto FROST shares) |

**Killer concerns from stress-test.**

- **Inverts Kuira's privacy thesis.** Even a faithful MPC ships a third party a precise log of every signing request: timestamp, frequency, often message hash. Midnight's value prop is that *no one* sees this. Adding an MPC co-signer re-introduces the surveillance surface Midnight was built to eliminate.
- **"Non-custodial" MPC is marketing framing.** The coordinator can refuse to participate. A refusing co-signer is functionally a freeze. Web3Auth and FROST partially escape via the "device + recovery combines locally" escape hatch; Privy/Magic/Dynamic do not.
- **Vendor lock-in or self-operated SLA business.** Running a 99.99% co-signer is an ops business, not an SDK.
- **Conflicts with [memo `feedback_mpc_vm_principle`]** (don't roll our own MPC; use generic MPC-VM). FROST is a special-purpose protocol with battle-tested libraries (ZF FROST 2.1.0, NCC + Least Authority audited), so the principle arguably doesn't bind here — but the MEMORY entry exists because the team's prior position is sceptical.

**Where MPC does fit.** Narrow features that are *inherently* multi-party: treasury sigil, agent-delegated signing with revocation (#27), PvP escrow in Kicks. For those, FROST isn't "key management" — it's the contract.

**Verdict.** REJECT as primary sigil keying. RETAIN as a candidate primitive for inherently-multi-party features (treasury, agent delegation, escrow), built on an MPC-VM per the existing principle.

### Path 11 — zkLogin (Sui pattern) — NEW

**One-liner.** OAuth JWT → Groth16 ZK proof → Midnight address. Salt server reconstructs `(sub → salt)` mapping in AWS Nitro Enclaves.

| Criterion | Score |
|---|---|
| Portability | 4 (any device with the OAuth account can re-derive) |
| Sovereignty | 1 (OAuth provider + salt service operator are both hard dependencies) |
| UX cost | 4 (familiar OAuth flow) |
| Security | 2 (OAuth ban = wallet loss; salt service single point of failure) |
| Migration cost | 1 (forced re-keying; circuit/verifier doesn't exist on Midnight) |
| Engineering effort | 1 (~6–12 months: circuit on Halo2, BN254 precompile or port, JWK oracle, salt infra) |
| Long-term viability | 3 |
| **Non-regression compatible?** | **FAIL** (no path from existing PRF-derived seed to a zkLogin-derived address) |

**Killer concerns from stress-test.**

- **No circuit + verifier on Midnight.** Sui uses Groth16 on BN254; Midnight uses Halo2/PLONK on BLS12-381. Either port the ~1M-constraint zkLogin circuit to Halo2 (months + new trusted setup) or ship a BN254 pairing precompile. Multi-quarter Midnight Foundation lift.
- **Salt = single point of failure.** Mysten's enclave architecture trust-shifts from device to Mysten Labs. Salt loss = wallet loss for *every user*. Sui's own docs admit changing browser/device with localStorage salt loses access "even when using the same JWT."
- **Cross-app on Android isn't free either.** Wallet bound to `(iss, aud, sub, salt)`. Different `aud` per app → different addresses. To get cross-app, all apps share an OAuth client ID — operationally fragile.
- **OAuth provider ban = wallet gone.** Sui's official recovery guidance is "use native multisig and a backup signer" — conceding that zkLogin alone is not a complete custody story.

**Verdict.** REJECT as primary sigil. TRACK as an *optional* recovery path layered under Sigil — a "I lost my phone and have no backup, recover via Google" tier — once the underlying Midnight circuit and JWK-oracle infrastructure exists. Estimate: 6–12 month workstream that re-centralizes recoverability onto an OAuth provider + a salt service. Worth tracking, not adopting.

### Path 12 — Mobile-to-mobile (WalletConnect / Phantom / MWP) — NEW

**One-liner.** Separate "Kuira Wallet" Android app holds keys. dApp apps reach it via verified App Link (Phantom/MWP style) or WalletConnect relay. dApps hold no seed.

| Criterion | Score |
|---|---|
| Portability | 5 (one wallet, many dApps; user can swap to hardware wallet later) |
| Sovereignty | 5 (Kuira Wallet is the only key custodian) |
| UX cost | 1 (modal app-switch per signature: 3–6s on mid-tier Android, plus context loss) |
| Security | 4 (well-trodden protocols; deep-link hijacking solvable via verified App Links) |
| Migration cost | 2 (today's apps continue to work; "external wallet" is additional) |
| Engineering effort | 3 (deep-link wire format + per-call consent UI + session token cache; ~3–4 weeks for the basic flow) |
| Long-term viability | 4 |
| **Non-regression compatible?** | **PASS** as a secondary/Tier-C connector overlaid on the existing in-process flow; **FAIL** if it replaces the in-process flow |

**Killer concerns from stress-test.**

- **App-switch friction compounds.** Every BBoard interaction = BBoard → OS animation → Kuira Wallet cold-start → biometric → sign → OS animation → BBoard resume. Kuira's thesis is "Sigil feels like part of the dApp." WalletConnect makes Sigil feel like a notarization trip to a different building.
- **Install funnel cliff.** "Install the wallet first" is Phantom/MetaMask's #1 growth blocker. Kuira's edge over MetaMask is in-flow sigil forge.
- **Wrong audience for v1.** Phantom can tolerate the friction because Solana users self-select as crypto-natives; Kuira's pitch is "Web2-tier UX for ZK," the opposite audience.

**Why we still want it.** It's the only pattern that gives users *wallet portability* — they can swap Kuira Wallet for a hardware wallet, a multisig, or a future-better wallet without re-onboarding into every dApp. For the 5% power-user / institutional / treasury-management case, this is non-negotiable. It's also the only pattern that lets non-Kuira wallets reach Kuira dApps, which matters for ecosystem credibility.

**Verdict.** TIER-C SECONDARY. Build a verified-App-Link-based connector (Phantom shape, but using verified App Links not raw schemes) once the SDK has a stable v1 surface. Don't market it. Don't default to it. Keep the wire format relay-portable for the day a desktop dApp wants phone-as-signer.

### Path 13 — `AccountManager` + `AbstractAccountAuthenticator` — NEW

**One-liner.** Kuira Wallet registers a custom account type `com.kuiralabs.sigil` via `AbstractAccountAuthenticator`. Other apps call `AccountManager.getAccountsByType("com.kuiralabs.sigil")` to discover, then `getAuthToken(...)` for scoped signing tokens. Signing happens via signature-protected ContentProvider keyed by token.

| Criterion | Score |
|---|---|
| Portability | 5 (Settings → Accounts shows the sigil; system account picker on multi-sigil; no GPM coupling) |
| Sovereignty | 4 |
| UX cost | 2 (still requires Wallet install; but consent UX is system-native) |
| Security | 4 (UID + signing-cert verification via `Binder.getCallingUid` + `PackageManager.getPackagesForUid`; `PackageManagerService` rejects type-squatting) |
| Migration cost | 2 (existing in-process flow continues; AccountManager is an additional surface) |
| Engineering effort | 4 (authenticator service + provider + token cache + consent UI; ~1 week) |
| Long-term viability | 4 (stable since API 5, never deprecated) |
| **Non-regression compatible?** | **PASS** (purely additive; doesn't change existing PRF-derived seeds) |

**Killer concerns from stress-test.**

- **Same install-funnel problem as Path 7.** "Install the wallet app first" is the binding UX constraint for any out-of-process pattern.
- **Different mental model from passkeys.** Users who already authenticated via biometric will see a second "Add Account" screen in Settings, which is confusing.

**Killer feature for Kuira specifically.** **No GPM account binding.** Settings → Accounts entry is local to the device. Two devices on the same Google account can hold *different* sigils — directly unblocking the Kicks PvP testing constraint flagged in [`project_sigil_gpm_account_constraint`]. This is the *only* pattern surveyed that fixes that constraint.

**Verdict.** TIER-B FOLLOW-UP. Worth a ~1-week prototype after Track A ships, specifically to unblock Kicks PvP testing. If the prototype validates, ship as an alternative onboarding path ("Add Sigil to Device" via Settings) without removing the in-process flow.

### Path 14 — Apple Credential Provider extension + Keychain Access Groups (iOS reference) — NEW

**One-liner.** iOS-side reference. `keychain-access-groups` (Team-ID scoped, code-signing-enforced) is the closest analogue to a clean cross-app credential primitive. Apple's Credential Provider extension API is rigid (fixed credential types), but `keychain-access-groups` is exactly what Android lacks.

Not a candidate for Android v1, but informs the iOS port: when Kuira ships iOS (per [`project_sdk_platform_roadmap`]), the cross-app story under one Team ID is essentially free via keychain groups. Document this as the iOS architectural prescription.

## New comparison matrix

| Path | Port | Sov | UX | Sec | Mig | Eng | LTV | **Non-regression** |
|---|---|---|---|---|---|---|---|---|
| P1 Keystore only | 2 | 2 | 2 | 3 | 1 | 1 | 2 | **FAIL** |
| P2 Mnemonic primary | 2 | 4 | 1 | 2 | 2 | 4 | 2 | **FAIL** (as primary) |
| P2' Mnemonic opt-in export | n/a | 5 | 4 | 3 | 5 | 4 | 4 | **PASS** |
| P3 Decoupled | 3 | 4 | 2 | 2 | 2 | 2 | 2 | **FAIL** |
| P4 Cloud-escrowed | 3 | 1 | 2 | 1 | 2 | 3 | 2 | **FAIL** |
| P5 ROR / future spec | 2 | 1 | 3 | 1 | 2 | 2 | 1 | n/a |
| **P6 Canonical rpId (revised, shared salt + opt-in `appScope`)** | **4** | 1 | **4** | 3 | 2 | **4** | 2 | **PASS+** (extends shared funds to canonical apps) |
| P7 Sigil Vault | 5 | 3 | 2 | 3 | 1 | 2 | 4 | **FAIL** (as v1) |
| P8 CredentialProvider custom type | 4 | 3 | 3 | 4 | 2 | 3 | 4 | **PASS** |
| P9 Compact-native AA (Sigil Account contract) | 5 | 4 | 3 | 3 | 1 | 1 | 5 | **FAIL** (as v1); **PASS** (as v2 alongside) |
| P10 FROST 2-of-3 | 4 | 2 | 3 | 3 | 1 | 1 | 3 | **FAIL** |
| P11 zkLogin | 4 | 1 | 4 | 2 | 1 | 1 | 3 | **FAIL** |
| P12 Mobile-to-mobile | 5 | 5 | 1 | 4 | 2 | 3 | 4 | **PASS** (as Tier-C overlay) |
| P13 AccountManager | 5 | 4 | 2 | 4 | 2 | 4 | 4 | **PASS** |
| P14 iOS Keychain group | n/a | n/a | n/a | n/a | n/a | n/a | n/a | iOS reference |

**Reading the matrix.**

- Only four paths PASS the non-regression invariant in a v1 timeframe: **P6 (canonical rpId + opt-in `appScope`)**, **P2' (mnemonic export)**, **P8 (CredentialProvider custom type)**, and **P13 (AccountManager)**. P12 passes as a Tier-C overlay.
- Of those, only **P6 directly solves "use my BBoard funds in the starter"** in 4–8 weeks.
- **P9 (Compact-native AA)** is the durable answer — but it doesn't pass the non-regression check in a v1 window. It carries V1's Track B intent forward.
- The unranked-for-Android **P14 (iOS keychain groups)** is the iOS architectural prescription when that platform ships.

## Updated recommendation

**Ship Track A immediately. Ship Track B over two quarters. Stack P2', P8, P13 as additive enablers behind the canonical rpId.**

### Track A — Canonical Kuira rpId, **shared salt preserved**, opt-in `appScope` (4–8 weeks)

The single substantive change from V1 Track A: **keep `SEED_SALT`, `SIGIL_SALT`, and `BACKUP_SALT` exactly as they are today.** Add an *optional* `appScope: String?` field to `PasskeyConfig` that, when set, mixes into the salt. Default `null` = today's behaviour — shared seed across every app on the canonical rpId + DAL.

Concretely:

1. **Register `sigil.kuira.app`** (or chosen apex) on a custom domain Kuira-org commits to operating with a 99.99% SLA. Custom domain is required because:
   - PSL forbids `kuira.io` if it ever lands on the list (it won't, but defence-in-depth).
   - User-sites on `*.github.io` work as rpIds (V1 was wrong about this — see *V1 claims that were wrong*) but each `<user>.github.io` is a sibling silo with no shared parent — can't be cross-app reuse target.
   - ROR requires a registrable domain the publisher controls; an apex on `github.io` exists for no-one.
2. **Publish `https://sigil.kuira.app/.well-known/assetlinks.json`** listing every first-party app's package + SHA-256 signing fingerprint. Two-engineer review per entry. Staged on a pre-prod subdomain before merge to prod. Cert-rotation runbook.
3. **Use `<knownSigner>` (API 31+) for first-party suite signing flexibility.** Each app keeps its own signing key while still being recognized by the DAL — borrowed from the AccountManager investigation's recommendation pattern.
4. **`SEED_SALT` / `SIGIL_SALT` / `BACKUP_SALT` unchanged.** Shared-funds invariant preserved.
5. **Add `PasskeyConfig.appScope: String?` (default null).** When set, the per-call salt becomes `SHA-256("kuira:<role>:v1:" || appScope)`. Document: "Use only if your app needs an isolated wallet. Once set, immutable. Changing it = forced re-onboard + fund loss."
6. **First-party app DI sets `appScope = null`.** BBoard, Kicks, starter, Kuira Wallet all derive the same seed under the canonical rpId.
7. **User-visible disclosure at forge** (carried over from V1): *"This sigil is shared with all Kuira-built apps under the canonical Kuira rpId."*
8. **Public sunset commitment** (carried over from V1): Track A is transitional; Track B's Compact-native account abstraction supersedes it.
9. **Add the missing test the V1 synthesis implicitly assumed exists** (per stress-test finding): a unit test that pins "two `PasskeyManager`s with same rpId produce identical entropy" — mockable at the `authenticateWithPrf` boundary. The cross-app same-seed invariant is currently load-bearing and untested.

**Does this satisfy the maintainer's literal request "use my BBoard funds in the starter"?** Yes, under three conditions:
- Both apps are on the canonical rpId `sigil.kuira.app`.
- Both apps' fingerprints are in the canonical DAL.
- Neither app sets `appScope` (default null).

Today's BBoard + Kicks on `nel349.github.io` continues to work (legacy rpId path). Existing users keep their funds. New first-party apps adopt the canonical rpId from day one and inherit the shared sigil.

**Why is this different from V1's recommendation, and why is it defensible this time?**

V1 said: canonical rpId + **per-app `SEED_SALT` enforced**. The "per-app salt" was framed as a security fix (preventing one compromised partner from draining everyone). The maintainer correctly identified this would break shipped product behaviour.

V2 says: canonical rpId + **shared salt preserved + `appScope` as opt-in product feature**. The reframing rests on three findings from the stress-tests:
- The PRF salt is *not* the security boundary. The `(rpId, DAL)` tuple is. Two apps with the same rpId + DAL already share the same GPM credential, so either can call `authenticateWithPrf` with any salt and derive any descendant secret. Per-app salting only re-keys the deterministic suffix; an attacker who can invoke PRF can invoke it with the per-app salt equally well.
- The legitimate motivation for per-app salt is *product-level* ("game wallet vs main wallet"), not security. Product features should be opt-in, not forced.
- V1's shared-funds regression was a real regression of a shipped product feature. The stress-test confirmed it's "technically accurate" that per-app salt would break BBoard+Kicks shared funds — there's no mechanical way to migrate seeds across a salt change.

V2 also adds the test that pins the cross-app same-seed invariant. V1 reasoned about it but didn't ship a guardrail.

### Track A.1 — `assetlinks.json` discipline (concurrent with Track A)

Carry over V1's mitigations, restated for clarity:
- First-party apps only.
- Every entry reviewed by two Kuira-org engineers.
- Staged on a pre-prod subdomain before merge to prod.
- Fingerprint-verified against Play Console output.
- Cert-rotation runbook (multi-person, monitored, dead-man's switch).
- Domain registrar 2FA, multi-person access, monitored auto-renewal.

### Track A.2 — Optional mnemonic export (P2', concurrent with Track A)

Ship the V1 opt-in mnemonic-export escape hatch alongside Track A. Power users get a sovereignty answer when they ask about Kuira-org dependency on the canonical rpId.

### Track B — "Sigil Account" Compact contract (~2 quarters)

Carry V1's Track B forward, **reframed** from "delegated-spend authority via `SpendAuthorization` records" to "Sigil Account Compact contract" (per Path 9). Same calendar, same kickoff. The contract is the durable answer to cross-app fund portability without seed sharing:

- **Identity = contract address.** Bech32m-encoded, like today's wallet address.
- **Signers = registered validators.** Each device/app's passkey or session key is one validator. `add_signer`, `revoke_signer`, `recover` are circuits.
- **Per-signer policy.** Spend caps (asset, per-tx, per-epoch), allowed callees, nonce-based replay protection. The Compact circuit proves "this pubkey is in the signer set AND signed this op AND the scoped policy allows it" without revealing *which* signer.
- **Migration = on-chain transfer.** Existing UTXOs move into the contract address via a normal `sendShielded`. This *does* publish a linkage tx — flagged as a separate design study to determine whether a shielded-spend witness can avoid the link.
- **Agent runtime (#27) rides this contract.** Delegated agent keys = `add_signer` with a scoped policy. The agent runtime is the first non-passkey signer type the contract supports.

### Track C — Secondary connectors (post-Track-A)

- **P13 AccountManager prototype** (1 week, after Track A ships). Validate the "Settings → Accounts → Add Kuira Sigil" surface unblocks Kicks PvP testing by escaping GPM-account captivity (`project_sigil_gpm_account_constraint`). Ship as an alternative onboarding path if it validates.
- **P8 CredentialProvider custom-type spike** (1–2 weeks). Validate routing, picker UX, `CallingAppInfo.signingInfo` verification. Park as a Tier-D enabler.
- **P12 verified-App-Link connector** (3–4 weeks, when SDK has stable v1 surface). External-wallet escape hatch. Tier-C; don't market.

### Track D — Defer

- **P7 Sigil Vault.** Re-evaluate when agent-runtime work needs a host process, or Kuira has ≥3 first-party surfaces and a real third-party dApp asking for it.
- **P10 FROST.** Retain as a candidate primitive for inherently-multi-party features (treasury, agent delegation, escrow). Not the sigil's keying story.
- **P11 zkLogin.** Track as optional recovery tier once Midnight has a BN254 verifier or a Halo2-port circuit.
- **P14 iOS keychain groups.** iOS architectural prescription when the iOS port lands.

### What ships in 4–8 weeks vs 6 months

**4–8 weeks (Track A + A.1 + A.2):**
- Canonical rpId `sigil.kuira.app` live with DAL.
- All first-party apps updated to canonical `PasskeyConfig`.
- `appScope` field shipped (default null).
- Migration UI for legacy-rpId users (re-forge under canonical, transfer funds via on-chain tx).
- Mnemonic export escape hatch.
- Unit test pinning cross-app same-seed invariant.

**6 months (+ Track B + Track C):**
- Sigil Account Compact contract on testnet, then mainnet.
- Agent-runtime (#27) rides the contract.
- AccountManager prototype validated; ships as alternative onboarding if it unblocks PvP.
- CredentialProvider custom-type prototype parked or shipped depending on API 34 adoption curve.
- External-wallet connector (P12) shipped as Tier-C.

### Acknowledged tradeoffs

- **Track A creates a Kuira-org operational dependency on `sigil.kuira.app`.** Real centralization. Mitigated by treating it as transitional, committing publicly to Track B, and shipping P2' (mnemonic export) for power users.
- **Shared seed across canonical-rpId apps means one compromise drains the suite.** This is the existing shipped behaviour. Mitigation is DAL discipline + `<knownSigner>` + the public sunset commitment, not salt-domain separation. Apps that want isolation set `appScope`.
- **Forced-migration risk for users currently on `nel349.github.io`.** The on-chain transfer window during Phase 2 migration is the only fund-at-risk window. Use the existing tip-aware Dust sync to confirm before deleting the legacy seed.
- **GPM-account captivity (`project_sigil_gpm_account_constraint`) is unchanged by Track A.** P13 (AccountManager prototype in Track C) is the only surveyed path that fixes this.
- **Track B's on-chain migration leaks the legacy↔new linkage.** Acknowledge openly; separate design study to determine if a shielded-spend witness can avoid the link.
- **Custom-domain ops is a Severity-1 surface.** Domain lapse, seizure, or compromise = every Kuira app loses passkey access. The runbook (registrar 2FA, multi-person access, monitored auto-renewal, tabletop exercise) is non-negotiable.

## Migration plan

**Phase 0 — Decide and announce (week 1).**
- Maintainer approves the recommendation.
- Register `sigil.kuira.app` (or chosen apex). Set up TLS, monitoring, registrar 2FA.
- Publish architecture record + public roadmap note explaining: (a) shared seed across canonical-rpId apps is *preserved*, (b) `appScope` is the opt-in isolation knob, (c) Track B supersedes Track A on a 2-quarter horizon.

**Phase 1 — Plumbing (weeks 2–4).**
- Add `appScope: String?` parameter to `PasskeyConfig`. Default null. Document immutability.
- Add `seedSaltFor(appScope: String?)`, `sigilSaltFor(...)`, `backupSaltFor(...)` helpers in `SeedDeriver` / `AppStateBackup` / `PrfKeyDeriver`. When `appScope == null`, return the existing constants unchanged.
- Add `KuiraSigilRpId = "sigil.kuira.app"` constant to a shared SDK module.
- Ship `assetlinks.json` to `https://sigil.kuira.app/.well-known/` with all first-party app fingerprints. Two-engineer review.
- **Add the missing test.** Unit test in `core:identity` (or a new `cross-app` test target) that instantiates two `PasskeyManager`s with the same `rpId` and asserts identical PRF output flows through to identical entropy. Mock at `authenticateWithPrf`.

**Phase 2 — Per-app DI rollout (weeks 4–5).**
- Update Kuira Wallet, BBoard, Kicks, starter `IdentityConfigModule.RP_ID` from `nel349.github.io` to `sigil.kuira.app`. `appScope = null`.
- Each app independently re-forges under the canonical rpId at first launch.

**Phase 3 — In-app migration UI (weeks 5–7).**
- On first launch under the new rpId, detect a sigil on the legacy rpId via `SigilStateStore`.
- Display: *"Your Kuira sigil is moving to a unified identity across all Kuira apps. You'll keep your current funds — they'll be transferred to your new sigil."*
- Flow: forge new sigil under canonical rpId → derive new wallet seed (same salt, different `credSecret` because rpId changed → different seed) → send all funds from legacy wallet to new wallet via standard on-chain transfer → mark legacy sigil deprecated in `SigilStateStore`.
- For apps with on-chain identity references (BBoard posts, Kicks games): publish a one-time **DID rotation attestation** signed by the legacy DID asserting "my new DID is X." Contracts that look up history can chain through the attestation.
- Funds at risk only during the on-chain transfer window. Use existing tip-aware Dust sync to confirm before deleting legacy seed.

**Phase 4 — Starter + SDK docs (week 8).**
- Update `kuira-starter-android` to bake in `sigil.kuira.app`.
- Document: how to register a first-party app, what `appScope` means, public commitment that Track B replaces the DAL allowlist.
- Add diagnostics in `SigilPanelViewModel` to surface "this sigil is on canonical rpId, shared across N installed Kuira apps."

**Phase 5 — Track B kickoff (week 8 onward).**
- Spec the Sigil Account Compact contract.
- Design study: can the legacy→Sigil-Account UTXO migration use a shielded-spend witness to avoid the linkage tx?
- Coordinate with Midnight on any required runtime primitives.
- Ship in stages: contract deploy → `add_signer`/`revoke_signer` circuits → on-chain enforcement → `recover` flow → agent-runtime integration (#27).

**Phase 6 — Track C side-quests (parallel with Track B).**
- 1-week AccountManager prototype. If it unblocks PvP testing, ship as alternative onboarding.
- 1–2-week CredentialProvider custom-type prototype. Park as Tier-D.
- 3–4-week verified-App-Link external-wallet connector. Tier-C.

**Rollback plan.** If `sigil.kuira.app` is compromised mid-rollout, users on Phase 3 retain their legacy sigil until they explicitly migrate. Pause the migration screen via remote config and let users keep operating on per-app rpIds while the domain is recovered or replaced. No funds lost from a domain compromise — only future forges fail until DAL is verified again.

## Open questions

1. **`sigil.kuira.app` vs another apex.** Same as V1 Q1. Brand/ops call.
2. **Should `appScope` mix in the app's signing-cert SHA-256 rather than a free-form string?** Pro: prevents accidental scope collision and reflects the trust boundary. Con: signing-cert rotation forces a re-forge. Recommend free-form string with strong documentation about immutability, and let signing-cert-based variants be a Track C experiment.
3. **DID rotation attestation format.** Same as V1 Q3. One-week design spike.
4. **iOS parity.** Path 14 (Keychain Access Groups) is the iOS architectural prescription; needs verification when iOS port lands.
5. **Sigil Account on-chain primitives.** Does Midnight contract runtime expose what we need for `add_signer`-style validator membership and scoped-spend policy verification in-circuit? Coordinate with Midnight team early.
6. **Migration-tx linkage avoidance.** Can a shielded-spend witness move funds from a legacy wallet into a Sigil Account without publishing a correlation? Design study before Track B mainnet.
7. **User research on shared-sigil mental model.** Same as V1 Q6.
8. **Domain-loss tabletop.** Same as V1 Q7. Non-negotiable before Phase 1 ships.
9. **GPM-account captivity.** Does P13 AccountManager prototype actually unblock PvP testing? Validate before committing to a Track C ship.
10. **`<knownSigner>` adoption.** First-party apps currently share signing cert (per [memo `feedback_use_github_handle`] context); migrating to per-app keys with `<knownSigner>` is a separate workstream worth scoping early.

## References

(Carries forward V1's WebAuthn / Android / Apple / Solana / wallet sources; adds V2's stress-test and new-category sources.)

**WebAuthn / FIDO specs and discussions (verified in V2 stress-tests).**
- [W3C WebAuthn Level 3](https://www.w3.org/TR/webauthn-3/)
- [CTAP 2.1 spec](https://fidoalliance.org/specs/fido-v2.1-ps-20210615/fido-client-to-authenticator-protocol-v2.1-ps-20210615.html)
- [WebAuthn L3 PRF](https://w3c.github.io/webauthn/#prf-extension)
- [Yubico CTAP2 HMAC-Secret Deep Dive](https://developers.yubico.com/WebAuthn/Concepts/PRF_Extension/CTAP2_HMAC_Secret_Deep_Dive.html)
- [Yubico hmac-secret reference](https://docs.yubico.com/yesdk/users-manual/application-fido2/hmac-secret.html)
- [Corbado PRF 2026 field data](https://www.corbado.com/blog/passkeys-prf-webauthn)
- [Related Origin Requests explainer (W3C wiki)](https://github.com/w3c/webauthn/wiki/Explainer:-Related-origin-requests)
- [passkeys.dev ROR](https://passkeys.dev/docs/advanced/related-origins/)
- [web.dev ROR](https://web.dev/articles/webauthn-related-origin-requests)
- [web.dev RP ID deep dive](https://web.dev/articles/webauthn-rp-id) — explicit `user.github.io` VALID example
- [Public Suffix List raw](https://publicsuffix.org/list/public_suffix_list.dat)
- [Corbado: RP ID & Passkeys](https://www.corbado.com/blog/webauthn-relying-party-id-rpid-passkeys)
- [FIDO CXF v1.0 RD](https://fidoalliance.org/specs/cx/cxf-v1.0-rd-20250313.html)
- [Corbado CXP/CXF analysis](https://www.corbado.com/blog/credential-exchange-protocol-cxp-credential-exchange-format-cxf)
- W3C WebAuthn issues [#865](https://github.com/w3c/webauthn/issues/865), [#1372](https://github.com/w3c/webauthn/issues/1372), [#1665](https://github.com/w3c/webauthn/issues/1665), [#1827](https://github.com/w3c/webauthn/issues/1827) — all closed; no spec path to cross-rpId PRF reuse

**Android platform — credential / IPC / identity.**
- [Credential Manager — Credential Provider](https://developer.android.com/identity/sign-in/credential-provider)
- [`androidx.credentials.CustomCredential`](https://developer.android.com/reference/androidx/credentials/CustomCredential)
- [`androidx.credentials` releases](https://developer.android.com/jetpack/androidx/releases/credentials)
- [`AccountManager`](https://developer.android.com/reference/android/accounts/AccountManager)
- [`AbstractAccountAuthenticator`](https://developer.android.com/reference/android/accounts/AbstractAccountAuthenticator)
- [Creating a content provider (permissions)](https://developer.android.com/guide/topics/providers/content-provider-creating)
- [AOSP: Signature permission allowlist](https://source.android.com/docs/core/permissions/signature-permission-allowlist)
- [Block Store](https://developer.android.com/identity/block-store)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Unsafe use of deep links](https://developer.android.com/privacy-and-security/risks/unsafe-use-of-deeplinks)
- [OWASP MASTG-TEST-0028 Deep Links](https://mas.owasp.org/MASTG/tests/android/MASVS-PLATFORM/MASTG-TEST-0028/)

**Solana Seed Vault.**
- [Seed Vault Integration Guide](https://github.com/solana-mobile/seed-vault-sdk/blob/main/docs/integration_guide.md)
- [`WalletContractV1.java`](https://github.com/solana-mobile/seed-vault-sdk/blob/main/seedvault/src/main/java/com/solanamobile/seedvault/WalletContractV1.java)
- [Seed Vault Javadoc Constants](https://solana-mobile.github.io/seed-vault-sdk/seedvault/javadoc/constant-values.html)
- [Solana Mobile Docs — Seed Vault](https://docs.solanamobile.com/developers/seed-vault)
- [Saga of Saga — Part 2 (Offside)](https://blog.offside.io/p/saga-of-saga-part-2-digging-into-solana)

**Mobile-to-mobile wallet protocols.**
- [WalletConnect Pairing URI spec (ERC-1328)](https://specs.walletconnect.com/2.0/specs/clients/core/pairing/pairing-uri)
- [WalletConnect Android Mobile Linking](https://docs.walletconnect.network/wallet-sdk/android/mobile-linking)
- [Phantom Deeplinks: Provider Methods](https://docs.phantom.com/phantom-deeplinks/provider-methods)
- [Phantom Deeplinks: Encryption](https://docs.phantom.com/phantom-deeplinks/encryption)
- [Coinbase Mobile Wallet Protocol Overview](https://mobilewalletprotocol.github.io/wallet-mobile-sdk/docs/client-sdk/mobile-sdk-overview/)
- [MetaMask SDK](https://docs.metamask.io/sdk/)

**Account abstraction.**
- [EIP-4337](https://eips.ethereum.org/EIPS/eip-4337)
- [ERC-7579: Minimal Modular Smart Accounts](https://eips.ethereum.org/EIPS/eip-7579)
- [Safe WebAuthn 4337 module README](https://github.com/safe-global/safe-modules/blob/main/modules/passkey/contracts/4337/README.md)
- [Biconomy PasskeyValidator](https://docs.biconomy.io/modules/validators/passkeyValidator/)
- [Coinbase Smart Wallet passkeys](https://help.coinbase.com/en/wallet/getting-started/smart-wallet-passkeys)
- [Coinbase smart-wallet GitHub](https://github.com/coinbase/smart-wallet)
- [Corbado — Smart Wallets and Passkeys](https://www.corbado.com/blog/smart-wallets-passkeys)
- [Midnight Docs — Smart contract security (proof-based auth)](https://docs.midnight.network/compact/smart-contract-security)
- [Midnight Docs — Writing a contract](https://docs.midnight.network/compact/writing)
- [Midnight Docs — Zswap](https://docs.midnight.network/concepts/how-midnight-works/zswap)
- [Midnight Docs — Compact stdlib](https://docs.midnight.network/compact/standard-library/exports)

**MPC.**
- [Web3Auth tKey v2 Architecture](https://hackmd.io/@torus/Hyv8HjO8i)
- [Web3Auth SSS Architecture](https://web3auth.io/docs/infrastructure/sss-architecture)
- [Privy Security Architecture](https://docs.privy.io/security/wallet-infrastructure/architecture)
- [Privy SSS deep-dive](https://privy.io/blog/shamir-secret-sharing-deep-dive)
- [Lit Protocol PKP Overview](https://developer.litprotocol.com/user-wallets/pkps/overview)
- [Lit Protocol v3 release](https://spark.litprotocol.com/lit-v3-is-live-confidential-compute-and-key-management-in-one-api/)
- [Dynamic Wallet Infrastructure](https://www.dynamic.xyz/features/wallet-infrastructure)
- [RFC 9591 — FROST](https://datatracker.ietf.org/doc/rfc9591/)
- [ZF FROST Book](https://frost.zfnd.org/)
- [ZF FROST Audit](https://www.zfnd.org/blog/frost-audit)
- [Dfns Givre](https://www.dfns.co/article/a-frost-library-called-givre)

**zkLogin.**
- [What is zkLogin? — Sui Docs](https://docs.sui.io/concepts/cryptography/zklogin)
- [zkLogin: Privacy-Preserving Blockchain Authentication (arXiv 2401.11735)](https://arxiv.org/html/2401.11735v2)
- [Dive into zkLogin's Salt Server Architecture](https://blog.sui.io/zklogin-salt-server-architecture/)
- [Mysten Labs zkLogin SDK](https://sdk.mystenlabs.com/sui/zklogin)

**Apple platform (iOS reference).**
- [ASCredentialProviderViewController](https://developer.apple.com/documentation/authenticationservices/ascredentialproviderviewcontroller)
- [Sharing access to keychain items](https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps)
- [passkeys.dev — iOS](https://passkeys.dev/docs/reference/ios/)
- [Hanko — iCloud passkeys with WebAuthn](https://www.hanko.io/blog/how-to-support-apple-icloud-passkeys-with-webauthn)

**Internal Kuira references (this repo).**
- `core/identity/.../passkey/PasskeyManager.kt`
- `core/identity/.../passkey/PasskeyConfig.kt` (to add `appScope`)
- `core/identity/.../sigil/Ed25519PrfSigilProvider.kt`
- `core/identity/.../sigil/SigilStateStore.kt`
- `core/identity/.../backup/SeedDeriver.kt` (preserve constants; add `seedSaltFor`)
- `core/identity/.../backup/AppStateBackup.kt` (preserve `BACKUP_SALT`; add `backupSaltFor`)
- `core/identity/.../backup/PrfKeyDeriver.kt`
- `core/identity/.../backup/BlockStoreBackupStorage.kt`
- `core/identity/.../auth/AuthorizationStore.kt`
- `core/auth/.../SeedVault.kt`
- `sdk/wallet-seed/.../WalletSeedSource.kt`
- `sdk/wallet-seed/.../SigilSession.kt`
- `app/.../di/IdentityConfigModule.kt` (canonical rpId)
- `examples/bboard/app/.../di/IdentityConfigModule.kt` (canonical rpId)
- `examples/midnight-kicks/app/.../di/IdentityConfigModule.kt` (canonical rpId)
- `core/identity/src/test/.../backup/SeedDeriverTest.kt` (add cross-app same-seed pinning test)

**V2 stress-tests and architectural investigations (this commission).**
- `psl-github-io-claim` — V1 PSL claim refuted; `user.github.io` is a valid eTLD+1 rpId
- `salt-regression-in-code` — confirms per-app `SEED_SALT` would break BBoard+Kicks shared funds; recommends `appScope` opt-in instead
- `ctap2-rpid-prf-binding` — V1 binding claim survives; no spec path on 1–3 year horizon
- `solana-seed-vault-deep` — system-app + IPC architectural reference
- `system-app-aidl-ipc` — Kuira Sigil Vault sketch and trade-offs
- `zk-login-sui` — circuit cost, salt-server centralization, 6–12 month workstream
- `account-abstraction-eip4337` — Compact-native AA sketch via Sigil Account contract
- `mpc-key-management` — Web3Auth, Privy, Lit, FROST 2-of-3 evaluation
- `walletconnect-deeplink-android` — verified App Link connector as Tier-C
- `apple-credential-provider-pattern` — iOS Keychain Access Groups as iOS architectural prescription; Android `CustomCredential` extensibility
- `android-accountmanager-contentprovider` — `AccountManager` + signature ContentProvider as GPM-captivity escape
- `stress-trust-tier-framing` — refutes V1's trust-tier framing as a stable security boundary
- `stress-system-app-pattern` — Sigil Vault is post-suite consolidation, not v1
- `stress-aa-and-mpc` — both refuted as v1 primary; AA retained as Track B, MPC retained narrowly
- `stress-walletconnect-and-credprovider` — both useful as secondary/research tracks, not primary
