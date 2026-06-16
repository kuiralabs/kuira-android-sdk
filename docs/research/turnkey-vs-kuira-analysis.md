# Turnkey (tkhq) vs Kuira — competitive analysis

**Date:** 2026-06-16 · **Basis:** cloned + read `github.com/tkhq/kotlin-sdk` (source, not marketing) + Turnkey security docs.

---

## TL;DR — they're different layers, not competitors

- **Turnkey** = key-management / custody **infrastructure**. Blockchain signing keys live **server-side in AWS Nitro secure enclaves**; the client holds only an **auth key** (and HPKE bundles for import/export). Signing is a remote **activity**, executed in the enclave.
- **Kuira** = self-custodial **wallet + identity** with **on-device keys** (`seed = PRF(passkey, salt)`, Keystore-backed) and **ZK transaction privacy** (Midnight shielded).

**On privacy/self-custody, Kuira wins structurally** — the key never leaves the device, transactions are ZK-shielded, and no third party sits in the signing path. **On managed-custody / attestation / multi-chain / recovery-at-scale, Turnkey wins** — that's what it's built for. Adopting Turnkey for Kuira's *core* wallet would gut the self-custody + ZK thesis; its ideas are worth borrowing for the *agent-key* and *recovery* edges.

---

## What Turnkey actually is (code-verified)

| Aspect | Finding (with code reference) |
|---|---|
| Signing keys | **Server-side, in the enclave.** `decryptExportBundle` exists for export; there is **no local tx-signing path**. |
| Signing | A remote **activity**: `ACTIVITY_TYPE_SIGN_RAW_PAYLOAD` → `v1SignRawPayloadIntent` → `http/TurnkeyClient.kt`, executed in the enclave. |
| Local crypto | Only the **auth keypair** (`crypto.generateP256KeyPair`, stored in `AndroidKeyStore` via `stamper/.../SecureStore.kt`) + HPKE bundle encrypt/decrypt (`encryptWalletToBundle`, `decryptExportBundle`, `decryptCredentialBundle`). |
| "Keys never leave the client" (README) | Refers to **import/export bundles** (HPKE end-to-end to the attested enclave), *not* signing-key-on-device. |
| "Non-custodial" | Safety-deposit-box: Turnkey holds the box (cloud enclave), your credential unlocks; Turnkey can't see plaintext keys or independently authorize. |
| Privacy | **Key isolation only.** No transaction-level privacy (chain-agnostic, transparent chains). Every signing request flows through Turnkey's infra. |
| Auth | passkeys (Android Credential Manager), API-key stamps, OAuth (e.g. Google), OTP (email/SMS). |
| Multi-chain | Broad `ADDRESS_FORMAT_*`: ETH, SOLANA, BITCOIN, COSMOS, APTOS, SUI, TRON, XRP, XLM, TON, DOGE, SEI, SPARK… |
| Policy | `policyEvaluation` / `consensus` / `condition` types + root-quorum. |
| Recovery | `InitUserEmailRecovery`, `RecoverUser`, `createSubOrganization`, `sessionExpir`. |

**The crisp distinction:** both use Android Keystore — Turnkey stores the **auth key** (the money-key is in their cloud enclave); Kuira stores the **seed itself** (the money-key, signing on-device).

---

## Things to TAKE from them

1. **Verifiable-security story → roadmap #40 (privacy-neutral spine) + #41 (hardware attestation).** Turnkey's strongest asset: anyone can *cryptographically verify* the enclave runs the expected code. Kuira's security rests on the device secure element + passkey — strong but **not externally verifiable**. Kuira's answer is a *composite* (no enclave to attest → make every layer verifiable). Full design in [§ Verifiable-security story](#verifiable-security-story--how-it-works-on-kuira-roadmap-40--41) below.
2. **Policy / quorum engine → roadmap #42 (extends #27).** Turnkey's `policyEvaluation`/`consensus`/`condition` + root-quorum is a mature "scoped, multi-party authorization" model. Apply it to #27's `KeyAuthorization` (scope + expiry + ECDSA-signed) for the agent SDK / delegated keys. Borrow the policy *language*, NOT the TEE — Kuira's principle is a real MPC VM (MP-SPDZ).
3. **HPKE export-to-ephemeral-key hygiene — LOW relevance (no roadmap item).** Their `decryptExportBundle` encrypts key material to a client ephemeral P-256 key + verifies the enclave's quorum signature. But Kuira **rederives** the seed from the passkey (no escrow, no key transit), so there's nothing to export-encrypt. Reference only IF Kuira ever adds cloud key escrow / cross-device key transfer.
4. **Multi-auth onramps (OAuth/OTP) — note, not a roadmap item.** Turnkey offers OAuth/OTP alongside passkeys. For a **self-custody** wallet this tensions with the thesis: a non-passkey onramp implies a custodial/server intermediate (the seed must come from the passkey PRF). Kuira's single-biometric passkey forge already *is* the onramp. Revisit only if a custodial-onramp tier is ever wanted.
5. **Layered SDK packaging — validation, not a change.** `crypto / stamper / http / types / passkey / sdk-kotlin` mirrors `compact-engine → midnight-sdk → wallet-runtime → dapp-ui`. Confirms the existing layering.

