# Charles Hoskinson — Midnight Launch Week (Public YouTube)

**Date:** March 23, 2026
**Source:** Charles Hoskinson YouTube channel, "Midnight Launch Week" (streamed live, ~26 min)
**Video context:** Public announcement of Midnight federated mainnet launch

---

## Parsed Transcript

Hi, this is Charles Hoskinson from warm, sunny Colorado. Always warm, always sunny, sometimes Colorado. Today is March 23rd, the week of the 23rd and it shall be known as the Midnight week.

We're having a lot of fun. We're doing a lot of work and we're slowly methodically getting Midnight turned on, which takes quite a bit of time, quite a bit of effort. But this time around we got a lot of partners and I'm very excited that those partners and collaborators — Efferves and others — are working with us, and I wanted to make a video talk about what's going on for the next few weeks. Kind of give everybody a sense of what it takes to land the shuttle.

### Launching Cryptocurrency = Landing the Space Shuttle

So launching cryptocurrency, it's kind of like landing the space shuttle. If you guys remember back in the day, you'd have some usually badass pilot from the Navy or Air Force and be flying this thing and comes down at 30,000 miles an hour and just somehow lands on a runway like a plane does and no one dies, which is truly extraordinary when you really think about it, but they make it look like it's pedestrian.

### Phased Launch: Testnet → Federated Mainnet → Decentralized

Basically when you launch a cryptocurrency, there's usually a process of guarded staging. And there's layers to it.

**Phase 1 — Testnet:**
In the beginning there was the testnet and you learn a lot from it. You gain a lot of ideas from it and then eventually you break a lot of stuff. The goal of the testnet is to break and to learn. The goal of the testnet is to train people to do stuff with it — whether it be developers or SPOs or other people. So this in various formats ran for about a year give or take and we upgraded it many times. We demonstrated we could hard fork, for example. Developers were on it and they were having fun with it and all this other stuff. But at some point, you got to leave the nest. The bird actually has to be kicked out and if it doesn't die on the pavement, then you get to a federated launch.

**Phase 2 — Federated Mainnet (NOW — Week of March 23):**
Now you could go straight to pure decentralization and in 2026 that's a super bad idea. But usually testnet goes to a federated launch. So you have this concept of FNOs (Federated Node Operators) and you may have noticed that this time around instead of Emurgo and Input Output, there's people like:
- **Google Cloud**
- **Telegram**
- **Moneygram**

There's a lot of really amazing operators and basically they run the network. At this stage they make the blocks step by step and process transactions and they initiate hard forks and they process them — all kinds of regular network operations.

Back in the Cardano days you may have remembered the Byron era. There we had Ouroboros BFT and that was actually three entities — the Cardano Foundation, Emurgo, and IOHK (before we changed the name to IOG). That was during the Byron era and it worked really well. Then during the Shelley era, we had the incentivized testnet (ITN) and many of you learned how to be stake pool operators. We transitioned gradually from Byron to Shelley using this concept of a D-parameter.

### Why Midnight Is More Complex

Midnight's a little bit more complicated because Midnight actually simultaneously operates on Cardano and Midnight as its own network. There's this idea of some relationship between the two networks. The value-carrying asset lives on Cardano (NIGHT), and the operational asset lives on Midnight (DUST).

Actually, Midnight has **four address structures**. It has a **private and public ledger**. And actually it has a **layered consensus algorithm: Aura, Grandpa, and BEEFY**. And BEEFY is the Cardano bridge side. So there's actually a lot more stuff going on under the hood which makes this much more like the space shuttle than when we were launching Cardano.

### This Week: Federated Mainnet Goes Live

What's happening this week is the federated launch and this is basically the mainnet network turning on step by step.

Every day we have a **go/no-go meeting** and based upon what we're getting back from the federated mainnet nodes, they tell us a whole bunch of stuff — a lot of testing vectors — but there's all these checkpoints.

