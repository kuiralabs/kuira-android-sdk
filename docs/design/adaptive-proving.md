# Adaptive proving — route each proof local vs remote by cost AND privacy

**Status:** design (not built). Tracked as roadmap row #58.
**Owner decision needed before build:** yes (SDK `core/` change → approval + full gate).

## Problem

Generating a transaction's zero-knowledge proof is the most expensive step in the SDK:
seconds of CPU and, for large circuits, hundreds of MB to GBs of RAM. The Private Vault
made this concrete — its `pvExecute` / `pvApprove` / `pvRevoke` circuits carry ~5.2 MB
proving keys and, on a memory-limited emulator/phone, the on-device prover returned
`null` (out of memory). The public vault never hit this because its circuits are light.

There are **two independent proving paths** in the SDK today, and only one is tunable:

| Path | Code | Honors `ProvingMode`? |
|---|---|---|
| **Wallet txs** (send NIGHT, dust registration) | `core/ledger` `TransactionSubmitter` (`ProvingMode.REMOTE ->` at :146) via `ProofServerClientImpl` | ✅ LOCAL or REMOTE |
| **Contract calls / deploys** (`MidnightContract`) | `core/compact-engine` `MidnightConfig.kt:57` — `proofProvider = LocalProofProvider(...)` | ❌ **hardcoded local** |

So `MidnightSdk.Builder.provingMode(REMOTE)` offloads only *wallet* transactions; every
contract circuit proves on-device regardless. Heavy contract circuits therefore can't run
on devices that lack the RAM to self-prove them. This design makes contract proving
**adaptive**: route each proof to local or remote based on its **cost** and its
**privacy sensitivity**, with the device's real memory budget in the loop.

## Why "just send big ones remote" is the WRONG default

Remote proving sends the **witness — the private inputs — to the proof server** (proof
generation is inseparable from the witness; see "Fragmentation" below). For a normal
contract that's fine. For the **Private Vault**, `pvExecute`'s witness *is* the recipient,
amount, and salts. Offloading it to a **third-party** proof server hands those secrets to
whoever runs it — defeating the vault's whole guarantee. This is inherent to Midnight's
proof-server model; the privacy-preserving way to go remote is a **self-hosted** proof
server. So the router must weigh **two axes**, not one:

1. **Feasibility** — can this device local-prove this circuit without OOM?
2. **Privacy** — does going remote leak a sensitive witness to an untrusted server?

## Signal 1 — circuit cost is knowable BEFORE proving

Two on-device signals, available before `prove()` is called:

- **Prover-key file size** (already staged in the device keys dir) — a reliable, bimodal
  proxy. For the Private Vault:

  | Circuit | Prover key |
  |---|---|
  | getters, `pvDepositUnshielded` | 22 KB – 280 KB (trivial) |
  | `pvProposeWithdrawal` | 2.8 MB |
  | `pvApprove` / `pvExecute` / `pvRevoke` | **5.2 MB each** (heavy) |