---

## Things to CRITICIZE — Kuira's advantage

1. **Custody.** Keys + every signing request live in Turnkey's cloud (a third party in the path). Kuira = **true self-custody** (on-device, nobody else touches the key — not even an attested enclave operator).
2. **Privacy.** Turnkey sees all signing metadata and offers **zero on-chain privacy** (transparent chains). Kuira = **no central party + ZK shielded** (amounts/parties hidden). This is the headline edge.
3. **Availability / censorship.** Turnkey signing **requires their API up** — a downtime + censorship vector. Kuira **signs offline on-device**.
4. **Honeypot.** Turnkey's enclaves concentrate *many* users' keys → a high-value target (isolated, but still one target). Kuira's keys are **distributed across users' devices** → no honeypot.
5. **Trust surface.** Turnkey = trust AWS Nitro + Turnkey's ops (attestation mitigates). Kuira = trust the user's *own* hardware + passkey.
6. **"Non-custodial" framing.** Fair to call it "*they-can't-touch-it* custody," not "*you-hold-it* custody." Kuira is the latter.

**Where Turnkey genuinely beats Kuira (be honest):** externally-verifiable security (attestation), policy/quorum + recovery at scale, multi-chain breadth, no dependence on per-device secure-element quality. These are custody-ops strengths, not privacy.

---

## Verifiable-security story — how it works on Kuira (roadmap #40 + #41)

**Reframe:** Turnkey has ONE thing to attest (a remote enclave). Kuira has no server enclave — so instead of one attestation, make *every layer independently verifiable*. The composite has no single trusted party — more in the self-custody spirit, and arguably stronger.

**The catch (unique to a privacy wallet):** several device-attestation mechanisms (Play Integrity, hardware key attestation, WebAuthn attestation) bind to a device / Google account and can **de-anonymize the user**. Turnkey attests its OWN infra, so it has no such cost. So Kuira leads with the **privacy-neutral** pillars (prove the *system*, not the *user*) and treats hardware attestation as **opt-in / local self-check**, never a mandatory device beacon.

### Privacy-neutral pillars — the spine (roadmap #40)
1. **Reproducible builds + binary transparency** — anyone rebuilds the source to the identical APK hash; publish hashes to an append-only log (Sigstore/Rekor). Proves *"the store build = the audited source — no backdoor."*
2. **Open + third-party-audited security core** — PRF seed derivation, SeedVault wrap + wipe-after-use, Schnorr/BIP-39/BIP-32/bech32m, the JNI/FFI boundary; report published.
3. **ZK is verifiable by construction** — the unfair advantage Turnkey lacks. Every Midnight tx is a proof anyone can verify without the witness, checked on-chain. Make the *artifacts* verifiable too: reproducible circuit compilation + published verifier keys (proving keys provably match the audited Compact source).
4. **Published, precise threat model** — exactly what's protected, what isn't, under which assumptions.

### Hardware attestation — powerful, privacy-gated (roadmap #41)
5. **Android Key Attestation** — the *direct* Turnkey analog. `setAttestationChallenge` on the SeedVault wrapping key → a hardware-signed cert chain (Google attestation root) proving the key is **StrongBox/TEE-backed, non-exportable, biometric-gated**. The on-device equivalent of "keys never leave the enclave," verifiable by the user/auditor — not on our word.
6. **WebAuthn authenticator attestation** — proves the passkey (the seed *root*) is genuine hardware, not a software fake.
7. **Play Integrity** — defensive only (warn on a rooted/unlocked bootloader via the verified-boot signal).
   → All three must be **local self-checks / opt-in auditor proofs**, never a per-tx server beacon, to protect anonymity.

**The narrative it buys:**
> *Turnkey: "trust our enclave's attestation." Kuira: "don't trust us — verify. Your key is sealed in your own phone's secure chip (hardware-signed proof), the app is the audited open source (rebuild it, check the hash), and every transaction is a ZK proof anyone can check on-chain."*

Distributed, user-owned verifiability vs one centralized attestation — without putting keys in anyone's cloud. **First steps (low lift):** threat-model doc + reproducible-build pipeline + a Key-Attestation self-check surface. Audit + reproducible-circuit pipeline are funded/eng milestones.

## Strategic positioning

- **Narrative:** *"Turnkey gives you managed keys in their cloud; Kuira gives you self-custody + privacy on your device."* Kuira's edge is exactly what Turnkey structurally can't offer.
- **Could Kuira use Turnkey?** For the **core wallet — no** (moves keys to a third-party cloud, adds a dependency, no ZK benefit). For a future **agent-key / delegated-signing tier (#27)**, their policy + attestation model is a *reference*, but Kuira would build with an MPC VM, not a TEE.
- **Net:** the spike validates the differentiator. Keep self-custody + ZK as the moat; the level-ups borrowed from Turnkey are tracked as **roadmap #40 (verifiable-security spine) + #41 (hardware-attestation self-check) + #42 (policy/condition engine, extends #27)**. HPKE export-hygiene and OAuth/OTP onramps were evaluated and *dropped* as roadmap items — they don't fit a passkey-rederived, self-custodial model.
