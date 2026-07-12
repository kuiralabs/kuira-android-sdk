# Kuira SDK alpha05 — Contract Robustness

The 6-line counter passed; real contracts don't — issue #3, and Tushar's tifosi app
hand-patching compiled JS to pass a `Uint`. Three spikes confirmed the diagnosis
and, more importantly, that the fragile patterns can be **removed**, not just
hardened. This is the plan to do that.

## One root cause, confirmed three ways

Kuira's contract path **hand-mirrors Midnight's vocabulary and guesses types** —
ignoring the sources of truth it already has on disk. Each spike found the same
shape and the same cure: stop mirroring, use the source of truth.

| Layer | What it does today | The source of truth it ignores |
|---|---|---|
| Native transcript assembly | a hand-written JSON parser re-implementing the ledger's types | the upstream **serde `Deserialize`** impls (already present) |
| Bridge argument marshalling | dispatch by Kotlin runtime type, build JS **source strings** | the compiled **ABI** (`contract-info.json`, already on disk) |

## The three eliminations

**1. Native — delete the hand parser, deserialize via upstream serde.** `Op`, `Key`,
and `StateValue` already implement serde `Deserialize` upstream, and the JS shim
already emits that JSON shape — `serde_json::from_value` works. The lone obstacle is
one validation gate on `AlignedValue` (the JS zero-pads, failing the normal-form
check). Fix: have the JS emit normal-form values, then **delete the parser and let
serde deserialize.** A new upstream variant then either deserializes or throws a
loud error — it can never silently lag. (Pending a build confirmation; the
round-trip test below is the proof.)

**2. Bridge — codegen a typed Kotlin API from the ABI.** The Compact type vocabulary
is a **closed set of 10 self-describing types** (`Uint`, `Field`, `Boolean`, `Bytes`,
`Enum`, `Vector`, `Tuple`, `Struct`, `Opaque`, `Alias`); the "SDK types" (`Maybe`,
`Either`, addresses, coin keys) are all just `Struct`s with typed fields, so one
recursive walker covers everything. The plugin already reads the ABI; generate a
typed wrapper per contract (`castVote(optionIdx: BigInteger)` instead of
`call("castVote", Any)`). The `Long`→`bigint` bug becomes a **compile error**, not a
device failure. Codegen is a thin typed **facade** over a corrected runtime
converter (so `Opaque`/edge cases keep a home).

**3. Transport — real values instead of source strings.** The bridge binding
(`quickjs-kt`) can pass real JS values — strings, arrays, objects, `UByteArray →
Uint8Array` — which removes the escaping / injection class outright. One honest
limit: the binding has no `BigInt`, so `Uint`/`Field` still cross as a decimal string
reconstructed with `BigInt(s)` in JS (what the witness path already does —
injection-safe). Real values fix escaping and make marshalling explicit; bigint stays
a typed shim by necessity.

## The complete gap list (so we are 100% sure)

