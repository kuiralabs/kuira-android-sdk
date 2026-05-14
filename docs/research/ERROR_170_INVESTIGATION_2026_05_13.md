# Error 170 — May 13 investigation notes

**Status:** Bug isolated, fix not landed. Investigation suspended at end of long debugging session.
**Bug location (REVISED — see "Update 1" below):** somewhere upstream of `balance_ffi.rs` —
most likely `kuira-crypto-ffi/src/contract_ffi.rs` (contract-CALL tx assembly path).
**Impact:** Contract *call* (`joinMatch`, `commitBatch`, `revealBatch`) submissions fail
with `Invalid Transaction (Custom error: 170)`. Contract *deploy* submissions
succeed with the same balance_ffi + dust + node.

## Update 1 — balance_ffi is NOT the bug

After capturing `ERR170_INPUT_HEX` and `ERR170_OUTPUT_HEX` log lines from a real
match (see `/tmp/err170.log` from the May-13 session), the data shows:

| Step                          | Deploy (✅)               | Join (❌)                  |
|-------------------------------|---------------------------|----------------------------|
| Input proven tx size          | 11,774 bytes              | 5,169 bytes                |
| Goes through `balance_ffi`    | yes                       | yes                        |
| Fee from ledger params        | 0 → calculated 1 speck    | 0 → calculated 1 speck     |
| Dust UTXO count               | 3                         | 3                          |
| Dust spend prove time         | ~0.7s                     | ~0.6s                      |
| Balance + seal completes      | yes                       | yes                        |
| Output tx prefix tag          | `pedersen-schnorr[v1]`    | `pedersen-schnorr[v1]`     |
| Submitted to node             | yes → finalized in block  | yes → rejected 170         |

Both txs run the **exact same** balance_ffi code path (verified by log timestamps
24s apart in the same MatchManager session). balance_ffi produces a valid tx for
deploy and an invalid tx for join. Therefore the difference must already be
present in the **input** to balance_ffi.

The two inputs come from different FFI functions:
- Deploy → `contract_assemble_deploy_tx` (in `contract_ffi.rs`)
- Join   → `contract_assemble_contract_call_tx` or equivalent (also in
  `contract_ffi.rs`, or in `transaction_ffi.rs` or `prove_ffi.rs`)

**Next session's investigation target moves from balance_ffi.rs to whichever
FFI function builds the proven `joinMatch` tx.**

---

## Definitive isolation

| Layer | Status |
|---|---|
| Localnet node + indexer (`docker ps`) | ✅ Healthy. Block production normal. |
| Alice wallet on localnet | ✅ 10,000 NIGHT, 159 DUST registered, 1 UTXO |
| CLI `mn transfer alice→alice 1 NIGHT` | ✅ Finalized. tx `0032baf8aac96af34390954c317d069a057603a7316e5c91beeb1a81a3b66205c3` |
| Android app → same wallet → same node | ❌ Error 170 on every circuit submission |

