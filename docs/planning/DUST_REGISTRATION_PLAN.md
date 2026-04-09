# Dust Registration & Tank Display (feature/dust)

**Status:** Steps 0-4 ✅ Complete | Step 5 ⏳ (Send flow integration) | Step 6 ⏳ (Dust sync optimization)
**Last Updated:** 2026-04-08
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
| 4 | `feature/dust` module — ViewModel & UI | ✅ | DustScreen, DustViewModel, DustUiState, navigation, 14 unit tests |
| 5 | Integration with Send flow | ⏳ | "No dust" → redirect to DustScreen |
| 6 | Dust sync optimization | ⏳ | Replace block-by-block HTTP with WebSocket subscription (see `DUST_SYNC_OPTIMIZATION.md`) |

## Dependency Order

```
0 (v5→v9 fix) → 1 (Rust) → 2 (JNI) → 3 (Kotlin) → 4 (feature/dust) → 5 (Send integration)
                                                            ↓
                                                      6 (Dust sync optimization)
```

## Key Risks

1. ~~**Dust registration may need ZK proof generation**~~ **RESOLVED** — Registration tx goes through proof server (`ProofServerClient.proveTransaction()`), same as unshielded send.
2. **Registration tx needs dust fees itself** — `allow_fee_payment` field bootstraps this (confirmed working in CLI).
3. **Dust generation takes ~7 days** — UI communicates time-to-capacity via `DustBalanceCalculator`.
4. **Dust sync is extremely slow (10+ min)** — Current block-by-block HTTP scanning. Fix planned in Step 6 (WebSocket subscription).

## Key Files

**Rust FFI:**
- `rust/kuira-crypto-ffi/src/dust_ffi.rs` — EVENT_PREFIX fix + new registration functions
- `rust/kuira-crypto-ffi/src/serialize.rs` — `build_dust_registration_transaction()`
- `rust/kuira-crypto-ffi/jni/kuira_crypto_jni.c` — JNI bridge

**Kotlin Core:**
- `core/crypto/.../dust/DustKeyDeriver.kt` — Dust public key derivation (JNI)
- `core/crypto/.../dust/DustLocalState.kt` — Dust state replay (JNI)
- `core/ledger/.../dust/DustRegistrationBuilder.kt` — Registration tx builder (JNI)
- `core/indexer/.../repository/DustRepository.kt` — Dust sync + balance queries
- `core/indexer/.../dust/DustBalanceCalculator.kt` — Time-to-capacity calculations
- `core/indexer/.../api/IndexerClientImpl.kt` — Block-by-block scanning (to be replaced in Step 6)

**Feature Module:**
- `feature/dust/.../DustUiState.kt` — Sealed class: Idle, Loading, Status, NoDust, Registering, RegistrationSuccess, Error
- `feature/dust/.../DustViewModel.kt` — Status check + full registration flow (build→prove→seal→submit)
- `feature/dust/.../DustScreen.kt` — Compose UI with dust tank display + registration form
- `feature/dust/src/test/.../DustViewModelTest.kt` — 14 unit tests (real DustBalanceCalculator, mocked I/O)

**Navigation:**
- `app/.../navigation/AppNavigation.kt` — `Screen.Dust` route at `"dust/{address}"`
- `feature/balance/.../BalanceScreen.kt` — "Dust" button navigates to DustScreen

**Reference:**
- `midnight-ledger/ledger/src/dust.rs:651` — DustRegistration struct reference
