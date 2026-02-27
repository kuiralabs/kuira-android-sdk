# Dust Registration & Tank Display (feature/dust)

**Status:** In Progress
**Prerequisite for:** Phase 4B-Shielded (Steps 1-8)
**Why:** Send flow fails — event version mismatch (v5→v9) + no dust registered. Makes Kuira self-sufficient (no Lace needed).

---

## Steps

| # | Step | Status | Notes |
|---|------|--------|-------|
| 0 | Fix event prefix v5→v9 | ✅ | `dust_ffi.rs:488`, `IndexerClientImpl.kt`, `RealDustFeePaymentTest.kt` |
| 1 | Rust FFI — dust registration serialization | ✅ | `build_dust_registration_transaction()` in `serialize.rs` — test with sig verification passes |
| 2 | JNI bridge for registration | ✅ | `nativeBuildDustRegistrationTransaction` in `kuira_crypto_jni.c` |
| 3 | Kotlin DustRegistration wrapper | ✅ | `DustRegistrationBuilder.kt` in `core/ledger/.../dust/` |
| 4 | `feature/dust` module — ViewModel & UI | ⏳ | DustScreen, DustViewModel, DustUiState, navigation |
| 5 | Integration with Send flow | ⏳ | "No dust" → redirect to DustScreen |

## Dependency Order

```
0 (v5→v9 fix) → 1 (Rust) → 2 (JNI) → 3 (Kotlin) → 4 (feature/dust) → 5 (Send integration)
```

## Key Risks

1. **Dust registration may need ZK proof generation** — TypeScript SDK calls `wallet.finalizeRecipe()`. May need proof server or Rust FFI addition.
2. **Registration tx needs dust fees itself** — `allow_fee_payment` field should bootstrap this, needs verification.
3. **Dust generation takes ~7 days** — UI must communicate this clearly.

## Key Files

- `rust/kuira-crypto-ffi/src/dust_ffi.rs` — EVENT_PREFIX fix + new registration functions
- `rust/kuira-crypto-ffi/jni/kuira_crypto_jni.c` — JNI bridge
- `core/crypto/.../dust/DustLocalState.kt` — Kotlin wrapper pattern
- `feature/send/` — Blueprint for feature/dust module
- `midnight-ledger/ledger/src/dust.rs:651` — DustRegistration struct reference