**Native (transcript).** `Key::Stack` missing (the #3 bug); `StateValue::Map` and
`StateValue::BoundedMerkleTree` missing — on **both** the FFI and the JS shim. `Op`
is fully covered today but `#[non_exhaustive]`, so it can drift. Plus a silent
`Op::Noop` fallback (corruption trap → should hard-error), gas using wall-clock time
(violates chain-anchored-time), and no JS↔native version-coherence check. **All of
these vanish when the hand parser is replaced by serde.**

**Bridge (types), both directions:**

| Type | Argument today | Return today | alpha05 |
|---|---|---|---|
| `Uint` / `Field` | wrong (JS number) | ordinal ok | `BigInteger`, typed shim |
| `Boolean` | ok | ok | keep |
| `Bytes<N>` | wrong (`Int8Array`) | ok | `UByteArray → Uint8Array`, length-checked |
| `Vector<T>` | partial (numbers; arrays throw) | ok | typed, recursive |
| `Struct` (incl. addresses, `Maybe`) | partial, unvalidated | untested | generated data class, validated |
| `Enum` | missing | lossy (ordinal) | generated `enum class`, symbolic |
| `Opaque<"string">` | ok | ok | `String` |
| `Opaque<other>` | — | — | passthrough + runtime |
| return value | — | **dropped by `call()`** | decoded via ABI `result-type` |

## As-built supported surface (shipped)

The generated `<Alias>Contract` facade covers every type a Compact **circuit
signature** uses, both directions:

| Type | Argument | Return |
|---|---|---|
| `Uint<N>` | `BigInteger`, range-guarded to the ABI `maxval` (u8/u64/u128 …) | `BigInteger` |
| `Field` | `BigInteger` (unbounded — no guard) | `BigInteger` |
| `Boolean` | `Boolean` | `Boolean` |
| `Bytes<N>` | `ByteArray` | `ByteArray` (hex- and array-tolerant) |
| `Enum` | `enum class`, marshalled `CompactEnum(ordinal)` | symbolic via `entries[ordinal]` |
| `Vector<T>` | `List<T>`, elements marshalled/guarded, recursive | `List<T>`, recursive |
| `Tuple` | positional `TupleN` (non-empty tuple args skipped today) | positional `TupleN`, shape-deduped |
| `Struct` | `data class` + `toCallArg()` | `data class` + `decode<Struct>` |
| `Opaque<"string">` | `String` | `String` |
| `Alias` | transparent to its inner type | transparent |
| `Maybe` / `Either` / addresses / coin keys | a `Struct` of the above → same path | same |

Circuits are emitted as: `call()` (write → receipt) for every circuit; `read<Name>()`
(on-chain view) for a value-returning `pure:false` circuit; `local<Name>()` (readLocal,
no deploy) for a `pure:true` circuit. A shape the walker can't yet marshal/decode (a
nested `Vector<Vector<Enum>>` arg, a non-string `Opaque` return) is **gracefully
skipped** with a facade KDoc note — never emitted broken.

Not generated (tracked separately): typed **ledger accessors** for the `ledger`
section's ADTs (`Map`/`Set`/`Counter`/`MerkleTree`) — dApps read those via
`handle.ledger()` today.

## The regression guard — tests that can't lag

1. **Native serde round-trip (Rust, no chain).** Serialize every upstream `Op` /
   `Key` / `StateValue` variant and assert it deserializes. Driven by the upstream
   types, so a new variant fails the build mechanically. (No such test exists today.)
2. **Conformance — generator (shipped).** A kitchen-sink ABI exercising the full type
   surface *combined* (a struct reused as arg + return + tuple-element + vector-element,
   a `Vector<Uint>` arg, a `Tuple<Uint,Struct>` return, an enum field, a pure
   `Bytes->Bytes` circuit) asserts the one recursive walker keeps them all consistent in
   a single file. The generated facades also compile as part of every dApp's build
   (`compileDebugKotlin` against the published plugin) — the on-device compile gate.
   Remaining tier: kitchen-sink contracts DEPLOYED on localnet with every circuit
   exercised end-to-end (the runtime conformance harness).
3. **Real-app acceptance.** tifosi and the starter green, and tifosi's
   `patch-voting.js` **deleted** — the proof the fix reached the real world.

## The principle

Both layers are driven by the source of truth — upstream serde natively, the ABI on
the bridge — so the next Compact change either compiles / deserializes or fails
**loudly**, never silently lags. The conformance green-list becomes the
supported-feature list, which closes the docs gap issue #3 flagged. Pin the toolchain
as one coherent set (`compactc 0.31` / `compact-runtime 0.16` / native-ledger `8.0.3`).

## Decisions / dependencies

- **Codegen needs two build dependencies** in the contract plugin — the AGP API
  (`compileOnly`) and a codegen library (KotlinPoet, `implementation`). This is a
  dependency change and needs sign-off.
- **The native serde path changes the JS emitter** (normal-form values) — small and
  contained, but it touches the shared shim; the round-trip test is the gate.

## Honest open items

- Native serde reuse is *structurally* confirmed, not yet byte-verified through a
  compile — the round-trip test is what proves it.
- bigint stays a typed string-shim (the binding has no `BigInt`); that's by
  necessity, not a gap.

## Acceptance gate

tifosi runs every contract — voting, payment, and a real unshielded transfer — with
no hand-patched JS, on localnet and PreProd.
