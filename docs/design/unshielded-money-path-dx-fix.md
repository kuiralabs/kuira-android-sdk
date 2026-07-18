# Unshielded money-path DX fix (alpha06)

**Target:** alpha06 · **Origin:** [kuira-sdk-android#4](https://github.com/kuiralabs/kuira-sdk-android/issues/4) · **Status:** Layers 1 + 2 implemented + on-device validated (full pre-release suite pending)

## Status

- **Layer 1 (clear error + precedence) — DONE, validated on-device (2026-07-16).**
  - FFI `check_unshielded_offer_present` (`contract_ffi.rs`) — honors an explicit offer, else returns
    `UNSHIELDED_VALUE_UNFUNDED: …` naming the builder. Rust unit test `unshielded_offer_precedence`
    green (explicit-offer-wins, both branches).
  - Detection inline in the assembler: `receives_unshielded`/`sends_unshielded` from the fallible +
    guaranteed transcripts' `effects.unshielded_inputs/outputs`, computed before the transcripts move
    into the prototype.
  - Kotlin `ContractCallException.UnshieldedValueUnfunded` + marker mapping in `MidnightContract`.
  - `UnshieldedTransferHarnessTest` (offline, on emulator) — unfunded `receiveUnshielded` →
    funding-builder error; unfunded `sendUnshielded` (to a real user) → withdrawal-builder error.
    (Note: send-to-**self** nets to a *receive* effect — use send-to-user for a true send.)
  - Still to do for release: kicks `PaymentMoneyPathTest` end-to-end via a mavenLocal republish (to
    exercise the typed exception through `MidnightContract.call` + confirm the funded path is
    unchanged), then the full unit+instrumented suite.
- **Layer 2 (auto-fund deposit) — IMPLEMENTED (2026-07-16), e2e validation pending.**
  - FFI signal enriched to `UNSHIELDED_VALUE_UNFUNDED:<kind>:<token>:<amount>|<human>` — token+amount
    extracted from the transcript's `unshielded_inputs`/`unshielded_outputs`. Rust test updated + green.
  - `MidnightContract.prepare` two-pass: on a `funding` signal with the policy on + a provider set +
    no caller offer, build the funding offer from the wallet and re-run; the auto-funded offer is
    threaded to the post-prove signing (`effectiveFundingJson`). Withdrawals / policy-off / no-provider
    take the Layer 1 error.
  - `MidnightConfig.configureUnshieldedAutoFund(enabled, provider)` (provider set by the SDK,
    post-construction, since it needs the wallet).
  - `MidnightSdk.Builder.autoFundUnshieldedDeposits(enabled = true)` — default ON (explicit offers
    always win, so on-by-default is safe); wires the provider to `buildUnshieldedFundingJson`.
  - **e2e VALIDATED (2026-07-16)** via mavenLocal republish + kicks `PaymentMoneyPathTest`
    (emulator + localnet): a plain `call("deposit", amount)` auto-funds & lands
    (`totalDeposited == amount`); with `.autoFundUnshieldedDeposits(false)` the same call throws the
    typed `UnshieldedValueUnfunded` naming the builder. On-device signal confirmed as
    `funding:0000…0000:1000000|…` (token = NIGHT, extracted from the transcript).
  - **Fix is complete.** Full unit suite green; instrumented tests that exercise the money path pass.
    The instrumented-gate reds (`RealDustFeePaymentTest`, `SdkRegistrationE2ETest`) are pre-existing
    environmental failures unrelated to #4 — localnet ledger version skew + E2E test hygiene.
  - **alpha06 release requirements (separate from the #4 fix):**
    1. Fix the localnet ledger version skew (node specVersion vs SDK-bundled ledger 8.0.3) so the
       instrumented gate runs clean — the recurring "Custom error: N" root cause.
    2. E2E test hygiene: make `RealDustFeePaymentTest` / `SdkRegistrationE2ETest` skip-when-unavailable
       (`assumeTrue` before the blocking wait), matching `SdkSendNightIsolatedE2ETest`.
    3. Cut alpha06 (`scripts/release.sh`) + post the issue #4 reply.

## Problem

A dApp author writes a standard payment contract — `deposit` uses `receiveUnshielded`,
`withdraw` uses `sendUnshielded` — calls it the obvious way, and every transaction fails at
submit with the opaque node error **`Transaction rejected: Invalid Transaction`**. The circuit
compiles and proves fine; only submission fails. There is no hint about what's wrong.

This is a **DX / discoverability failure, not a broken feature.** The money path itself works
(shipped in #30/#35, present since alpha05). It is simply **opt-in**, and nothing tells the caller.

## Root cause

`receiveUnshielded` / `sendUnshielded` move NIGHT into / out of a contract. For the transaction to
balance, the wallet must attach an **unshielded offer** to the intent (funds in for a deposit, a
recipient output for a withdrawal). That offer is opt-in via two parameters that default to `null`:

- `MidnightContract.call(circuit, *args, unshieldedFundingJson=null, unshieldedWithdrawalJson=null, …)`
  — `core/compact-engine/…/compact/MidnightContract.kt`
- built by `MidnightSdk.buildUnshieldedFundingJson(amount, tokenType?)` and
  `MidnightSdk.buildUnshieldedWithdrawalJson(recipientHash, amount, tokenType?)`
  — `sdk/midnight-sdk/…/sdk/MidnightSdk.kt`

A plain `call(circuit, amount)` supplies neither → no offer → the contract's fallible unshielded
segment underflows → the node rejects the tx. (Background on the offer construction — fallible
segment, sign-after-prove for deposit, recipient-output for withdrawal — is #30/#35 in the
contract-call money-path notes.)

## Current workaround (proven)

The caller builds and passes the JSON:

- deposit: `call("deposit", amount, unshieldedFundingJson = sdk.buildUnshieldedFundingJson(amount))`
- withdraw: `call("withdraw", amount, recipient, unshieldedWithdrawalJson = sdk.buildUnshieldedWithdrawalJson(recipientHash, amount))`

Verified end-to-end on-device (emulator + localnet) with the reporter's exact contract via
`PaymentMoneyPathTest` in `examples/midnight-kicks` (see "Proof" below): a plain deposit reproduces
`Invalid Transaction`; the funded deposit submits and the value lands in the ledger.

## What we can detect

After circuit execution (before submit), the transcript carries the unshielded flows the circuit
declared:

- `transcript.effects.unshielded_inputs` — value the contract **receives** (`receiveUnshielded`)
- `transcript.effects.unshielded_outputs` — value the contract **sends** (`sendUnshielded`)

(FFI: `kuira-crypto-ffi/midnight-ledger/ledger/src/semantics.rs` — the effects maps; also
`structure.rs`.) So the SDK can see "this circuit moves unshielded value" and whether an offer was
supplied.

**Asymmetry that shapes the fix:** `unshielded_inputs` gives `(token, amount)` — enough to fund a
deposit automatically. `unshielded_outputs` gives only `(token, value)` with **no recipient** — the
recipient is a circuit argument the SDK cannot generically identify. So a **deposit can be
auto-funded; a withdrawal cannot** (it needs the recipient the caller already knows).

## Approach — funding-resolution precedence (custom always wins)

Not two exclusive options — a single precedence the SDK applies when a circuit moves unshielded
value. The explicit path is never removed:

1. **Caller supplied an offer** (`unshieldedFundingJson` / `unshieldedWithdrawalJson`) → use it
   **verbatim, never override.** This is the custom/creative lane: custom UTXO selection, batching,
   a **sponsor** (a different wallet funds the deposit, signed by the sponsor's key), a relayer,
   multi-party. Auto-fund must step aside whenever an offer is present.
2. **No offer, and it's auto-fundable** (a deposit — `unshielded_inputs` gives token + amount) →
   **auto-fund from the caller's own wallet** (Layer 2), *when the auto-fund policy is enabled*.
3. **No offer, and it's not auto-fundable** (a withdrawal — `unshielded_outputs` has no recipient;
   only the caller knows it) → **clear error** (Layer 1).

The two build layers implement this precedence:

- **Layer 1 — clear early error (the foundation).** Detect the transcript's unshielded
  `inputs`/`outputs` effect; if no matching offer was supplied (and auto-fund didn't fill it), fail
  fast with an actionable message naming the builder — instead of the node's opaque
  `Invalid Transaction`. Symmetric (deposit + withdraw). Also carries the "honor a supplied offer,
  never override" precedence.
- **Layer 2 — auto-fund the deposit case (on top).** For `receiveUnshielded` with no offer, build
  the funding offer from `unshielded_inputs` so a plain `call("deposit", amount)` just works.
  **Configurable policy** (per-call and/or SDK-level, so teams that want funds-move-explicitly can
  keep it off → they get the Layer 1 error instead). Only ever fills a gap the caller left empty;
  withdrawal stays explicit.

Note the amount auto-fund moves is exactly the circuit's declared receive amount (== the caller's
`amount` arg) — it is not a hidden or extra spend, it is the deposit itself. The design choice is
"explicit vs automatic assembly," not "surprise cost."

## Layer 2 implementation note — it needs a two-pass flow

Auto-fund can't be done in the FFI alone: building the funding offer requires the **wallet's UTXOs
and NIGHT signing key**, which live in the SDK (`MidnightSdk.buildUnshieldedFundingJson`), not the
native assembler. And the amount to fund is only known **after** circuit execution (it's the
transcript's `unshielded_inputs` value). So auto-fund is a two-pass flow in `MidnightContract`:

1. Execute + attempt assembly with no offer → the FFI surfaces "needs funding: token, amount"
   (a structured variant of the Layer 1 signal, only when the auto-fund policy is on).
2. The SDK reads (token, amount), calls `buildUnshieldedFundingJson(amount, token)` against the
   wallet.
3. Re-execute/re-assemble with the funding offer attached (the normal funded path).

Cost: one extra circuit execution per auto-funded deposit (seconds). Withdrawal is never auto-funded
(no recipient), so it always takes the Layer 1 error. This is strictly additive on top of Layer 1 —
the precedence and detection are already in place.

## Sponsoring

A sponsored deposit = the offer is funded by a party other than the tx caller. That is lane #1 (an
explicit, custom offer built from the sponsor's UTXOs and signed by the sponsor's key). Auto-fund
never interferes because an offer is present. Today `buildUnshieldedFundingJson(amount)` funds from
the SDK's OWN wallet; the `unshieldedFundingJson` param is **opaque**, so a dev can already pass a
custom sponsor offer. A future ergonomic helper (e.g. `buildSponsoredFundingJson(...)`) could make
this first-class — out of scope for alpha06, but the precedence above leaves the door open.

## Open decisions

- **Auto-fund default (on vs off).** With explicit-always-wins, auto-fund-on is safe (supplying an
  offer opts out per-call). Default is a policy choice: convenience-on, or explicit-first (Layer 1
  error) with auto-fund opt-in.
- **Detection surface.** The executed transcript's typed unshielded effects live in the FFI
  (`partition_transcripts`); the Kotlin `txParamsJson` only carries raw transcript ops and the
  contract-info.json has no per-circuit unshielded metadata — so detection is FFI-side.

## Touch points

- `core/compact-engine/…/compact/MidnightContract.kt` — `call` / `prepare` (where the error/auto-fund hooks in)
- `core/compact-engine/…/compact/CircuitExecutor.kt` — where the transcript is produced
- `sdk/midnight-sdk/…/sdk/MidnightSdk.kt` — `buildUnshieldedFundingJson` / `buildUnshieldedWithdrawalJson`
- `kuira-crypto-ffi/…/ledger/src/semantics.rs` — transcript `effects.unshielded_inputs/outputs` (detection source)

This is an `sdk/` + likely FFI change → requires approval and a full unit + instrumented run across
all modules before release.

## Proof (regression harness already written)

`examples/midnight-kicks`:
- `contract/src/payment.compact` — the reporter's contract (deposit/`receiveUnshielded`, withdraw/`sendUnshielded`, NIGHT)
- `app/src/androidTest/…/PaymentMoneyPathTest.kt` — fails-before/passes-after on localnet:
  plain deposit → `Invalid Transaction`; funded deposit → `SUBMITTED` + `ledger().getUintBig("totalDeposited") == amount`.

This harness should stay as the acceptance test for the fix: after layer 1, the plain deposit must
fail with the SDK's **clear** error (not the node's opaque one); after layer 2, the plain deposit
must **succeed**.
