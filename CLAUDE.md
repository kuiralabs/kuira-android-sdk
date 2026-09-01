# Kuira Wallet - Claude Context (Auto-Loaded)

**Purpose:** Entry point for all engineering context. Keep this SHORT so it's always loaded.

---

## Core Mission
Build the Kuira Wallet (Midnight blockchain) for Android following elite engineering practices.

---

## Project Structure

**Current Repository:** `kuira-android-sdk/`
- Production Midnight Wallet implementation
- Multi-module architecture
- Pure Compose UI

**Reference Repository:** a reference architecture sample
- Google's Now in Android sample app
- Use as architectural reference when needed
- Reference for: convention plugins, multi-module patterns, Compose best practices, testing setup

---

## Engineering Guidelines (Read Based on Task)

**When making architectural decisions:**
→ Read `guidelines/ARCHITECTURE_GUIDELINES.md`

**When writing Kotlin code:**
→ Read `guidelines/KOTLIN_GUIDELINES.md`

**When handling crypto/keys/security:**
→ Read `guidelines/SECURITY_GUIDELINES.md`

**When writing tests:**
→ Read `guidelines/TESTING_GUIDELINES.md`

**When building UI/Compose:**
→ Read `guidelines/COMPOSE_GUIDELINES.md`

**When implementing Midnight-specific features:**
→ Read `guidelines/MIDNIGHT_GUIDELINES.md`

**To see guideline updates:**
→ Read `guidelines/CHANGELOG.md`

---

## Key Principles (Always Follow)

1. **Explain WHY before HOW** - Don't just write code, explain reasoning
2. **Test-first when possible** - Write test, make it pass, refactor
3. **Security first** - Never log secrets, wipe keys after use
4. **Clean Architecture** - Domain → Data → Presentation
5. **Challenge assumptions** - If something seems off, say so

---

## Implementation Context (Reference When Implementing)

**Planning Documentation:** the internal docs
- `midnightWallet.md` - Main implementation plan (6 phases, 80-120 hours)
- the internal design notes - Crypto & transaction flows
- the internal design notes - Configuration & state management

**Midnight Libraries (TypeScript SDK):** the Midnight TypeScript SDK
- Reference for understanding Midnight blockchain patterns
- We're porting/reimplementing in Kotlin (NOT using WASM)

**Architecture Reference:** a reference architecture sample
- Google's official modern Android architecture sample
- Multi-module structure with convention plugins
- **Use for:**
  - Convention plugin patterns (`build-logic/convention/`)
  - Module organization (`core/`, `feature/`)
  - Hilt DI setup (`*/di/`)
  - Testing patterns (`core/testing/`)
  - Compose patterns (`core/designsystem/`, `core/ui/`)
- **Don't copy:** Google News-specific features

---

## Collaboration Approach

See `LEARNING_STRATEGY.md` for how we work together:
- Learn by doing
- Ask questions when unclear
- Request diagrams/analogies as needed
- You drive the pace

---

## Current Phase

**Phase 1: Crypto Module (Weeks 5-6, 20-25 hours)**
- BIP-39 mnemonic generation
- BIP-32 HD key derivation
- Schnorr signatures (secp256k1)
- Bech32m address formatting
- Secure storage (Android Keystore)

**Module:** `core:crypto`
**Status:** Setting up structure

---

## General Instructions

- Always explain the "why" behind decisions
- Use concrete examples
- No generic AI phrasing
- Ask for clarification when unclear
- Reference specific guidelines when making decisions
- Reference Now in Android for architectural patterns when needed