**Guarded launch:**
- The goal is to get to a stable network — blocks are being made (genesis block, next block, next block...)
- It is a **mainnet, not a testnet** — no backsies ideally
- It's **guarded** — transactions and dApp deployment are restricted
- You restrict the deployment of dApps and transactions (you can do that in the federated side)

**Consumer side — dust generation starts:**
- Dust generation is working
- NIGHT generation from the Cardano contracts is done
- Glacier drop period is over
- Dust is starting to be generated
- Best represented by an **update to Lace** — you'll be able to see dust generation

### What Comes Next: Lifting the Guard

Once you have a stable network, the FNOs (Google, Telegram, others) say "Yeah, we're happy with it. Things are looking good. All these things are running." Then you gradually — like the D-parameter — **lift the guard** and start deploying **waves of apps**.

So you go from just dust generation → **Lace + dApps** → you can actually start using experiences.

**Guardedness goes DOWN, app count goes UP.**

But of course to use the network you need to have dust. So that's the first step, then dApps.

### Timeline: Next Few Weeks

- **Week of March 23:** Getting the network turned on with partners. Going through a daily checklist. Things are moving very positively.
- **Lace update** coming soon
- **Next week:** Expect guardedness to drop and some dApps get deployed — meaning you can USE them. It's not a testnet, it's the mainnet.
- This will be **hard-forked systematically** like Cardano was, to add more and more capabilities.

### What's Running Now

Right now it's: can you achieve consensus, get the performance things we're looking for, verify **Plonk and Halo 2** are working the way we think they are. **Compact** is running — it's basically **Zcash with smart contracts**.

- Dust generation (a very complex thing) is working correctly
- dApps can be deployed, they're actually running, people can interact with them

### Roadmap: What Gets Unlocked Over Time

As we get further along and these heartbeats come in:
- **ZKIR V3** — composable contracts
- A lot of optimizations
- Test harnesses for **Nightstream** (lattice-based folding system)

**Quarterly cadence** of upgrades:
- December: Launched the NIGHT token
- Now: Federated mainnet
- Each update expands the set of possible dApps dramatically

**Upcoming capabilities (step by step):**
- Dust generation ← (now)
- **Capacity exchange** — enables cross-chain transactions
- **Midnight Passport system** — the account abstraction system
- **Intents** — cross-chain
- And more

### Phase 3: SPOs and Governance

If you're a stake pool operator: **Phase 3** is when you get involved. We're entering Phase 2 with the federated mainnet, right on schedule.

Phase 3 will:
- Create an **incentivized testnet (ITN) for SPOs** — start making blocks similar to how Google and Telegram are doing now
- In parallel: begin **governance experiments** (voting, etc.)
- Similar to Cardano's Shelley + Voltaire, but with **parallelism** this time

**Why governance takes time:**
Midnight was distributed with a Glacier drop — huge benefit (lots of people), but the problem is those people haven't decided whether they want to be good faith members or just dump their NIGHT. Nobody paid for it. Need time for the community to self-select before turning on governance. Need time for an ambassador class to form.

Governance will spiral — gradually getting more complex over time:
- Start with experiments
- Then members-based organization
- Then full on-chain governance

### The Triangle: What's Launching When

Charles draws the triangle again:

**PET (Privacy Enhancing Technology):**
- Launching now: the ZK side
- Later: MPC + TEE components (outsourceable computation on off-chain, working with Google infrastructure among others)

**Abstraction (two parts):**
- Account abstraction → **Midnight Passport** program
- Chain abstraction → **CAKE** (intents)

**Smart Compliance:**
- Once you have the passporting system → go from DID to selective disclosure system
- Example: ZKMe

The triangle is something you turn on step by step — that's the utility side.

### What's Already Built

What's truly impressive is what's already been built:
- Running **Kachina** (the protocol)
- Running **Plonk, Halo 2**
- Have **Compact** (the smart contract language)
- This is what's shipping with the federated mainnet
- **First time ever**: Zcash with smart contracts
- Multi-address structure, ability to separate things — incredibly impressive