- **`k`** (the circuit's log-size) — the native prover already parses it when loading a key
  (`Loading BLS params k=13`). Peak memory scales ≈ `2^k`. Exposing `k` gives a
  physics-based estimate; key-file size is the zero-plumbing fallback.

  *Gap:* `contract-info.json` carries per-circuit `name / pure / proof / arguments /
  result-type` but **not** `k` or `rows` — so `k` must come from the key/`.bzkir` header or
  a native prover call, not the JSON. (Argument shapes give only a weak lower bound.)

## Signal 2 — the device's real memory budget

`ActivityManager.MemoryInfo` (`availMem`, `totalMem`, `lowMemory`, `memoryClass`) →
a safe local-proving ceiling that scales with the actual phone. A 12 GB flagship can
self-prove circuits a 3 GB device can't; a static threshold would be wrong on both.
Needs a **calibration** step: map (`k` or key size) → estimated peak RSS, measured across
a few reference circuits/devices, with headroom.

## The design — `AdaptiveProofProvider`

Replace the hardcoded `LocalProofProvider` in `MidnightConfig` with
`AdaptiveProofProvider(local, remote?, policy)`. Per transaction:

1. **Estimate cost** — read the circuit's `k` / key size from the unproven tx.
2. **Read the device budget** — `MemoryInfo` → local ceiling.
3. **Apply the `ProvingPolicy`:**
   - `cost < budget` → **local** (fast, private, offline) — the common case, including all
     the cheap circuits.
   - `cost ≥ budget` → can't self-prove safely → **remote**, *gated on trust*:
     - circuit has a sensitive witness **and** server is third-party → **warn / block**
       (offer "point at your own proof server"); never silently leak.
     - server is self-hosted/trusted, **or** the circuit isn't privacy-sensitive → proceed.
4. **Safety net** — attempt local, catch OOM / `null`, fall back to remote **through the
   same trust gate** (so the fallback can't leak either).

Defaults: **local-first**, remote fallback only to a **trusted** server, explicit warning
before any private witness leaves the device. Fully configurable per dApp.

### Per-circuit policy — the cheap near-term win (no new crypto)

Most of the benefit needs only *routing metadata*, not cryptography. Tag each circuit with
a sensitivity + a size hint, and route accordingly. For the Private Vault:

- `pvApprove` → **local** (protect signer identity; the salt/tag is the sensitive part).
- `pvExecute` → **remote-eligible** — in Tier 1 it *already discloses* recipient + amount on
  settlement, so remote leaks almost nothing new; the private part is just the
  threshold/membership check, and the heavy 5.2 MB cost is largely the (public) treasury send.
- getters / `pvDepositUnshielded` → **local** (tiny).

This alone lets the heavy path (`execute`) offload while keeping the identity-sensitive path
(`approve`) on device — most of the win, near-zero privacy risk.

### Self-hosted proof server — the trust gate

The clean answer for a serious deployment: the operator runs their **own** proof server, so
"remote" is not "untrusted." The policy models this as
`proofServerTrust: Trusted | ThirdParty`; a `Trusted` server lifts the leak gate entirely.
Needs config to point the SDK at a chosen proof-server URL per network and mark its trust.

## Fragmentation — the stretch direction (your idea, done right)

The intuition "send the big circuit remote, keep the witness local" **can't work naively**:
the heavy cost *is* the witness math (Halo2/PLONK proving spends its memory on polynomial
commitments/FFTs/MSMs over witness-derived polynomials — you can't do the big MSM without
the secret values). But the *spirit* is real, via **circuit decomposition + commitment
bridging**:

1. Split the circuit into two sub-circuits.
2. **Local, small:** prove "I know witness `w`; here is commitment `C = hash(w)`" — cheap
   (a preimage/membership check), `C` is public output.
3. **Remote, heavy:** the big sub-circuit takes `C` as a **public** input (never `w`) and
   does the expensive *public* computation; the server sees only `C`.
4. **Bridge** via recursion / proof composition so the two verify as one.

Feasibility hinges on: **the heavy part must be expressible over the commitment, not the raw
witness.** Works when the sensitive knowledge is a small check and the expense is public
structure (a big Merkle path, a range proof, a state transition over disclosed data). For
our vault this is *partially* true — `execute`'s heavy work is over recipient/amount that
Tier 1 discloses anyway.

Beyond decomposition, the far frontier is **delegated/masked proving** (blind the witness so
an untrusted server computes on masked values — outsourced MSM/FFT with homomorphic masking)
and **collaborative zk-SNARKs** (MPC over secret-shared witnesses). Both exist in the
literature, are not production-standard, and carry meaningful overhead. Reach here only if
per-circuit routing + self-hosting + decomposition don't suffice.

## Phasing

1. **Phase 1 (concrete, near-term):** `AdaptiveProofProvider` in `MidnightConfig` +
   `ProvingPolicy` with **per-circuit local/remote routing** + memory-aware local ceiling +
   local-first-with-fallback. Unblocks heavy contracts on low-RAM devices with a per-circuit
   privacy policy. (Requires a `RemoteProofProvider` — today only `LocalProofProvider`
   implements `ProofProvider`; remote logic lives only in `core/ledger`'s `ProofServerClient`.)
2. **Phase 2:** self-hosted proof-server config + the `Trusted/ThirdParty` trust gate + the
   leak warning UX.
3. **Phase 3 (research):** circuit decomposition + recursion for true "witness-local,
   heavy-remote" splitting; measure recursion overhead before committing.

## Explicit gaps / open questions (so this design isn't mistaken for complete)

- **Circuit identity from an unproven tx:** does the SDK expose *which* circuit a queued tx
  proves, before proving, so the router can look up its cost/sensitivity? (The native prover
  deserializes it — "Deserialized transaction, 0 tx-specific keys" — so the info exists; it
  needs surfacing to Kotlin.)
- **No `RemoteProofProvider` yet** — must be written (wrap `ProofServerClient`), and the
  witness-leak boundary of the remote call audited.
- **`k` extraction** — from the `.bzkir`/prover header or a native call; not in
  `contract-info.json`. Needs a small FFI/parse.
- **Calibration** — `k` / key-size → estimated peak RSS, per device class, with headroom.
  Empirical; needs a measurement pass.
- **Recursion support** — confirm Midnight's Halo2 stack supports the proof composition
  Phase 3 assumes, and measure the overhead (fragmentation can cost more than it saves).
- **Proof-server discovery & trust** — how a dApp declares its proof-server URL + trust per
  network; default posture (localnet self-hosted vs PreProd hosted).
- **Fallback UX** — what the user sees when a local proof OOMs and the only remote option is
  untrusted (block? warn-and-allow? ask?).
- **Determinism / correctness** — a proof must verify identically whether produced locally
  or remotely; add a cross-check test (same tx, both providers, same on-chain acceptance).
