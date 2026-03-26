# Questions for Charles — Sorted by Priority

**Context:** Prepared from 5 weeks of dev-chat reports, CLI wallet experience,
Kuira development, and the Nightforce AMA / Launch Week video.

**Goal:** Get strategic clarity on where to invest as a community builder,
surface real developer pain diplomatically, and position my work.

---

## Tier 1: Must Ask

### Q1. The Forced Prioritization

> "If you had to pick only ONE of these to ship perfectly in the next
> 6 months — Nightstream, Passport, Intents, MOS, or rock-solid developer
> tooling (compiler, SDK, docs, proof server) — which one would it be
> and why?"

**Why #1:** Sets the tone for everything. His answer tells me where the team's
head is at. If he says tooling, developer experience is genuinely prioritized.
If he says Nightstream or Passport, the vision comes first and community tools
are even more critical. Every other question flows from this.

---

### Q2. Main Quest vs Side Quests

> "Midnight's triangle has three massive sides — PET, Abstraction, Smart
> Compliance — and each side has its own roadmap: Nightstream, MOS, Passport,
> Intents, Capacity Exchange. As a community builder, I see developers getting
> excited about future features while struggling with today's basics — deploying
> a contract, getting test tokens, interacting through Lace. How does the team
> decide what's main quest versus side quest? Is there a north star metric — like
> 'X developers with a working dApp on mainnet by Q3' — that keeps the roadmap
> grounded?"

**Why #2:** Directly addresses the vision-vs-reality gap without being
adversarial. Uses the video game analogy Charles would appreciate. Opens the
door for the developer experience conversation.

---

### Q3. The Developer Experience Paradox

> "You said Midnight 'ended up creating the best framework for agents.' But
> agents need developers first, and developers need working tools. From moderating
> the dev channels, I see the same pattern: a developer arrives excited, hits a
> broken faucet or a Lace error, spends two days debugging infrastructure instead
> of building, and some don't come back. What's the plan to close the gap between
> 'best framework in the world' and 'I can deploy my first contract in under an
> hour'? Is that Midnight's job, or is that where community tooling is expected
> to fill in?"

**Why #3:** This is the pain point question backed by real data. Positions my
CLI wallet as a solution. Also asks the critical framing question: is community
tooling filling a gap or building a permanent pillar?

---

### Q4. The Wallet Ecosystem

> "Lace is the only official wallet, but it's browser-only and has known
> limitations. Meanwhile, community wallets are emerging — my CLI wallet,
> MeshJS experiments, Kuira for Android. Does Midnight want a single reference
> wallet that everything routes through, or a multi-wallet ecosystem with a shared
> connector standard? Because that answer determines whether community wallet
> builders are partners or competitors."

**Why #4:** Existential for everything I'm building. If the answer is "Lace
is the wallet," I need a different strategy. If it's "multi-wallet ecosystem,"
I'm exactly where I should be. This needs a clear answer.

---

### Q5. Mobile — Is There a Different Path?

> "You described the phone's TEE as the foundation for account abstraction —
> keys never leave the secure enclave, biometric approval, social recovery. But
> today the entire Midnight developer experience is desktop-only: Node.js SDK,
> browser-based Lace, Docker proof servers. Does Midnight see mobile as just a
> consumer endpoint that connects to existing infrastructure, or is there a
> vision for mobile-native proving, mobile SDKs, mobile-first development? I'm
> building an Android wallet with Rust FFI for ZK proving — am I ahead of the
> roadmap or aligned with where things are heading?"

**Why #5:** Directly unblocks Kuira decision-making. If mobile is a priority,
I get validation and potentially early access to specs. If not, I know
I'm pioneering and should plan accordingly.

---

## Tier 2: Important — Ask If Time Allows

### Q6. Agents — When Does "First-Class Citizen" Become Concrete?

> "You said AI agents are first-class citizens on Midnight and that the language
> of agents is proofs. I've already built an MCP server that lets Claude interact
> with the wallet autonomously — 24 tools, structured output. But today there's no
> agent-specific infrastructure on Midnight: no agent identity, no autonomous
> spending policies, no intent submission API. What does 'first-class citizen'
> look like concretely in the next 6 months? Is there a spec for how agents
> authenticate, submit intents, and manage keys — or is this still vision-stage?"

**Why:** I've built the thing Charles is describing. I need to know if the
platform will meet me halfway or if I'm building the platform myself.

---

### Q7. Can Community Builders Shape the Standards?

> "The DApp Connector API, the intent protocol, the MOS spec — these standards
> will define the ecosystem for years. Is there a path for community builders
> who are actively implementing these things to participate in the standard-setting
> process? Not just feedback after the fact, but co-design. Because we're finding
> edge cases and making architectural decisions today that the specs should
> probably know about."

