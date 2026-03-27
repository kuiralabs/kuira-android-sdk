# Kuira Agent Store — Platform Vision

**Date:** 2026-03-26
**Author:** Norman / MobWeb3
**Status:** Draft — Consolidated from brainstorm session
**Builds on:** `KUIRA_VISION_V1.md`, Privacy-First Agent Gaming Ecosystem doc

---

## Table of Contents

1. [Core Thesis](#1-core-thesis)
2. [Platform Architecture](#2-platform-architecture)
3. [AIP — Agent Interface Protocol](#3-aip--agent-interface-protocol)
4. [Agent Runtime Environment](#4-agent-runtime-environment)
5. [Agent Store / Marketplace](#5-agent-store--marketplace)
6. [SDK Strategy](#6-sdk-strategy)
7. [Business Model](#7-business-model)
8. [Product Strategy & Sequencing](#8-product-strategy--sequencing)
9. [Relationship to Kuira Wallet Roadmap](#9-relationship-to-kuira-wallet-roadmap)
10. [Pending Topics](#10-pending-topics)

---

## 1. Core Thesis

**The Agent Store is a general-purpose marketplace for AI agents that operate on encrypted data, prove correct computation, and preserve user data sovereignty.**

Not a game agent marketplace. Not a DeFi bot marketplace. A **domain-agnostic platform** — like an App Store, but for agents. Games, DeFi, compliance, personal finance, and agent-to-agent coordination are all verticals that plug into the same standard.

The differentiator that makes this possible: **Midnight's ZK infrastructure.** Every agent operates on encrypted inputs, produces verifiable outputs, and never exposes the user's raw data or the creator's proprietary strategy. No other blockchain can do this today.

### Why Privacy Enables the Agent Economy

Today, users won't trust AI agents with sensitive data (finances, health, strategy, personal information) because they fear surveillance and exploitation. If agents operate on fully encrypted data via ZK proofs, FHE, and MPC, users gain the trust to participate. This unlocks a data and behavioral flywheel that privacy-blind competitors cannot access.

**Privacy doesn't block data sharing — it enables it.**

### Charles Hoskinson's Validation

> "It's the first time ever that artificial intelligence agents get to be first-class citizens."

> "The language of agents, the language of AI — it's not verbal, it's proofs. When agents meet each other... Agent B will say, 'Prove it to me.' So a ZK system is how you do that at scale."

> "There are going to be trillions of agents running on the web every year and things like Midnight create that fabric for all of them to work together and participate in commerce."

---

## 2. Platform Architecture

MobWeb3 builds the **platform** (infrastructure, standards, marketplace). First-party products prove the platform. Third-party partners build on top. Platform fees generate revenue regardless of which vertical wins.

### Three Layers

```
Layer 3 — Products (Games, DeFi tools, compliance apps)
           MobWeb3 builds: Gaming vertical (Fog, Arena)
           Partners build: DeFi, compliance, personal finance, etc.

Layer 2 — Agent Store (Marketplace)
           Agent registry, discovery, purchase, rating
           Domain schema registry
           On-chain verification and dispute resolution

Layer 1 — Kuira (Infrastructure)
           Wallet (crypto, signing, payments, identity)
           Agent runtime (WASM executor)
           AIP standard (protocol specification)
           Midnight blockchain (ZK proofs, smart contracts)
```

### Platform Economics

```
MobWeb3 / Kuira
├── Builds:  AIP standard, agent runtime, marketplace infra, wallet
├── Owns:    Gaming vertical (first-party, proves the platform)
├── Enables: DeFi, compliance, personal finance (third-party partners)
└── Revenue: Platform fees on every agent transaction
```

---

## 3. AIP — Agent Interface Protocol

### Design Philosophy

The AIP defines the **envelope**, not the **content**. It's domain-agnostic — each vertical publishes its own Domain Schema that agents in that vertical conform to.

### Agent Computation Models

| Model | How It Works | Best For | Trade-off |
|-------|-------------|----------|-----------|
| **ZK Proof** | Agent runs computation, produces mathematical proof of correctness | Deterministic strategies (decision trees, heuristics, game solvers) | Trustless but expensive for complex models |
| **TEE Attestation** | Agent runs inside Trusted Execution Environment, hardware attests correct execution | Complex AI models (neural nets, LLMs) | Cheaper but requires hardware trust |
| **Hybrid** | TEE for execution + ZK for selective properties | Best of both — pragmatic bridge | More complex implementation |

The AIP supports both proof types. ZK is the long-term ideal. TEE bridges the gap until ZK is efficient enough for complex AI agents.

### Agent Format

Agents are **WASM binaries** — deterministic, portable, sandboxed. Same input always produces the same output. Can run on any platform (mobile, server, browser, decentralized compute) with identical results.

Why WASM:
- Deterministic execution (critical for proof verification)
- Portable across platforms (phone, server, browser)
- Sandboxed (agent can't access anything outside its inputs)
- Compact already compiles to WASM-compatible circuits on Midnight

### AIP Envelope (Universal)

```
AgentManifest:
  agent_id:           bytes32          # on-chain NFT
  domain:             string           # "defi", "gaming", "compliance", ...
  schema_version:     semver           # which domain schema version
  computation_commit: bytes32          # WASM hash or TEE enclave measurement
  proof_type:         ZK | TEE | Hybrid
  resource_bounds:    ComputeBudget    # CPU, memory, time limits
  pricing:            PricingModel     # one-time, rental, per-execution

ExecutionRequest:
  game_id:            bytes32          # context identifier
  turn:               u64             # sequence number
  encrypted_state:    bytes           # encrypted to agent owner's key
  action_space:       ActionSchema    # what actions are legal (domain-specific)
  deadline:           timestamp       # must respond by

ExecutionResponse:
  action:             Action          # the chosen action (domain-specific)
  proof:              AgentProof      # ZK proof or TEE attestation
  agent_id:           bytes32         # which agent computed this
  nonce:              bytes32         # prevents replay
```

### Domain Schema (Per Vertical)

Each vertical defines its own input/output types. The AIP envelope wraps them.

```
Domain Registry
├── gaming/
│   ├── fog-strategy-v1      (map state → strategic move)
│   ├── fog-battle-v1        (battle state → tactical action)
│   └── arena-v1             (grid state → move)
├── defi/
│   ├── portfolio-rebalancer-v1
│   ├── yield-optimizer-v1
│   └── arbitrage-executor-v1
├── compliance/
│   ├── tax-reporter-v1
│   └── sanctions-screener-v1
├── personal/
│   ├── budget-analyzer-v1
│   └── spending-categorizer-v1
└── ... (community registers new domains and schemas)
```

### Verification (On-Chain)

- Midnight smart contract (Compact) verifies proofs
- Accepts: (state_commitment, action, proof)
- Returns: valid / invalid
- Never sees the agent's strategy or the user's raw data
- Dispute resolution: misbehaving agents get slashed

---

## 4. Agent Runtime Environment

### Three Execution Contexts

| Context | When | Where It Runs | Latency | Example |
|---------|------|---------------|---------|---------|
| **Real-time** | Active user session | On-device (phone, PC) | Sub-second | Agent assists during gameplay |
| **Offline** | User is away | Server or decentralized compute | Seconds to minutes | Agent manages portfolio overnight |
| **Evaluation** | Before purchase | Sandboxed (device or cloud) | Batch | Test agent against historical data |

### Architecture

```
┌─────────────────────────────────────────────────┐
│ Agent WASM Binary (the portable unit)           │
│ Deterministic: same input → same output         │
│ Self-contained: no network calls, no I/O        │
└──────────┬──────────────┬───────────────────────┘
           │              │              │
    ┌──────▼──────┐ ┌────▼──────┐ ┌────▼──────────┐
    │ On-Device   │ │ Cloud     │ │ Decentralized  │
    │ Runtime     │ │ Runtime   │ │ Compute        │
    │ (Kuira/PC)  │ │ (AWS/GCP) │ │ (Future)       │
    └──────┬──────┘ └────┬──────┘ └────┬───────────┘
           │              │              │
           └──────────────┼──────────────┘
                          │
                   ┌──────▼──────┐
                   │ Proof       │
                   │ Submission  │
                   │ (Midnight)  │
                   └─────────────┘
```

The agent IS the WASM binary. The runtime is just where it executes. Because WASM is deterministic and sandboxed, the same binary produces the same output regardless of where it runs. The proof proves this.

### Kuira's Role in the Runtime

1. **On-device runtime** — embeds a WASM executor for real-time agent execution. Lightweight, no server needed.
2. **Wallet for agent operations** — when agents run server-side, they connect to Kuira via the connector SDK for payments, signing, and identity.

---

## 5. Agent Store / Marketplace

### Two-Sided Marketplace

**Supply Side (Agent Creators):**
- Developers, quants, data scientists, game AI researchers
- Build agents conforming to published domain schemas
- Monetize via sales, rentals, or per-execution fees
- IP (strategy, model weights) stays private — only proof of correct computation is visible

**Demand Side (Agent Users):**
- Gamers wanting AI assistance
- DeFi users wanting automated portfolio management
- Businesses needing compliance automation
- Other agents composing capabilities (agent-to-agent)

### On-Chain Registry

```
Agent Registry (Midnight smart contract)
├── Agent metadata (name, creator, domain, schema version)
├── Computation commitment (WASM hash or TEE enclave ID)
├── Pricing model (one-time purchase, rental, per-execution)
├── Reputation (ratings, execution count, dispute history)
└── Revenue split (creator share, platform fee)
```

### Marketplace Flywheel

```
More domain schemas → more agent creators → more agents
→ more users → more revenue for creators → more creators
→ community proposes new domains → cycle accelerates
```

---

## 6. SDK Strategy

### Short-Term: Strategy A (Thin Client)

The wallet connector is a thin client — DApps and agents connect to Kuira for wallet operations. All wallet logic stays in Kuira. Same pattern as the CLI wallet's `midnight-wallet-connector` npm package, ported to Android.

### SDK Products

```
1. kuira-aip-sdk (core standard)
   - AIP envelope types (agent manifest, execution request/response, proof)
   - Domain schema definition toolkit
   - Agent packaging (WASM compilation + commitment generation)
   - Proof generation helpers (ZK and TEE)
   - THE STANDARD — published as spec + reference implementation

2. kuira-agent-runtime
   - WASM executor for running AIP-compliant agents
   - Embeddable in any environment (mobile, server, browser)
   - Encrypted input/output marshaling
   - Proof generation orchestration

3. kuira-connector-sdk (wallet connection)
   - Connect to Kuira wallet for payments, signing, identity
   - Thin client (Strategy A)
   - Used by apps and agents that need wallet operations

4. Domain-specific SDKs (built on kuira-aip-sdk)
   - kuira-game-agents — schemas + helpers for gaming vertical
   - Community-built: defi-agents, compliance-agents, etc.
```

### Strategy B (Embeddable Wallet) — Future Evaluation

An embeddable SDK that includes full wallet capabilities (crypto, indexer, ledger) as a library. This would let any app embed Midnight wallet capabilities without Kuira installed. To be evaluated after platform traction is proven — the market will signal if this is needed.

---

## 7. Business Model

Platform fee on agent transactions. Details TBD — separate document.

Key revenue streams to explore:
- Percentage of agent sales/rentals
- Per-execution fees for cloud-hosted agents
- Premium marketplace features (featured listings, analytics)
- Enterprise licensing for private domain schemas
- Compute infrastructure fees (if MobWeb3 hosts runtimes)

---

## 8. Product Strategy & Sequencing

### Approach: Vertical Slice

Prove the full stack with one domain first, then open the platform.

```
Step 1: AIP v0.1 + Reference Domain (Gaming)
  - Define AIP spec through building a concrete product
  - Simple game (Arena: 1v1 strategy with ZK fog of war)
  - 3-5 reference agents of varying capability
  - Prove the full loop: create → publish → buy → execute → verify

Step 2: Open Agent Creation
  - Publish AIP v0.1 SDK
  - Marketplace MVP for gaming domain
  - External creators build agents
  - Iterate AIP based on creator feedback

Step 3: Second Domain + Platform Generalization
  - Partners bring DeFi or compliance vertical
  - AIP proven to generalize across domains
  - Domain schema registry opens to community

Step 4: Fog + Flagship Products
  - Full grand strategy game built on proven AIP
  - Launches with pre-existing agent ecosystem
  - Showcases platform capabilities at scale
```

### Why Gaming First

- Lower stakes than DeFi (game tokens, not real portfolios)
- Failure modes are fun, not catastrophic
- ZK fog of war is a compelling, novel demo
- Attracts developers and players simultaneously
- Proves the platform without regulatory complexity

---

## 9. Relationship to Kuira Wallet Roadmap

The Agent Store builds on top of the existing Kuira wallet phases:

```
EXISTING ROADMAP (wallet):
  Phase 1  (Crypto)          ✅ Complete
  Phase 4A (Sync Engine)     ✅ Complete
  Phase 4B (WebSocket/UTXO)  ✅ Complete
  Phase 2  (Unshielded Tx)   ✅ Complete
  Phase 2F (Dust)            ✅ Complete
  Phase 4B-S (Shielded Bal)  → Next
  Phase 3  (Shielded Tx)     → Next
  Phase 5  (DApp Connector)  → Next
  Phase 6  (UI Polish)       → Ongoing

VISION PHASES (from KUIRA_VISION_V1.md):
  Phase 7  (Full DApp Connector)   — ConnectedAPI as Android Service
  Phase 8  (Agent Runtime)         — Background service, policy engine, x402, MCP
  Phase 9  (WASM dApp Host / MOS)  — Depends on Midnight specs
  Phase 10 (Game SDK)              — Game-specific integration

AGENT STORE PHASES (new):
  Phase 11 (AIP Specification)     — Agent Interface Protocol v0.1
  Phase 12 (Agent Runtime SDK)     — WASM executor, embeddable
  Phase 13 (Agent Store MVP)       — On-chain registry, marketplace UI
  Phase 14 (Gaming Vertical)       — Arena game, reference agents
  Phase 15 (Platform Opening)      — External creators, domain registry
```

**Dependencies:**
- Phase 8 (Agent Runtime) provides the foundation for Phase 12 (Agent Runtime SDK)
- Phase 7 (DApp Connector) provides the wallet connection for Phase 11 (AIP)
- Phase 10 (Game SDK) evolves into Phase 14 (Gaming Vertical)
- Phases 11-15 can begin design/spec work in parallel with Phases 7-10 implementation

---

## 10. Pending Topics

Deep-dive threads to explore in future sessions:

### Technical

- [ ] **AIP v0.1 Specification Draft** — Formalize the protocol. Define exact types, serialization format, proof interface, versioning strategy.
- [ ] **Marketplace Smart Contracts** — On-chain agent registry, NFT minting for agents, fee collection, dispute resolution, slashing. All on Midnight using Compact.
- [ ] **WASM Runtime Architecture** — Which WASM engine (Wasmtime, Wasmer, browser-native)? Sandboxing model. Memory limits. How agents interact with encrypted state.
- [ ] **ZK Proof Design for Agent Verification** — What exactly does the proof prove? How to verify "correct computation" without revealing strategy. Circuit design considerations.
- [ ] **TEE Integration Path** — How agents run in TEEs. Attestation flow. NVIDIA/Intel/ARM TrustZone options. Charles's confidential compute consortium alignment.
- [ ] **Domain Schema Standard** — How verticals define their input/output schemas. Versioning. Backward compatibility. Community governance for new domains.

### Product

- [ ] **Arena Game Design** — The simple 1v1 strategy game that proves the platform. Rules, mechanics, what makes it fun. ZK fog of war implementation.
- [ ] **Fog Game Design** — Full grand strategy game. Offline agent management. Multiplayer. Economy. This is a separate PRD.
- [ ] **Marketplace UX** — How users discover, evaluate, and purchase agents. Rating system. Agent performance metrics. "Try before you buy" sandboxing.
- [ ] **Creator Experience** — What does it feel like to build an agent? Dev tools, testing, deployment, monetization dashboard.

### Business & Strategy

- [ ] **Competitive Landscape** — GIZA (gizatech.xyz), Autonolas, Fetch.ai, Virtual Protocol, SingularityNET. How this differs. Moat analysis.
- [ ] **Revenue Model Deep Dive** — Fee structure, pricing tiers, freemium vs premium, compute cost pass-through.
- [ ] **Go-to-Market** — Who is the first user? Crypto-native gamers? Strategy enthusiasts? DeFi power users? Developer community?
- [ ] **Partnership Strategy** — Which communities/teams build the DeFi vertical? Compliance vertical? How to attract third-party domain builders.
- [ ] **Regulatory Considerations** — Agent marketplace in different jurisdictions. Agent liability. Compliance agents and their regulatory status.

### Cryptography & Research

- [ ] **Katz & Lindell Study Integration** — How the cryptography textbook maps to implementation decisions (from Privacy-First Agent Gaming doc).
- [ ] **Nightstream Integration** — When lattice-based proofs become available, how does the AIP adapt? Post-quantum agent verification.
- [ ] **FHE for Agent Inputs** — Can agents operate on fully homomorphically encrypted data? Performance implications. Which operations are feasible.
- [ ] **MPC for Multi-Agent Coordination** — Multiple agents jointly computing without revealing individual strategies. Solver coordination protocol.

---

## Appendix: Key References

- **Kuira Vision V1:** `docs/planning/KUIRA_VISION_V1.md`
- **Privacy-First Agent Gaming Ecosystem:** Brainstorm document (Norman + AI agent session, March 25 2026)
- **CLI Wallet Connector:** `/Users/norman/Development/tech-moderator/midnight-wallet-cli/packages/connector`
- **CLI DApp Connector Server:** `/Users/norman/Development/tech-moderator/midnight-wallet-cli/src/lib/dapp-connector.ts`
- **Midnight Starship (reference game):** `/Users/norman/Development/tech-moderator/midnight-starship`
- **Charles Hoskinson Nightforce AMA:** Parsed in `KUIRA_VISION_V1.md` Section 1
- **Charles Hoskinson Launch Week:** Parsed in `KUIRA_VISION_V1.md` Section 2
- **GIZA (competitive reference):** gizatech.xyz