### Key Quote: 8 Years of Work

> "We started Midnight more than eight years ago. It left the R&D two years into it. So for about six years, we've been building and building and building and building, driving ourselves absolutely insane."

- Token launched in December
- Most traded — almost a **billion dollars in volume** on launch day
- Listed on **Binance** — first Cardano asset to do that
- Large distribution: exchange airdrops, scavenger hunt, Glacier drop — **over a million users** (verifiable via KYC on exchange wallets)

### AI Agents: First-Class Citizens on Midnight

> "The magic of Midnight and the magic of what we're doing is that we're giving privacy in a safe way to the masses, and it's a natural continuation of what Satoshi promised. And the other side of it is it's the first time ever that artificial intelligence agents get to be first-class citizens."

**The language of agents is proofs, not words:**
- When agents meet each other, they don't have non-verbal communication, human trust structures, or all these other things
- If Agent A asks for something, Agent B will say: "Prove it to me"
- A ZK system is how you do that at scale
- Combine with MPC and TEE for scale across legacy and Web3
- There will be **trillions of agents** running on the web every year
- Midnight creates the **fabric for all of them** to work together and participate in commerce

**Intents + AI Agents:**
- When you think about abstraction — you want to tell people what you want to do, somebody has to figure out how to solve it
- That solver right now is a dumb smart contract or a human
- **The solver of the future is artificial intelligence**
- In many cases, you own it — it's actually yours (little NFT that represents it)
- That AI needs to enter into the pits with all the other AIs and it's going to need **proofs to trust everything**
- That's effectively what Midnight is doing at scale, with all the rules you set

> "So it's quite timely and it's quite fortuitous that after all this work and research, we inadvertently created the best framework in the world for agents to live and get things done."

### Closing

> "While I am Norwegian, I'm also Italian. Dad's Norwegian, mom's Italian. And every single thing that we do in Italy, we do with love."

> "Welcome the fourth generation of cryptocurrencies. Blockchains can now keep a secret, and because of that, blockchains can become mainstream."

---

## Key Facts for Kuira Wallet Development

| Fact | Source | Impact |
|------|--------|--------|
| Federated mainnet launching week of March 23, 2026 | Direct statement | We're building for a LIVE mainnet now |
| FNOs include Google Cloud, Telegram, Moneygram | Direct statement | Institutional validators = serious network |
| Dust generation is working on mainnet | Direct statement | Our dust implementation should work against mainnet |
| Lace update coming for dust visibility | Direct statement | We can verify our dust display matches Lace |
| dApps deploying next week (after March 23) | Direct statement | DApp connector (Phase 7) becomes time-sensitive |
| Capacity exchange coming (cross-chain tx) | Roadmap item | Phase 8 agent runtime should prepare for this |
| Midnight Passport = account abstraction | Direct statement | Confirms AA path for Kuira |
| Intents coming (cross-chain) | Roadmap item | Intent submission API needed |
| ZKIR V3 + composable contracts next | Roadmap item | More complex dApps = more connector methods needed |
| Nightstream test harnesses coming | Roadmap item | Proof system may change — abstract proving interface |
| Consensus: Aura + Grandpa + BEEFY | Direct statement | BEEFY = Cardano bridge side |
| Four address structures (private + public ledger) | Direct statement | Confirms our multi-address implementation |
| AI agents are "first-class citizens" on Midnight | Direct statement | Validates our agent-first strategy |
| "Language of agents is proofs" | Direct statement | ZK proofs = agent communication primitive |
| Solvers of the future = AI | Direct statement | Intent + solver architecture is agent-native |
| Phase 3: SPO ITN + governance experiments | Roadmap | 6-12 months timeline for full decentralization |
| Running Kachina, Plonk, Halo 2, Compact | Technical detail | These are the proof systems our FFI targets |
| Almost $1B volume, listed on Binance, 1M+ users | Market data | Large user base = demand for mobile wallet |
