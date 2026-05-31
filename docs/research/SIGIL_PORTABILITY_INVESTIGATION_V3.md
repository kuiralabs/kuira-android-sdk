# Sigil portability v3 — PRF as unlock, seed as data

*Investigation date: 2026-05-30*
*Status: revised recommendation; supersedes V2*
*Primitive shift: PRF is an unlock mechanism, not a derivation. Inspired by Signal SVR2 + Dashlane production deployment.*

---

## TL;DR

- **Make the master seed a first-class piece of data, not a function output.** Forge generates 32 random bytes once, persists them, and never re-derives them. PRF becomes an *unlock key* that wraps the seed — like Dashlane wrapping `MachineGeneratedMP`, like Bitwarden wrapping a per-credential RSA key, like Signal SVR2 wrapping the master key under a stretched PIN. This is the architectural pivot V1 and V2 both missed.
- **One seed, N unlock keys.** Each app (Kuira host, BBoard, future dApps) holds its own unlock key derived from its own PRF salt. Cross-app portability is **explicit enrollment with biometric on both ends**, not implicit through a shared salt. Cross-device portability is **GPM passkey sync of the host app's unlock key** plus a PIN-based recovery path.
- **V2's shared `SEED_SALT` constraint goes away.** Apps no longer need to coordinate salt constants forever — each app has its own salt, its own unlock key, and (after explicit enrollment) shares the seed by *re-encrypting it under the peer's unlock key*. This was V2's central limitation that no salt-pinning scheme could escape.
- **Recovery is PIN + passkey + opaque cloud blob**, structurally identical to the [yackermann SVR2 proposal](https://github.com/signalapp/SecureValueRecovery2/issues/4). Argon2id-stretched PIN feeds a PRF salt; the PRF output keys an AES-GCM blob in a Kuira-operated R2 bucket; the blob's location is a deterministic HMAC of the credId. No enclave required for V3 — the passkey + Argon2id is the rate limiter — with a clean upgrade path to OPRF + HSM if/when threat model demands.
- **Storage is tiered: Tier 1 per-app local file, Tier 2 Block Store (already shipped, free same-app reinstall), Tier 3 R2 bucket (cross-device, cross-account).** Each tier holds the same envelope encrypted under a tier-specific HKDF leg of the same PRF, so cross-tier replay is structurally impossible.
- **V2 migration is non-disruptive and cheap: adopt the existing V2 PRF output as the V3 master seed.** Same passkey, same wallet, same address, two biometric taps, no on-chain transfer, no funds at risk. The V2 derivation property (PRF output = seed) becomes a V3 design feature (PRF output = the seed we already had).
- **Ship in three tracks. Track A (4-8 weeks):** master seed + Tier 1/2 + enrollment protocol + V2 migration. **Track B (8-12 weeks):** Tier 3 cloud bucket + PIN recovery. **Track C (further out):** on-chain "Sigil Account" contract for fully delegated signing. V2's Track B reframed.

---

## The architectural pivot

V1 and V2 both treated the master seed as a *function of inputs*: `seed = PRF(passkey, salt)`. V1 wanted the salt to be per-app (`SEED_SALT_<app>`), V2 wanted the salt to be shared (`SEED_SALT_KUIRA`). Both committed the same category error.

If the seed is a function, then *changing the inputs changes the seed*. That single constraint is the source of every painful tradeoff V1 and V2 had:

- **V1's "regression"** wasn't really a regression at all in a vacuum — it was a regression *because V2 had already shipped a particular salt convention, and changing it changes the seed, and changing the seed loses the funds*. The salt becomes load-bearing forever.
- **V2's "Kuira-org operates a critical-path domain forever"** problem was the same constraint in a different costume. Once `rpId = kuira.app`, the rpId is part of the input to PRF, so changing the rpId changes the seed, so Kuira owns `kuira.app` forever — and every "Kuira-compatible" app is downstream of that domain.

The pivot:

> **The master seed is data, not a derivation. PRF is an *unlock key* for the seed, not the seed itself.**

When the seed is data, none of this matters. The seed lives in storage. PRF unwraps storage. Different unlock keys (one per rpId, one per device, one per PIN) can all unwrap the same stored seed. Adding a new authority is "encrypt the seed under one more unlock key"; revoking is "delete an unlock key wrapper"; recovery is "produce the right unlock key from inputs the user can reproduce."

This is exactly how every production PRF-based vault on the market works:

- **Dashlane** stores a `MachineGeneratedMP` per account, server-side, **PRF-wrapped per registered authenticator**. The PRF output unwraps MGMP; MGMP unwraps the vault. ([Dashlane support](https://support.dashlane.com/hc/en-us/articles/32877433567634-4-Credential-security-in-detail))
- **Bitwarden** stores `EncryptionKey(PrivateKey)` per credential — an RSA private key wrapped by the PRF-derived AES key. The RSA layer wraps the `UserSymmetricKey`, which wraps the vault. ([Bitwarden contributing docs](https://contributing.bitwarden.com/architecture/deep-dives/passkeys/implementations/relying-party/prf/))
- **1Password** uses passkey as auth, separate per-device key for unwrap, and Account Unlock Key as the portable vault secret. ([1Password Security Design](https://agilebits.github.io/security-design/passkeySSO.html))
- **Signal SVR2** keeps a master key blob in SGX, gated by an Argon2id-stretched PIN; the seed is *data* in the enclave, the PIN derives the *access key*. ([SVR2 README](https://github.com/signalapp/SecureValueRecovery2))
- **WhatsApp E2EE backups** generate a 256-bit random key client-side, then optionally wrap it under a password-derived HSM-released key. ([WhatsApp whitepaper](https://www.whatsapp.com/security/WhatsApp_Security_Encrypted_Backups_Whitepaper.pdf))

In none of these systems is the vault key a function of the authenticator. Every system that puts itself in that corner has had to retreat (see e.g. Apple's iCloud Keychain escrow rebuild, every "smart contract wallet" that learned the hard way that key-rotation has to be possible).

V3 adopts the same primitive that five different production systems converged on, and lets the rest of the architecture follow.

---

## Verified platform foundations

V3 stands on four platform claims, each verified independently in Phase 1.

### Foundation 1: PRF with per-assertion salts works on Android

The Android Credential Manager (`androidx.credentials`) passes WebAuthn JSON through to GPM. PRF is a JSON extension under `publicKey.extensions.prf.eval.first`, and the salt is **supplied per assertion** — not pinned at registration. The platform prepends `"WebAuthn PRF\0"` to the salt before HMAC for domain separation, but otherwise it is a clean per-call PRF.

Concretely:

- **Floor for full PRF:** Android 14 + GPM + Chrome ≥130 (browser path). Native Credential Manager path with PRF-on-create works on API 28+ via `androidx.credentials 1.3.0-alpha04` (current stable line 1.6.0).
- **GPM** enables PRF by default; no per-credential opt-in.
- **`eval.second` is supported** in a single ceremony — but only as the *credential rotation* primitive, not as a free second PRF output (corrected in adversarial review C1; the recovery spec uses two-call or single-call-with-public-salt alternatives).
- **Third-party providers (1Password, Bitwarden Android app)** are NOT guaranteed to support PRF; production code must probe or require GPM.

The SVR2-style "per-session PIN-derived salt" pattern is therefore compatible with Android's API surface, which is what V3 needs.

Sources: [Yubico PRF Developer Guide](https://developers.yubico.com/WebAuthn/Concepts/PRF_Extension/Developers_Guide_to_PRF.html), [Corbado 2026 PRF article](https://www.corbado.com/blog/passkeys-prf-webauthn), [androidx.credentials release notes](https://developer.android.com/jetpack/androidx/releases/credentials), [Passkey PRF Example](https://www.passkeyprf.com/).

### Foundation 2: credId is stable across GPM sync and same-account restore

The WebAuthn L3 spec defines credentialId as **immutable for the credential's lifetime**, generated either randomly (≥100 bits entropy) or as an authenticator-encrypted wrapper. GPM E2EE-syncs the credential blob (rpId + userHandle + public key + credId + wrapped private key) bit-identically across every device signed into the same Google account. iCloud Keychain works the same way.

This is what makes `recovery_index = HMAC(idk, HASH(credId))` work as a stable cross-device lookup key: Phone B running recovery with the GPM-synced passkey computes the *same* credId and therefore the *same* `recovery_index` as the original Phone A that wrote the blob.

What can break credId stability:

- **Re-registration** always produces a new credId. The V3 UX must aggressively use `excludeCredentials` and a clear "Recover, don't re-forge" gating to prevent accidental re-creation.
- **Multiple credentials per (user, rpId) on GPM** — V3 must handle this case via either a discoverable-cred enumeration on recovery, or a last-4-bytes credId hint stored alongside.
- **Apple Passwords behavior is different** — Apple treats (rpId, account) as having exactly one passkey; adding a second replaces the first. Affects iOS V3 only, irrelevant for Android-first launch.
- **Security keys (USB/BLE)** use encrypted-wrapper credIds that never sync; V3 treats these sigils as device-bound, mnemonic-recovery only.
- **Sync disabled** by user — same fate as security keys.

GPM-synced platform authenticators cover ~95% of consumer Android passkey flows. V3 targets this happy path with mnemonic fallback for the rest.

Sources: [WebAuthn L3 §6.1](https://www.w3.org/TR/webauthn-3/#credential-id), [Corbado on credId](https://www.corbado.com/blog/webauthn-user-id-userhandle), [Google blog Sept 2024](https://blog.google/innovation-and-ai/technology/safety-security/google-password-manager-passkeys-update-september-2024/), [Android Restore Credentials](https://developer.android.com/identity/sign-in/restore-credentials).

### Foundation 3: An opaque-blob cloud bucket without an enclave is viable

V3's recovery story does not need an SGX enclave because the recovery secret is already high-entropy (passkey PRF output + Argon2id-stretched PIN). Signal SVR2's reason for needing SGX is that *the PIN alone* is the secret; the enclave enforces the guess budget that the PIN's low entropy demands. V3's recovery secret has the PRF output as a 256-bit anchor, so the bucket can be a dumb content-addressed object store.

Cost math:

- ~512 B blob × 10M users × $0.015/GB-mo × 12 = ~$60/yr storage on Cloudflare R2.
- ~12 writes/yr/user (PIN changes + epoch bumps) × 10M = 120M Class A ops × $4.50/M = ~$540/yr.
- ~2 reads/yr/user (new-device restore) × 10M = 20M Class B ops × $0.36/M = ~$72/yr.
- Egress: **$0** on R2 (its defining feature).
- **Total: ~$700/yr per 10M users.**

This is small enough that Kuira can run it as infrastructure indefinitely, with the bucket URL exposed as a config knob so any dApp can run its own (preserving the V3 promise of no single critical-path operator).

Compared to:

- **Drive `appdata`:** free but requires Google sign-in OAuth at the worst possible UX moment (recovery on a fresh device).
- **SVR2 fork:** $2-4k/mo for a 3-node SGX quorum, multi-month engineering effort, ongoing SGX side-channel CVE exposure.
- **IPFS/Arweave:** public-CID metadata leak is worse than R2's trust model; multi-second p99 retrieval breaks the restore UX.
- **Block Store alone:** covers same-app-same-account reinstall (the dominant case), does not cover cross-device cross-account migration.

V3 ships **Block Store first (already done) + R2 as the cross-device tier**. Drive `appdata` is documented as an opt-in user-sovereign third tier.

Sources: [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/), [Signal SVR2 repo](https://github.com/signalapp/SecureValueRecovery2), [Android Block Store](https://developer.android.com/identity/block-store).

### Foundation 4: Client-side PIN stretching closes the brute-force gap

Without an enclave, the PIN's job is to survive offline guessing against a captured blob. Argon2id with `m=256 MiB, t=3, p=1` runs in ~2 s on a Pixel 7 and ~200 ms on attacker GPU. Combined with the PRF output as the *anchor* (not the entropy), this gives:

| Secret | Search space | Attacker time (GPU, 100 ms/guess) |
|---|---|---|
| 4-digit PIN | 10⁴ | 17 minutes — **unsafe** |
| 6-digit PIN | 10⁶ | 28 hours — **unsafe for funds** |
| 8-digit PIN | 10⁸ | 116 days — borderline |
| 8-char alphanumeric | 36⁸ ≈ 2.8×10¹² | ~9,000 years — safe |
| 6-word BIP-39 passphrase | >2⁶⁰ | >10¹⁰ years — safe |

V3 makes **8-character alphanumeric the floor** and **6-word passphrase the recommended option for high-value sigils**. Six-digit PIN is rejected entirely — V3 has no enclave-enforced attempt limit, so it cannot earn the affordance Signal and Apple offer.

Param storage: Argon2id `(m, t, p, perUserSalt)` lives in the blob header as a single profile byte, mirrored from 1Password's and Bitwarden's "params travel with the vault" pattern. Migration is opportunistic on next successful recovery.

Sources: [RFC 9106 Argon2](https://datatheorem.github.io/2014/04/16/custom-permissions/), [OWASP Password Storage Cheat Sheet 2024](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html), [1Password Security Design](https://agilebits.github.io/security-design/), [Bitwarden Security Whitepaper](https://bitwarden.com/help/bitwarden-security-white-paper/).

---

## Production precedents

Every production system V3 borrows from solves a slightly different problem; collectively they cover V3's design space.

### Signal SVR2 — the PIN-recovery archetype

What SVR2 does: stores a client-encrypted master key server-side, gated by an Argon2id-stretched 4-digit PIN, with SGX enclaves enforcing a guess budget. The enclave's MRENCLAVE is baked into the Signal client, so clients cryptographically verify they're talking to genuine Signal code, not a tampered build. Raft consensus across replicas keeps the guess counter monotonic so killing a replica can't roll back tries.

What V3 borrows:
- The **shape** of the recovery flow: PIN → stretch → PRF → KDF → `(idk, mack, enck)` → fetch ciphertext at `HMAC(idk, HASH(credId))` → AEAD-decrypt with `enck`.
- **Generic error responses** to defeat blob enumeration (a 404 on missing blob and a 404 on bad signature look identical from outside).
- **Safety-over-liveness:** when in doubt, recovery fails loud rather than silently degrading.

What V3 skips:
- SGX enclaves, Raft consensus, MRENCLAVE attestation — these are months of work that V3's threat model doesn't yet need. The PRF output is the high-entropy anchor; Argon2id is the rate limiter; the bucket is content-addressed.

The yackermann proposal in [SVR2 issue #4](https://github.com/signalapp/SecureValueRecovery2/issues/4) is essentially V3's recovery protocol with phone number replaced by sigil identifier and enclave replaced by R2.

### Dashlane — PRF as vault unlock at consumer scale

Dashlane shipped first ([Dec 2023](https://www.dashlane.com/blog/dashlane-phishing-resistance), via Yubico partnership): the vault is encrypted under `MachineGeneratedMP`, which is stored server-side **wrapped under each registered authenticator's PRF output**. Adding a second YubiKey runs a fresh assertion, derives that key's PRF output, and re-wraps the same MGMP. Server-side storage looks like `{credentialId → prfSalt, prfWrapped(MGMP)}`.

V3 borrows: the "**one logical secret encrypted under N unlock keys**" pattern. V3's master seed plays MGMP's role; each app's PRF output plays the per-authenticator wrap's role.

V3 differs: Dashlane re-wraps MGMP **directly** under each PRF output. V3 follows Bitwarden's RSA-indirection pattern (next section) instead, because direct wrap requires the new device's PRF output during enrollment — which is exactly what we don't have in the cross-app handoff case.

### Bitwarden — RSA indirection enables cross-credential enrollment

Bitwarden stores three blobs per credential:
1. `EncryptionKey(PrivateKey)` — RSA private key encrypted with HKDF-stretched PRF AES key.
2. `PublicKey(UserSymmetricKey)` — vault symmetric key encrypted with the RSA public key.
3. `UserSymmetricKey(PublicKey)` — RSA public key encrypted with vault key (for rotation).

The crucial property: **adding a new device needs only the new credential's public key**. The new device can be enrolled without holding the existing device's PRF output — the existing device wraps the vault key under the new public key.

V3 adopts this pattern almost verbatim for cross-app enrollment. App-A wraps the master seed under App-B's public key (delivered via the AIDL bound service); App-B unwraps with its own PRF-derived private key, then stores its own wrapped copy. App-A never sees App-B's PRF output, App-B never sees App-A's. The seed traveled, the unlock keys didn't.

### 1Password — the recovery code primitive

1Password ships a **mandatory recovery code** on every passkey-unlocked account. It's a 256-bit secret split into an SRP auth subkey + an identity-verification step. The recovery code alone is insufficient (still requires email verification), but it exists because 1Password's design team concluded that "your one passkey or nothing" is not an acceptable failure mode at consumer scale.

V3's PIN-recovery flow is the rough equivalent (PIN + cloud blob plays the recovery-code role), but V3 should also keep a BIP-39 mnemonic export as an opt-in escape hatch — analogous to WhatsApp's 64-digit key path. The mainstream user picks PIN+passkey; the self-custody user picks mnemonic; both paths are always present.

### WhatsApp E2EE backups — the 64-digit door

WhatsApp gives the user a choice at backup time: 64-digit key (write it down, WhatsApp stores nothing) or password (key escrowed in HSM under OPRF with retry counter). Years in, the password path dominates, but the 64-digit door is what keeps the trust story honest with regulators, journalists, and crypto-conscious users.

The lesson V3 takes: **never make escrow the only path.** Even after V3 ships PIN-recovery, the BIP-39 mnemonic export must always be available in Settings, biometric-gated, with no friction tax. WhatsApp validated this two-door pattern at billion-user scale.

---

## Architecture in detail

### Master seed lifecycle

**Forge (one-time, per sigil):**
1. App generates 32 random bytes from a CSPRNG (`SecureRandom`, seeded by `/dev/urandom`).
2. App enrolls a passkey via WebAuthn `create()` with PRF extension (`extensions.prf = {}`).
3. App runs an immediate PRF assertion with salt = `HKDF("kuira:local-unlock:v1" || rpId || credId)` to derive `K_local`.
4. App AES-GCM-encrypts the master seed under `K_local` to produce the local envelope (Tier 1).
5. App runs a second PRF assertion (different salt: `"kuira:block-store:v1" || ...`) to derive `K_blockstore`, encrypts the same seed under `K_blockstore`, writes to Block Store (Tier 2).
6. If user opts in to PIN recovery, app runs PIN stretching + PRF + KDF to derive `K_cloud`, encrypts the same seed under `K_cloud`, PUTs the blob to R2 (Tier 3).
7. App writes a `v3-forge-complete` marker after all chosen tiers ACK.

**Persistence (steady state):**
- Master seed lives in Tier 1 (always), Tier 2 (always), Tier 3 (if PIN-recovery opted in).
- Each tier holds the *same plaintext* (32-byte seed + small metadata header) encrypted under a *different unlock key*.
- All three unlock keys derive from the *same passkey* via different HKDF salts (purely informational separators; the passkey is the single root of trust).

**Immutability:**
- The master seed never changes after forge. PIN change, app reinstall, cross-device migration, cross-app enrollment — none of these mutate the seed.
- Only the *wrapping* changes. PIN change re-wraps Tier 3 only. Cross-app enrollment adds a new app-scoped wrapper. App reinstall on the same Google account restores from Tier 2 (or recovers via Tier 3 if Block Store missing).
- This immutability is what makes the "one seed, N unlock keys" model coherent: if the seed changed, every wrapper would need re-issuing every time, which is exactly the V2 trap.

**Sigil identity:**
- The sigil address is `derive(master_seed, "kuira:address:v1")`. Since the seed never changes, the address never changes. This is the V2→V3 migration property: V2 users keep their address.
- The sigil identifier (user-facing string, e.g. `kuira-norman-2026`) is a human-readable label chosen at forge; not cryptographic, used in PIN-recovery context disambiguation.

### Enrollment protocol (cross-app, same device)

**Goal:** transfer the master seed from App-A (already-enrolled donor) to App-B (freshly-installed petitioner) such that (a) both ends are authenticated, (b) plaintext seed never enters an unencrypted IPC parcel, (c) the user sees meaningful consent on both sides.

**Transport:** Bound service (AIDL) gated by signature permission, with QR-code fallback for cross-device. The AIDL channel is the **kernel-enforced caller-identity primitive** Android offers; `Binder.getCallingUid()` is the only IPC method where the receiver can authoritatively identify the sender without a round-trip.

**The trust-anchor question.** Adversarial review S1 correctly flagged that `signature` permission means only same-key-signed apps can bind — which, taken literally, foreclosed third-party apps and degraded V3 to "in-house key sharing." The V3 answer:

> **Signature-permission gates the *fast path* (Kuira's own apps and partners we co-sign). Third-party apps use the *consent path*: a TOFU enrollment per `{pkg, certSha}` persisted in App-A's authorized-peers list, with a one-time biometric-gated "Allow this app to request your sigil?" prompt before any binding works.**

The user becomes the trust anchor for third-party apps. App-A maintains a local list of `{pkg, certSha, label, enrolledAt, lastUsedAt}`. First contact from an unknown peer triggers a high-friction consent screen (large warning, app label, package name, signing-cert fingerprint as 4-word BIP-39 mnemonic). Subsequent contacts from the same `{pkg, certSha}` use a low-friction prompt (just biometric).

This preserves V3's stated goal (third-party apps can hold sigils) without giving up the kernel-enforced impersonation defense for first-party flows.

**Crypto envelope:**

```
// App-B (petitioner)
(skB, pkB) = X25519.keypair()
sessionId  = randomBytes(16)
send to App-A: { pkB, sessionId, appBPkg, appBCertSha256 }

// App-A returns a challenge to prove freshness (adversarial-review fix SE2)
challenge = randomBytes(32)
send to App-B: { challenge, appAPkg, appACertSha256 }

// App-B signs the challenge with skB
send to App-A: { sign(skB, challenge) }

// App-A verifies, then seals
shared     = X25519(skA_ephemeral, pkB)
salt       = sessionId
info       = "kuira-v3-enroll" || appAPkg || appBPkg
            || appACertSha256 || appBCertSha256
            || installerPackage(B) || firstInstallTime(B)
wrapKey    = HKDF-SHA256(ikm=shared, salt=salt, info=info, len=32)
nonce      = randomBytes(24)
ciphertext = XChaCha20-Poly1305.seal(
                key=wrapKey, nonce=nonce,
                aad=info,
                plaintext=master_seed)
send to App-B: { pkA_ephemeral, nonce, ciphertext }
zeroize(skA, shared, wrapKey, plaintext-in-memory)

// App-B unwraps, then re-encrypts under its own K_local + K_blockstore
```

**AEAD choice:** XChaCha20-Poly1305 over AES-GCM. The protocol is one-shot but the codebase shouldn't carry GCM-nonce-management traps; XChaCha is nonce-misuse-friendlier and is in libsodium/Tink with mature Android support.

**AAD content (adversarial-review fix SE1):** UIDs dropped (they're self-asserted and can collide); replaced with `installerPackage + firstInstallTime` which are PackageManager-attestable and stable across reinstall in ways UIDs aren't.

**Forward secrecy:** ephemeral keypairs on both sides — if either app's storage is later compromised, past enrollment sessions remain unrecoverable.

**Consent UX in App-A:**
> **BBoard wants to use your Kuira sigil**
> Package: `com.kuiralabs.bboard`
> First contact — verify before approving:
> Fingerprint: TIGER PURPLE CANOE WINTER
> [ Deny ] [ Approve with biometric ]

For repeat contacts from a previously-approved peer, the fingerprint line is omitted and the prompt collapses to a low-friction biometric (since the user has already vouched for this `{pkg, certSha}` pair).

**Confirmation UX in App-B:**
> **Your sigil is active in BBoard**
> Funds: 245 NIGHT · 18 DUST
> Sigil ID: kuira-sigil-7f3a…

Balance fetched post-enrollment confirms to the user that this is the same wallet — the only way they can detect a swapped-seed attack at the protocol's outer edge.

**Revocation, honestly stated:**
- **Soft revocation (default):** App-A removes BBoard from its authorized-peers list. BBoard's installed copy of the seed (encrypted under BBoard's own K_local) is unaffected — it can still sign. This is *removal of App-A's role in future enrollments*, not invalidation of past ones.
- **Hard revocation = sigil rotation:** The only way to make BBoard's copy worthless is to generate a new master seed and sweep funds on-chain. This is a dedicated, fee-bearing, multi-minute flow with fee preview, surfaced from Settings → Security, not from a per-app revocation row. UI text: "Stop new actions only" vs "Rotate sigil (moves all funds, requires network)."
- **Signatures already produced remain valid on-chain forever** — that's a property of the blockchain, not the protocol. UI must say so.

### Recovery protocol (cross-device, new install)

**Goal:** on a fresh device, the user reconstructs their sigil from (a) a PIN/passphrase they remember and (b) a passkey GPM-synced to their Google account.

**Inputs at recovery time:**
- **PIN/passphrase:** 8-char alphanumeric minimum; 6-word BIP-39 passphrase recommended for high-value sigils.
- **Passkey:** already on the device via GPM sync.
- **Sigil identifier:** displayed during the original forge; user re-enters or recovers via the credId enumeration path (see Foundation 2 multi-credential handling).

**Single-biometric flow (adversarial-review fix C1):**
The chicken-and-egg between `perUserSalt` and the index is resolved by making `perUserSalt` non-secret and storing it at a context-only index:

```
// Cheap, unauthenticated GET
saltIndex   = HMAC-SHA256(key=HASH(context), data="salt-lookup-v1")
perUserSalt = bucket.GET(saltIndex)   // returns 16 bytes or 404

// Stretch PIN
stretched   = Argon2id(
                 password = PIN,
                 salt     = perUserSalt || HASH(context),
                 m = 256 MiB, t = 3, p = 1, tagLength = 32
               )

// Build PRF salt
recoverySalt = HMAC-SHA256(key=HASH(context), data=stretched)

// SINGLE passkey assertion
prfOutput   = passkey.PRF(salt=recoverySalt)   // 32 bytes

// Derive three keys
prk         = HKDF-Extract(salt=HASH(context), ikm=prfOutput)
idk         = HKDF-Expand(prk, info="kuira-v3-idk",  L=32)
sigk        = HKDF-Expand(prk, info="kuira-v3-sigk", L=32)  // Ed25519 seed for PUT auth
enck        = HKDF-Expand(prk, info="kuira-v3-enck", L=32)

// Locate and decrypt
recovery_index = HMAC-SHA256(key=idk, data=HASH(credId))
blobBytes      = bucket.GET(recovery_index)
master_seed    = AES-256-GCM.decrypt(
                    key=enck, nonce=blob.nonce, ad=blob.header, ct=blob.ciphertext
                 )
```

**Blob format (versioned):**

```
struct RecoveryBlob {
  magic         : "KRA1"                  // 4 bytes
  version       : u16  = 1
  argonProfile  : u8                      // 0x01 = m=256MiB,t=3,p=1
  reserved      : u8
  epoch         : u32                     // monotonic, plaintext (fix H4)
  nonce         : [12]byte                // AES-GCM nonce
  ciphertext    : [N]byte                 // master_seed[32] padded to 64
  authTag       : [16]byte                // GCM tag, AD = magic..epoch
  passkeySig    : [64]byte                // Ed25519 sig over (magic..authTag)
  passkeyPub    : [32]byte                // Ed25519 pubkey, derived from sigk
}
```

The `epoch` field is plaintext but covered by the AEAD tag; client tracks the highest epoch it has seen in Block Store and rejects any blob with lower epoch (fix H4 — silent rollback by malicious bucket).

**Bucket protocol:**

```
GET    /v1/blobs/{index_hex}     // no auth, just the index
PUT    /v1/blobs/{index_hex}     // Ed25519-signed body, conditional on previous etag
DELETE /v1/blobs/{index_hex}     // Ed25519-signed timestamp, replay-protected
```

- **First-PUT anti-squat (fix H5):** first PUT requires a forge-time server-issued user-id namespace token, distributed at forge via a one-time signed challenge. Prevents attackers from squatting predictable indices.
- **Per-index rate limits:** 5 GETs/hour with exponential backoff; 24-hour lock at 25 failures. (fix C2: prevents free blob-acquisition for offline grinding.)
- **One-shot bucket use (fix C3):** client caches decrypted seed locally under Keystore after first recovery; bucket is hit exactly once per device lifetime + once per PIN change. Eliminates linkability via repeated GETs.
- **Server-side OPRF pepper (future hardening, post-launch):** add a Cloudflare Worker that HMACs the recovery_index under a server-held HSM key before forwarding to R2; turns blob acquisition from "get the URL" into "get the URL AND make a request the server is willing to serve." Closes C2 even against weak PINs.

**PIN change:**
```
1. User enters OLD PIN → derive old (idk, enck, sigk) → fetch blob → AEAD verify.
   (Successful decrypt = old PIN was correct.)
2. User enters NEW PIN twice → derive new (idk, enck, sigk) with fresh perUserSalt.
3. Re-encrypt master seed; bump epoch.
4. PUT new blob at new recovery_index (signed by sigk_new).
   - Mark new blob with state=committing in header flags.
5. After PUT ack: PUT again with state=committed (atomic CAS).
6. DELETE old recovery_index (signed by sigk_old).
7. Update Block Store: new perUserSalt, new wrapped envelope.
```

The two-phase commit (fix H6) prevents the DELETE-then-PUT race; if the client crashes between steps 4 and 6, next launch sees state=committing on the new blob, completes step 5 or aborts and falls back to old PIN.

**Forgotten PIN — be honest:** No recovery path. Funds are lost. Mitigations at forge:
- Display the BIP-39 mnemonic and require 3-of-12 word confirmation before forge completes.
- Surface "Export recovery phrase" in Settings, biometric-gated.
- At PIN-change time, re-warn: "If you forget this PIN, your sigil is lost. Export your recovery phrase to be safe."

### Storage tiers

**Universal envelope (104 bytes):**

```
Offset  Size   Field
------  ----   -----
0       1      magic = 0xB3                  // Kuira v3
1       1      version = 0x01
2       2      flags                         // bit0=PIN-derived, bit1=BS-mirror, bit2=cloud
4       4      created_unix
8       4      sigil_epoch                   // monotonic counter
12      12     IV (random)
24      32     ciphertext (master_seed[32])
56      16     GCM tag
72      32     aad_digest = SHA256(version || flags || epoch || tier_tag)
```

`tier_tag ∈ {"L1", "BS", "CL"}` is mixed into AAD so a blob from one tier cannot be replayed into another. Same plaintext, three different ciphertexts under three different keys with three different AAD bindings.

**Tier 1 — Per-app local:**
- Path: `${context.filesDir}/kuira/sigil/v3/seed.bin`
- Mechanism: raw file via the envelope; **no EncryptedSharedPreferences** (its Keystore master key adds a second failure mode without security benefit).
- Atomicity: write `seed.bin.tmp`, fsync, rename — the [0-byte file brick pattern](memory: project_zero_byte_file_brick_pattern) lesson is binding.
- Backup excluded: `<full-backup-content>` + `android:allowBackup="false"` for this file. Tier 2 owns the backup story.

**Tier 2 — Block Store:**
- API: `BlockstoreClient.storeBytes(StoreBytesData)` with `setShouldBackupToCloud(true)`.
- **One logical entry (fix S3):** CBOR map `{seed_envelope, meta, recovery_index}` → ~400 B. Reserves 1 of 16 Block Store entries, leaves 15 for other features.
- Cloud backup is the same-Google-account reinstall happy path; we've verified the round-trip (memory: `reference_sigil_recovery_flow`).

**Tier 3 — Opaque cloud bucket:**
- Provider: Cloudflare R2 via Cloudflare Worker at `https://recovery.kuira.app`.
- Endpoint constant: `KuiraEndpoints.RECOVERY_R2 = "https://recovery.kuira.app"` (no magic strings, per project convention).
- Object key: `v3/{recovery_index_hex}/{epoch:010d}.blob` — epoch in the key makes concurrent writes visible.
- Auth: Ed25519 sigk (derived from PRF; HKDF leg `"kuira-v3-sigk"`).
- TLS pinning: pin R2 leaf + Cloudflare ISRG-Root-X1 fallback via `okhttp.CertificatePinner`.

**Cross-tier key derivation:**

```
prf_out_app  = passkey.PRF(salt = HKDF("kuira:local-unlock:v1" || rpId || credId))
prf_out_bs   = passkey.PRF(salt = HKDF("kuira:block-store:v1"  || rpId || credId))
prf_out_pin  = passkey.PRF(salt = HKDF("kuira:recovery:v1"
                                       || HMAC(SHA256(PIN_stretched), rpId)))

K_local      = HKDF(prf_out_app, info="kuira/aead/local/v1")
K_blockstore = HKDF(prf_out_bs,  info="kuira/aead/bs/v1")
K_cloud      = HKDF(prf_out_pin, info="kuira/aead/cloud/v1")
```

**Why three keys with the same passkey?**
- *Replay isolation:* tier_tag in AAD makes cross-tier replay fail.
- *Compromise containment:* a future Android CVE that lets one app read another's filesDir yields only `K_local`-encrypted bytes — Block Store and cloud are unreachable.
- *Independent rotation:* PIN change rotates `K_cloud` only; biometric-only unlock continues to work for K_local and K_blockstore without touching the user's PIN.

**Adversarial-review S1 corrective:** the *root of trust is the passkey itself*. If the passkey is exfiltrated (GPM account takeover — see memory `project_sigil_gpm_account_constraint`), all three keys derive trivially. The three-key separation defends against *sandbox-escape* attacks, not *credential* attacks. To defend against credential attacks, V3 binds the `K_cloud` derivation to a **device-Keystore-attested key** (StrongBox where available) so cloud unwrap requires PIN + passkey + this device's Keystore — three factors, not two. Documented in Threat Model section.

**Concurrent updates (fix S5):**
- Every mutation increments `sigil_epoch` locally.
- Tier 2 is per-device (single writer), no conflict.
- Tier 3 uses R2 conditional PUT (`If-Match`). Worker enforces `new_epoch == max_existing_epoch + 1`. Conflict → 409 with winning blob returned.
- On 409: decrypt both, compare master seed bytes. If equal (PIN-change-only re-wrap), loser silently adopts winner's epoch. If different — should be impossible since `recovery_index` derives from `HMAC(prf_out_pin, master_seed)` (not just PRF), so different seeds produce different buckets. No fork possible.

### Migration from V2 (shared-rpId, shared-salt)

**Decision: adopt V2's PRF output as the V3 master seed.** No on-chain transfer. No new wallet address. No legacy wrapper.

**Justification:**
1. **V2's PRF output is already 32 bytes of high entropy from a passkey-resident PRF** — it satisfies V3's master-seed contract by construction.
2. **On-chain transfer is user-hostile.** Charging every existing user a tx fee + multi-minute wait for an upgrade they didn't ask for breaks the implicit contract. Active dApp state (Kicks games mid-reveal, contract balances) breaks under forced address rotation.
3. **The V3 invariant ("master seed never changes when PIN changes") is forward-looking.** Pre-V3 users had no PIN; the immutability clock starts at migration time.
4. **Identity continuity** matches the sigil framing — the sigil address survives the upgrade.

**Migration flow (revised per adversarial review M1, M2, M4):**

```
[Trigger: next intentional security-touching action — Send, Backup, Settings → Security.
 NOT a launch-time interruption. Frame: "Add recovery options".]

[Biometric — existing V2 passkey, salt = "kuira:seed:v1"]
   ↓
prfOut := PRF(passkey, "kuira:seed:v1")     // identical to V2's derivation
master_seed := prfOut                        // adopted, not re-derived

[Second biometric — new salt "kuira:unlock:v3:<rpId>"]
   ↓
prfUnlock := PRF(passkey, "kuira:unlock:v3:<rpId>")
K_local := HKDF(prfUnlock, info="kuira/aead/local/v1")

[Atomic write order, per memory: zero-byte file brick pattern]
   1. Write V3 envelope to seed.bin.tmp; fsync; rename to seed.bin
   2. Write V3 envelope to Block Store under K_blockstore
   3. Write v3-migration-complete marker (fsync, rename)
   4. ONLY THEN delete V2 artifacts

[Recovery PIN setup offered as a SEPARATE, dismissible follow-on flow]
```

**Failure & recovery semantics:**

| Failure | Recovery |
|---|---|
| Network down | Migration is fully local; no network needed for steps 1-4. |
| App killed mid-migration | Next launch: no marker → re-run from top. `master_seed := PRF(passkey, "kuira:seed:v1")` is deterministic, every restart produces the same seed. Idempotent. |
| Biometric denial | App reverts to V2 mode (V2 code path retained for transition window). Migration prompt reappears on next intentional security action. |
| Partial write | Atomic rename + marker-as-last-write means partial states are invisible to subsequent launches. |
| Block Store write failure | Local write succeeds, app marks V3-complete locally, schedules Block Store retry via WorkManager. |

**Rollback safety (fix M2):**
- Transition release window: 6 weeks with both V2 and V3 paths compiled in.
- If a V3 hotfix is needed, remote-config flag flips back to V2 mode **AND blocks Tier 3 writes** (`flags.cloud = false`). Tier 3 writes are only safe to roll back if no PIN-change has occurred; blocking writes during rollback prevents two devices ending up at incompatible `epoch=1` states.
- After transition window: ship one explicit `migrate-only` release between transition end and V2 removal — opens, migrates, closes. No wallet UI in that build. Avoids bricking the long-tail upgrader.

**Multi-device consistency (fix M3):**
- GPM-synced passkey → same PRF output on every device → same master seed.
- Concurrent first-migrations from the same seed on different devices race to write `epoch=1` to R2. Seed `sigil_epoch` initial value from `HMAC(master_seed, "epoch-init") mod 2^16` so both devices compute the same starting epoch, IV is derived deterministically too — both write byte-identical blobs, CAS succeeds for whichever arrives first, the other gets a no-op success.

**Migration is the single most important UX in V3.** Get it wrong and the install base fragments forever. The above flow prioritizes (a) zero on-chain cost, (b) user-initiated trigger over interruption, (c) idempotent re-entry, (d) safe rollback over the transition window.

---

## Adversarial findings + mitigations (consolidated)

The Phase 4 review produced 30+ findings across three components. The ones that *changed the architecture* are absorbed inline above; this section lists the residual mitigations and explicitly-accepted-risks for the launch checklist.

### Enrollment
- **S1 (signature-permission centralization)** → resolved: dual-track trust (signature-perm fast path + TOFU consent path for third parties). User-mediated trust for unknown peers.
- **S2 (cert fingerprint theater)** → resolved: 4-word fingerprint only shown on *first-contact* TOFU enrollment, omitted on repeat contacts. Same-signed apps skip the fingerprint UI entirely (it's noise when invariant by construction).
- **SE1 (UID fragility)** → resolved: AAD uses `installerPackage + firstInstallTime` instead of UID.
- **SE2 (no App-A challenge)** → resolved: two-round handshake with App-A challenge + App-B signature.
- **SE3 (hard revocation cost)** → resolved: renamed "Sigil rotation," gated behind dedicated fee-bearing flow, removed from per-app revocation row.
- **SE4 (Block Store async failure)** → resolved: success screen gated on Block Store ack; "Backup pending" state for failure mode.
- **N1 (metadata dossier)** → accepted, documented: App-A maintains authorized-peers list; user can purge.
- **N2 (multi-sigil chooser spec gap)** → noted for implementation: App-A's consent UI must include sigil selector when multiple sigils exist.
- **N3 (RemoteException debuggability)** → noted: `EnrollResult` sealed enum with stable error codes.
- **N4 (action-name version coupling)** → noted: separate `int protocolVersion` field in AIDL parcel.

### Recovery
- **C1 (eval.second misread)** → resolved: single-biometric flow via context-only public salt-lookup index.
- **C2 (online-guessable through rate limit)** → resolved: 5 GETs/hour per index + exponential backoff + 8-char alphanumeric floor + 6-digit PIN rejected. Post-launch hardening: server-side OPRF pepper via Cloudflare Worker + HSM.
- **C3 (recovery_index as long-term pseudonym)** → resolved: one-shot bucket use, local Keystore cache, rotate index on PIN change.
- **H4 (silent blob rollback)** → resolved: monotonic `epoch` field in plaintext + AAD-covered; reject lower-than-seen.
- **H5 (first-PUT TOFU squatting)** → resolved: forge-time server-issued user-id namespace token, signed.
- **H6 (DELETE-then-PUT race)** → resolved: two-phase commit (state=committing → state=committed → DELETE old).
- **M7 (Argon2 rotation phishability)** → resolved: defer rotation to legitimate PIN entry, never inject.
- **M8 (single-byte profile too rigid)** → resolved: TLV-encoded params in blob header.
- **M9 (no account-deletion path)** → resolved: forge-time signed delete-token sealed under mnemonic, redeemable for GDPR purge.
- **L10 (sigil-id shoulder-surf)** → noted: separate screens for sigil-id display and PIN entry.

### Storage & migration
- **S1 (cross-tier blast radius)** → resolved: bind `K_cloud` to a device-Keystore-attested key (StrongBox where available); state cross-*sandbox* threat model, not cross-*account*.
- **S2 (recovery_index as pre-auth oracle)** → resolved: Worker requires proof of recent PRF-app-unlock attestation token before issuing GET URLs.
- **S3 (Block Store budget claim)** → resolved: 1 CBOR-encoded entry, not 3 separate entries.
- **S4 (cost projection understatement)** → noted: project ~$700/yr per 10M users at realistic enrollment rates; budget Cloudflare Turnstile for DDoS.
- **S5 (multi-device fork)** → resolved: `recovery_index = HMAC(prf_out_pin, master_seed)`, not just PRF; different seeds → different buckets, no fork.
- **M1 (unsolicited migration prompt)** → resolved: trigger on next intentional security-touching action; frame as "Add recovery options."
- **M2 (rollback safety)** → resolved: rollback also blocks Tier 3 writes.
- **M3 (multi-device epoch race)** → resolved: deterministic epoch initial value from `HMAC(master_seed, "epoch-init")`.
- **M4 (atomic-rename ordering)** → resolved: explicit ordering — V3 envelope → V3 marker → delete V2.
- **M5 (V2 code permanent attack surface)** → resolved: explicit `migrate-only` release between transition window and V2 removal.

### Accepted risks
- **GPM account takeover** unlocks all tiers. Mitigation: device-Keystore-attestation on K_cloud (forces attacker to also compromise the device); user education on Google account security; not solved end-to-end. This is the fundamental limit of "passkey as root of trust" and is inherited from every PRF-vault system.
- **Forgotten PIN with no mnemonic export = funds lost.** This is non-negotiable in any zero-knowledge scheme; mitigated by aggressive forge-time mnemonic display + Settings export.
- **Forced sigil rotation** is fee-bearing and multi-minute. No way around this when revoking a peer's seed copy on a public blockchain.

---

## Comparison: V1 vs V2 vs V3

| Property | V1 (per-app salt) | V2 (shared rpId + shared salt) | V3 (seed-as-data) |
|---|---|---|---|
| **Non-regression invariant** | ❌ Regresses shipped funds (changes seed for existing apps) | ✓ Preserves shipped funds (same salt, same seed) | ✓ Preserves shipped funds (adopt V2 PRF output as seed) |
| **Cross-app fund portability — Kuira first-party** | ✗ Each app has different seed | ✓ Trivially, via shared salt | ✓ Via enrollment protocol (one biometric per peer) |
| **Cross-app fund portability — Third-party dApps** | ✗ Each app has different seed | ⚠️ Only if they ship Kuira's salt + use Kuira's rpId | ✓ Via enrollment protocol with TOFU consent |
| **Cross-device portability (same Google account)** | ✓ GPM sync of passkey + per-app salt re-derives same seed | ✓ GPM sync re-derives | ✓ GPM sync of unlock key + Block Store of envelope |
| **Cross-device portability (different account)** | ✗ Lost seed | ✗ Lost seed | ✓ PIN-recovery via R2 |
| **Kuira-org operates critical-path domain** | ✗ No (per-app rpIds) | ✗ Yes, forever (`kuira.app` is the salt foundation) | ✓ No — rpId only matters for App-A's unlock key, not the seed itself |
| **Salt/rpId can be changed without losing funds** | ✗ No | ✗ No | ✓ Yes — change unlock key wrapper, seed unaffected |
| **Number of authorities that can hold a seed** | 1 per app (no sharing) | All apps that use the shared salt | N, one per enrolled authority |
| **Adding a new authority** | N/A (no sharing) | Implicit (any app that uses the salt) | Explicit enrollment, biometric on both ends |
| **Revoking an authority** | N/A | Impossible (every app with the salt holds the seed) | Soft: remove wrapper (App keeps its copy). Hard: sigil rotation. |
| **Recovery without device** | ✗ Lost | ✗ Lost | ✓ PIN + cloud blob (Track B) |
| **Threat model honesty** | Simple, regression-prone | Simple, centralizing | Complex, but each layer is in production elsewhere |
| **Engineering cost to reach parity with V2 today** | N/A | 0 (already shipped) | 4-8 weeks (Track A) |
| **Engineering cost to add what V2 can't do** | N/A | Bounded by salt convention | 8-12 weeks (Track B: cloud recovery) |
| **Maintains "Kuira-compatible" third-party app story** | ✗ Each app forks the seed | ⚠️ Only if they accept the rpId+salt constraint | ✓ Via TOFU enrollment |
| **Production precedent** | None | None | Dashlane, Bitwarden, 1Password, Signal, WhatsApp |

**Hard score on the non-regression invariant:** V3 ✓ (adopts V2 PRF output as seed, same wallet, same address).

**Hard score on third-party app fund portability:** V1 ✗, V2 ⚠️ (requires they accept Kuira's salt convention forever), **V3 ✓** (via explicit enrollment).

V3 is the only variant that scores ✓ on both.

---

## Recommendation

**Adopt V3 as the primary architecture for sigil portability.** Ship in three tracks:

### Track A — Foundation (4-8 weeks)
**Goal:** parity with V2 for the shipped use case + V2 migration + cross-app enrollment.

- Implement `core:sigil-v3` module containing:
  - Master seed lifecycle (forge, persist, immutability)
  - Universal envelope codec (104-byte format)
  - Tier 1 (per-app local file) + Tier 2 (Block Store) storage
  - AIDL enrollment service (signature-permission gate + TOFU consent path)
- Implement V2→V3 migration:
  - User-initiated trigger (Settings → Security action)
  - Atomic write ordering + idempotent re-entry
  - Rollback safety (Tier 3 writes blocked during rollback)
  - `migrate-only` transition release plan
- Implement cross-app enrollment UI:
  - App-A's authorized-peers list (Settings → Connected Apps)
  - First-contact consent screen with cert fingerprint
  - Repeat-contact streamlined biometric prompt
  - App-B's post-handoff confirmation with balance check
- Ship V3 to internal beta with V2 fallback flag.

### Track B — Recovery (8-12 weeks)
**Goal:** PIN-based recovery for cross-device, cross-account, lost-device scenarios.

- Implement `core:recovery` module:
  - Argon2id PIN stretcher with profile versioning
  - Recovery blob format + epoch + two-phase commit
  - Ed25519 sigk for PUT authentication
  - Single-biometric flow with public salt-lookup index
- Stand up Cloudflare R2 bucket + Worker:
  - `recovery.kuira.app` custom domain, TLS pinning
  - Presigned URL minting with PRF-attestation challenge
  - Per-index rate limiting (5 GETs/hour, exponential backoff)
  - First-PUT anti-squat with forge-time namespace token
- Implement Tier 3 storage tier:
  - Cloud writes opt-in (user must enable PIN recovery)
  - Local Keystore cache for one-shot bucket use
  - Concurrent-write resolution via deterministic epoch + CAS
- Implement recovery UI:
  - Sigil identifier display + entry
  - 8-char alphanumeric floor enforcement
  - 6-word passphrase recommendation for high-value sigils
  - Forge-time mnemonic export with 3-of-12 confirmation
- Documentation: trust model, threat model, accepted risks.

### Track C — Sigil Account contract (post-V3 launch)
**Goal:** on-chain "Sigil Account" contract for fully delegated signing.

This is V2's Track B reframed. With V3's enrollment protocol, multiple apps holding the same seed is the *consumer-facing* solution; an on-chain Sigil Account contract is the *programmatic-delegation* solution for agent runtimes, multi-sig governance, and DAO-controlled sigils.

- On-chain contract: registry of authorized signing keys per sigil address
- Off-chain: agent runtime SDK that uses delegated keys (no master seed exposure)
- Coordinates with the `project_sdk_platform_roadmap` memory item (#27 delegated keys)

Track C does not block Tracks A and B; it's the architectural completion of the "sigil = identity + authority + state" framing.

---

## Migration plan

### Week 1-2: Architectural scaffolding
- Spike: validate single-biometric salt-lookup flow on Pixel 7 + emulator + low-end device
- Spike: validate AIDL signature-permission gate + TOFU consent flow
- Stand up `core:sigil-v3` module skeleton with universal envelope codec
- Define telemetry: migration success rate, enrollment success rate, recovery success rate

### Week 3-4: Tier 1 + Tier 2 + migration
- Implement Tier 1 file storage with atomic rename
- Implement Tier 2 Block Store integration (CBOR-encoded single entry)
- Implement V2→V3 migration flow with rollback safety
- Internal alpha: 5 engineers run migration on personal devices

### Week 5-6: Cross-app enrollment
- Implement AIDL service in Kicks example app (memory: `examples/midnight-kicks`)
- Implement consent UI with TOFU
- End-to-end test: Kuira → Kicks enrollment, fund visibility in Kicks, soft revocation

### Week 7-8: Track A polish + Track B kickoff
- V3 internal beta release (50 users, V2 fallback flag available)
- Telemetry review: migration success rate must be >95% before public release
- Start Cloudflare Worker + R2 bucket provisioning for Track B

### Week 9-12: Track B implementation
- Argon2id PIN stretcher with property tests
- Recovery blob format + two-phase commit
- Worker authentication + rate limiting + presigned URL minting
- Tier 3 integration into existing storage layer
- Recovery UI: PIN entry, sigil-id confirmation, success screen

### Week 13-14: Track B internal beta
- 100 internal beta users opt-in to PIN recovery
- Simulate device loss + recovery on new device
- Adversarial review of deployed system (Worker logs, R2 access patterns)

### Week 15-16: Public release
- V3 public release with PIN recovery opt-in
- Documentation: SECURITY.md, recovery flow walkthrough, threat model
- V2 code retained but inert; final removal scheduled 6 weeks out

---

## Open questions

1. **Cross-app enrollment for non-Kuira-signed third-party apps:** the TOFU consent path is correct for security, but is the UX acceptable for "I just installed BBoard, why do I have to go to Kuira and approve it?" Need user testing in week 5-6.

2. **Cloudflare Worker authentication challenge format:** Ed25519-signed JSON vs CBOR vs raw bytes. JSON is debuggable; CBOR is compact; raw bytes are fastest. Punt to implementation; affects Worker code only.

3. **GPM account selector for sigil ownership:** memory item `project_sigil_gpm_account_constraint` notes GPM gives no app account choice. For PvP testing in Kicks this is a real blocker. V3 doesn't solve this — it's a platform limitation. Kicks wishlist #28 covers it.

4. **iOS parity:** Tracks A and B are Android-first. iOS spike (memory: `project_sdk_platform_roadmap`) is committed but timing depends on iOS-native milestones. V3's protocol shape is platform-agnostic (WebAuthn PRF works on iOS 18.4+; the AIDL primitive is Android-specific but maps to App Groups / XPC on iOS).

5. **Quantum-future migration:** Ed25519 and AES-GCM fall to Shor/Grover. Blob format version bump (`v2`) to ML-KEM-768 wrapping + Dilithium signatures is forward-planned. No action this year.

6. **Recovery PIN on existing V2 users:** migration adopts the PRF as the seed but does not force PIN setup. Should we *encourage* PIN setup post-migration (modal nudge) or treat it as fully opt-in? Defer to PM/UX after Track A telemetry lands.

7. **Per-feature opaque buckets:** the bucket-URL-as-config-knob promise extends to Kicks game state, agent-runtime sessions, etc. Need a generalization of the recovery blob format to handle arbitrary payloads, not just 32-byte master seeds. Scope for V3.1.

8. **PIR for spendability:** memory `project_phase9_spendability_pir` notes a future privacy-preserving indexer. Independent of V3; not blocking.

---

## References

### Phase 1 — Platform verification
- [Android Credential Manager overview](https://developer.android.com/identity/sign-in/credential-manager)
- [androidx.credentials release notes](https://developer.android.com/jetpack/androidx/releases/credentials)
- [Credential Manager troubleshooting guide](https://developer.android.com/identity/sign-in/credential-manager-troubleshooting-guide)
- [Single-tap passkey + biometric prompts](https://developer.android.com/identity/sign-in/single-tap-biometric)
- [Yubico — Developers Guide to PRF](https://developers.yubico.com/WebAuthn/Concepts/PRF_Extension/Developers_Guide_to_PRF.html)
- [Corbado — Passkeys & WebAuthn PRF for E2EE (2026)](https://www.corbado.com/blog/passkeys-prf-webauthn)
- [Passkey PRF Extension Example](https://www.passkeyprf.com/)
- [Oblique — Passkey PRFs for E2EE](https://oblique.security/blog/passkey-prf/)
- [Chromium Intent-to-Ship: PRF](https://groups.google.com/a/chromium.org/g/blink-dev/c/iTNOgLwD2bI)
- [Android — Implicit intent hijacking](https://developer.android.com/privacy-and-security/risks/implicit-intent-hijacking)
- [Android — Intent redirection](https://developer.android.com/privacy-and-security/risks/intent-redirection)
- [Android — Unsafe deep links](https://developer.android.com/privacy-and-security/risks/unsafe-use-of-deeplinks)
- [Android — Custom permissions](https://developer.android.com/privacy-and-security/risks/custom-permissions)
- [Microsoft Security Blog — Intent redirection 2026](https://www.microsoft.com/en-us/security/blog/2026/04/09/intent-redirection-vulnerability-third-party-sdk-android/)
- [Snyk Labs — Intent-based vulnerabilities](https://labs.snyk.io/resources/exploring-android-intent-based-security-vulnerabilities-google-play/)
- [Oversecured — Deep link account takeover](https://oversecured.com/blog/android-deep-link-vulnerabilities)
- [USENIX Security 2017 — Measuring insecurity of mobile deep links](https://www.usenix.org/conference/usenixsecurity17/technical-sessions/presentation/liu)
- [Data Theorem — Race conditions on Android custom permissions](https://datatheorem.github.io/2014/04/16/custom-permissions/)
- [NDSS 2018 — Android custom permissions](https://www.ndss-symposium.org/wp-content/uploads/2018/02/ndss2018_08-4_Tuncay_paper.pdf)
- [OWASP MASVS — Platform Interaction](https://mas.owasp.org/MASVS/09-MASVS-PLATFORM/)
- [MALintent — Coverage-guided intent fuzzing (2025)](https://taesoo.kim/pubs/2025/askar:malintent.pdf)
- [Google Drive appdata scope](https://developers.google.com/drive/api/guides/appdata)
- [Google CASA verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification)
- [CASA Tier 2 cost analysis 2025-2026](https://deepstrike.io/blog/google-casa-security-assessment-2025)
- [Android Block Store docs](https://developer.android.com/identity/block-store)
- [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/)
- [RFC 9106 — Argon2](https://datatheorem.github.io/2014/04/16/custom-permissions/)
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [WebAuthn L3 §6.1 Credential ID](https://www.w3.org/TR/webauthn-3/#credential-id)
- [Corbado — WebAuthn User ID, User Handle, Credential ID](https://www.corbado.com/blog/webauthn-user-id-userhandle)
- [Corbado — Multiple passkeys per account](https://www.corbado.com/faq/multiple-passkeys-per-account)
- [web.dev — excludeCredentials](https://web.dev/articles/webauthn-exclude-credentials)
- [Google blog — GPM passkeys, Sept 2024](https://blog.google/innovation-and-ai/technology/safety-security/google-password-manager-passkeys-update-september-2024/)
- [Chrome for Developers — GPM passkey sync](https://developer.chrome.com/blog/passkeys-gpm-desktop)
- [MojoAuth — Cross-device passkey sync](https://mojoauth.com/blog/cross-device-passkey-sync-icloud-google-1password)
- [Android Restore Credentials](https://developer.android.com/identity/sign-in/restore-credentials)
- [Apple Developer Forums — RP re-enrollment](https://developer.apple.com/forums/thread/726170)
- [Yubico — Single-device vs multi-device credentials](https://developers.yubico.com/Passkeys/Passkey_concepts/Single_device_vs_multi_device_credentials.html)

### Phase 2 — Production precedents
- [Signal SVR2 repo](https://github.com/signalapp/SecureValueRecovery2)
- [SVR2 issue #4 — PRF proposal (yackermann)](https://github.com/signalapp/SecureValueRecovery2/issues/4)
- [Signal blog — Secure Value Recovery preview](https://signal.org/blog/secure-value-recovery/)
- [Signal blog — Improving Registration Lock](https://signal.org/blog/improving-registration-lock/)
- [About Signal PIN — KDF details](https://s1m.fr/signal-pin/)
- [Matthew Green — A few thoughts on Signal's SVR](https://blog.cryptographyengineering.com/2020/07/10/a-few-thoughts-about-signals-secure-value-recovery/)
- [Dashlane — Phishing-resistant authentication](https://www.dashlane.com/blog/dashlane-phishing-resistance)
- [Dashlane — Credential security in detail](https://support.dashlane.com/hc/en-us/articles/32877433567634-4-Credential-security-in-detail)
- [Dashlane — Passwordless login with a security key](https://support.dashlane.com/hc/en-us/articles/26705672883602-Passwordless-login-with-a-security-key)
- [Dashlane — Confidential Computing for Passkeys](https://www.dashlane.com/blog/passkeys-with-confidential-computing)
- [Dashlane — Security whitepaper](https://www.dashlane.com/download/whitepaper-en.pdf)
- [Yubico — Goodbye master passwords (Dashlane)](https://www.yubico.com/blog/goodbye-master-passwords-dashlane-and-yubico-enhance-credential-vault-encryption-and-login-with-yubikeys/)
- [Bitwarden — PRF WebAuthn role in passkeys](https://bitwarden.com/blog/prf-webauthn-and-its-role-in-passkeys/)
- [Bitwarden contributing docs — PRF implementation](https://contributing.bitwarden.com/architecture/deep-dives/passkeys/implementations/relying-party/prf/)
- [Bitwarden — Login with passkeys](https://bitwarden.com/help/login-with-passkeys/)
- [Bitwarden community — Vault recovery with PRF passkey](https://community.bitwarden.com/t/allow-for-vault-recovery-with-prf-passkey-or-recovery-key/85439)
- [Bitwarden Security Whitepaper](https://bitwarden.com/help/bitwarden-security-white-paper/)
- [1Password — Encrypt data with passkeys](https://1password.com/blog/encrypt-data-saved-passkeys)
- [1Password Security Design — Restoring access](https://agilebits.github.io/security-design/restore.html)
- [1Password Security Design — Unlock with passkey/SSO](https://agilebits.github.io/security-design/passkeySSO.html)
- [1Password — About passkey unlock security](https://support.1password.com/passkey-security/)
- [1Password — Goodbye passwords](https://1password.com/blog/unlock-1password-with-passkeys)
- [1Password — Recovery code security](https://support.1password.com/recovery-code-security/)
- [1Password — Introducing recovery codes](https://1password.com/blog/introducing-1password-recovery-codes)
- [Darth Null — 1Password Full Trip](https://darthnull.org/1pass-roundtrip/)
- [WhatsApp E2EE Backups Whitepaper](https://www.whatsapp.com/security/WhatsApp_Security_Encrypted_Backups_Whitepaper.pdf)
- [Meta Engineering — E2EE backups](https://engineering.fb.com/2021/09/10/security/whatsapp-e2ee-backups/)
- [Meta Engineering — Strengthening E2EE backups 2026](https://engineering.fb.com/2026/05/01/security/meta-strengthening-end-to-end-encrypted-backups/)
- [NCC Group — WhatsApp E2EE assessment](https://www.nccgroup.com/media/fzwdxklh/_ncc_group_whatsapp_e001000m_report_2021-10-27_v12.pdf)
- [IACR 2023/843 — WhatsApp E2EE Backup Protocol analysis](https://eprint.iacr.org/2023/843.pdf)
- [Telegram Technical FAQ](https://core.telegram.org/techfaq)

### Internal references
- V1: `docs/research/SIGIL_PORTABILITY_INVESTIGATION.md`
- V2: `docs/research/SIGIL_PORTABILITY_INVESTIGATION_V2.md`
- Memory: `project_sigil_concept`, `project_sigil_gpm_account_constraint`, `project_zero_byte_file_brick_pattern`, `reference_blockstore_cloud_backup`, `reference_sigil_recovery_flow`, `project_sdk_platform_roadmap`, `feedback_mpc_vm_principle`

---

*End of investigation. V3 supersedes V2 as the recommended architecture for sigil portability. V1 and V2 documents remain on record for traceability of the evolution.*