**The CLI and the app use the same wallet seed (BBoard's "alice" hardcoded `TEST_SEED`)
and connect to the same node and indexer.** Yet CLI's TS-based local tx construction
succeeds and our Rust FFI's local tx construction fails. The single architectural
difference is *how the balanced transaction is constructed*.

This matches prior memory: *"Error 170 definitive findings - Roots are correct;
issue is in balance_ffi.rs tx construction (fee? merge? signing?)"*

---

## Pre-isolation rabbit-holes (rule-out evidence)

These were investigated and rejected:

- **AAR drift between yesterday's working build and today's failing build.** Both
  AARs built from identical source: `balance_ffi.rs` last touched 2026-05-10,
  `kuira-crypto-ffi/Cargo.lock` last touched 2026-04-14, no SDK Kotlin commits
  between builds.
- **`adb pm clear` on the app + restart.** Made it worse — fee fell back to
  `INITIAL_PARAMETERS` (29T specks) → error 138 (same family as 170).
- **StatePoller concurrent FFI access.** Disabled the poller; the error still
  reproduces. Not the regression. Still a real future concern (friction log #7).
- **Seed wipe / shared SecureRandom / `withContext(IO)` in the new MatchManager.**
  Stashed all uncommitted Kotlin changes; rebuilt against `7b758b7` (the last
  commit that worked yesterday) — error 170 reproduces against it too. Not the
  regression. Code-review fixes are committed in `60bba3d`.

---

## Where the bug is, and likely shape

`kuira-crypto-ffi/src/balance_ffi.rs` constructs the dust-fee intent and merges
it with the proven contract tx. The CLI path uses the TS facade
(`midnight-wallet/packages/facade/src/index.ts:646` →
`midnight-wallet/packages/dust-wallet/src/v1/Transacting.ts:499`) which calls
`Transaction.fromPartsRandomized` (`midnight-ledger/ledger-wasm/src/tx.rs:246`).

Side-by-side of the two paths:

```
TS                                          Rust FFI
─────────────────────────────────────────   ─────────────────────────────────────
Transacting.balanceTransactions             balance_proven_transaction_impl
  Intent.new(ttl)                             Intent { … manually populated … }
  intent.dustActions = DustActions(...)       dust_intent.dust_actions = …
  Transaction.fromPartsRandomized(…)          let dust_segment_id =
                                                OsRng.gen_range(2..u16::MAX)
                                              Transaction::new(network_id,
                                                {dust_segment_id: intent}, …)

facade.finalizeTransaction(unprovenTx)      proven_dust_tx = dust_tx.prove(…)
  unboundTx = provingService.prove(tx)
  finalizedTx = unboundTx.bind()            // bind() ≈ seal()

facade case 'FINALIZED_TRANSACTION':         sealed_original = proven_tx.seal(OsRng)
  finalizedBalancing = finalize(balancing)   sealed_dust     = proven_dust_tx.seal(OsRng)
  return originalTx.merge(finalizedBalancing)sealed_tx = sealed_original.merge(&sealed_dust)
```

The high-level patterns match (seal-each, then merge-finalized). The bug is
sub-pattern — likely one of:

1. **`Intent` field defaults.** TS calls `LedgerIntent::new(&mut OsRng, …, ttl)`
   (per `ledger-wasm/src/intent.rs:241`) but I could not find the actual 8-param
   constructor in `midnight-ledger/ledger/src/structure.rs` — only `Intent` as a
   struct (line 844-851). Either the ledger crate version is newer than my
   checkout, or the constructor sets internal state in a way the Rust FFI's
   manual struct-population is missing.
2. **`binding_commitment` initialization.** TS path is wrapped by
   `LedgerIntent::new` which takes an RNG; FFI uses `OsRng.gen()` for the
   commitment directly. If TS does something other than a uniform-random
   PedersenRandomness here, the binding math may diverge.
3. **DustSpend construction / signing.** The FFI's
   `Created DustSpend from UTXO 0: v_fee=1` (line 326) wraps spends in
   `DustActions`; TS does the same shape but through different generic
   constructors. Possible signature-field default drift.
4. **TTL semantics.** FFI uses `timestamp_secs + 1800`. TS receives a `ttl` Date
   from the caller. Verify these are the same value not just same units.

---

## Reproducer plan (next session — do not skip)

Goal: pure-Rust reproducer for error 170 that does **not** require Android. With
that, the diagnosis loop drops from "rebuild AAR → reinstall → run match →
extract log → fail" (~10 min) to "cargo test" (~5 sec).

1. **Get the failing tx hex.** Add one `info!` line in `balance_ffi.rs` right
   before `Ok((result_hex, current_state))` that prints `result_hex`. Rebuild
   AARs once. Run a match. Capture the hex from logcat.
2. **Submit those bytes through plain JSON-RPC** to `ws://localhost:9944` from a
   throwaway Rust binary. If it returns error 170, we have a non-Android
   reproducer. (This rules out anything in the Android networking stack.)
3. **Capture a working tx for diff.** The `mn transfer` succeeded earlier;
   query its bytes from the chain by hash
   (`0032baf8aac96af34390954c317d…`) or capture them at submit time by
   running `mn` with verbose logging and grepping the WS frames.
4. **Deserialize both via `tagged_deserialize`** in a Rust test. Compare the
   `Transaction` struct fields (intents map keys, intent fields,
   binding_commitment magnitudes, dust_actions content). The diff is the bug.
5. **Once located, fix in `balance_ffi.rs`** (or in the manual `Intent { … }`
   construction), add a regression test that calls
   `balance_proven_transaction_impl` end-to-end and asserts node acceptance.

This **should not** require deep knowledge of Pedersen math up front — the
byte-level diff narrows the suspect surface dramatically before any crypto
analysis.

---

## What to avoid next session

- Touching Kotlin in `midnight-kicks`. The Kicks code is fine; isolated tests in
  `60bba3d` cover the state machine and parser. Don't speculate Kicks-side.
- `adb pm clear` as a "fix" — it triggers the `INITIAL_PARAMETERS` fee
  fallback (friction log #1) and produces a different error (138) that's
  symptomatic of the same root cause, but masks it.
- Restarting the localnet without re-running `mn dust register` for alice (her
  registration is fresh on this localnet incarnation; check `mn dust status`
  before assuming the wallet is funded).

---

## Quick-start commands for next session

```bash
# Health check the chain + wallet
mn balance mn_addr_undeployed18mj9eclnzussedhnvj99hdqug7n0kwsutj8dz5ez7edtwx4a60dss2s64k
mn dust status
docker ps | grep midnight

# Reference for working tx
mn transfer mn_addr_undeployed18mj9eclnzussedhnvj99hdqug7n0kwsutj8dz5ez7edtwx4a60dss2s64k 1 --wallet alice

# Failing path: rebuild and run
cd /Users/norman/Development/android/projects/kuira-android-wallet/examples/midnight-kicks
./build-kicks.sh
adb shell am start -n com.midnight.kicks/.KicksActivity
adb logcat -s KuiraCrypto MatchManager NodeRpcClient

# FFI source
$EDITOR /Users/norman/Development/android/projects/kuira-android-wallet/kuira-crypto-ffi/src/balance_ffi.rs
# TS reference
$EDITOR /Users/norman/Development/midnight/midnight-libraries/midnight-wallet/packages/dust-wallet/src/v1/Transacting.ts
```