**Why:** Concrete ask disguised as a question. You're saying "we're building
this stuff, let us in the room."

---

### Q8. What's Midnight's "Click Moment"?

> "Every platform has a moment where developers go from 'this is interesting'
> to 'I'm building my company on this.' For Ethereum it was DeFi summer, for
> Solana it was cheap NFTs. What's Midnight's 'click moment'? Is it when
> Passport enables compliant DeFi? When agents can transact autonomously? When
> a regulated company deploys their first private contract?"

**Why:** Gets Charles to name the thing that matters most. His answer tells
me where to point my energy.

---

### Q9. The Proof Bottleneck

> "Every transaction on Midnight requires ZK proving — a local proof server
> eating 4GB+ RAM or a remote server. As you scale to millions of users and
> trillions of agents, who proves? Does proving become a decentralized
> marketplace? Do phones eventually prove locally with hardware acceleration?
> Or does Nightstream eliminate the per-transaction proving model entirely?"

**Why:** Nobody's asking about the unsexy infrastructure question that actually
determines whether the vision works at scale.

---

### Q10. What Does Midnight Learn From Cardano?

> "Cardano's developer experience went through painful years — Plutus had a
> steep learning curve, tooling lagged the protocol, some developers left before
> things matured. Midnight is separate, but some patterns feel familiar: powerful
> protocol, vision ahead of tooling, community filling gaps. What is Midnight
> doing differently to avoid the same developer attrition curve? Or is this the
> unavoidable cost of building something genuinely new?"

**Why:** Respectfully direct. Charles has talked openly about Cardano's
struggles. Shows you're paying attention, not just cheerleading.

---

## Tier 3: If There's a Follow-Up Opportunity

### Q11. Who Does the Community Builder Talk To?

> "You mentioned three organizations: IOG, Midnight Foundation, and Shielded.
> Who does the community builder talk to? If I find a bug, who do I report it to?
> If I want to propose a connector standard, where does that go? If I need early
> access to a spec, who do I ask? Right now the answer is often 'ask in Discord
> and hope someone sees it.'"

**Why:** Reports show escalations going cold for 3+ weeks. This addresses it
structurally, not as a complaint.

---

### Q12. Privacy — Opt-in or Default?

> "Zcash learned that when privacy is optional, almost nobody uses it —
> shielded transactions were under 5% for years. Midnight has both public and
> private ledgers. Is Midnight's bet that developers will choose privacy because
> they need it (compliance, IP protection), or does the platform need to push
> privacy as the default path?"

**Why:** Challenges a core assumption. Charles has strong opinions here.

---

### Q13. The Compact Ceiling

> "Developers are already hitting circuit count limits, block size limits, and
> compiler crashes on moderately complex contracts. Where is Compact headed?
> Is ZKIR V3 about removing those ceilings, or is Compact intentionally
> constrained and complex logic should live off-chain?"

**Why:** Design philosophy question. The answer shapes how every developer
architects their dApp.

---

### Q14. Sustainability for Community Builders

> "Community builders are investing serious time — my CLI wallet has months of
> work, Kuira has 185+ hours, other members are building proof server workarounds,
> compatibility guides. What does sustainable community building look like on
> Midnight? Is there a grants program, a revenue model for tool builders, or is
> this expected to be volunteer-driven?"

**Why:** Honest question about whether this is a hobby or a career. Better for
a private conversation than a public AMA — can come across as asking for money.

---

### Q15. The Introduce-Kuira Question

> "I'm building Kuira, an open-source Android wallet focused on AI agents,
> game developers, and dApp devEx for Midnight. I already have a CLI wallet
> with a working DApp connector, an MCP server for AI agents, and a reference
> game using the connector. What's the best way to align with the Midnight
> team on connector standards so community wallets are first-class citizens?"

**Why:** Establishes Kuira directly with Charles. Shows I'm not just
asking — I'm building. Could be woven into Q4 (wallet ecosystem) instead
of asked standalone.

---

## Strategy Notes

- **Lead with Q1 (forced prioritization)** — it's provocative enough to get a
  real answer, not a rehearsed one
- **Q2 + Q3 are a combo** — main quest sets up the developer experience
  question naturally
- **Weave Q15 (Kuira intro) into Q4 or Q5** rather than asking it standalone —
  it's stronger as context for a real question than as a self-introduction
- **"We" = community context** (community builders, dev channels, shared tools).
  **"I" = Kuira / personal projects** (CLI wallet, Kuira, MCP server).
- **Q10 (Cardano mistakes)** is high-risk high-reward — read the room before
  asking it
- **Q14 (sustainability)** is better as a private follow-up, not a public question
