# How EffectStream Works — the story

EffectStream is Paima Engine's successor. This is the mental model, told as the
journey of one player action, so each piece can later be mapped onto mobile.

---

## The one idea

Don't put game logic on a blockchain — it's too slow and expensive, especially on
a ZK chain. Use the chain for the one thing it's uniquely good at: being a
**tamper-proof, ordered, public log of what everyone did.** Then run the actual
rules **off-chain**, in a program that reads that log and computes the current
state. Because the program is deterministic, anyone can replay the same log and
arrive at the exact same state — trustless, but at the speed of ordinary software,
not blockchain consensus.

The whole system is four roles:

- **The chain is a queue** — an ordered list of player inputs. It doesn't run the
  game; it records, in order, "player X submitted this blob."
- **The state machine is the rules** — reads the queue in order and computes what
  each input does to the game.
- **The database is a cache** — the current game state, written by the state
  machine, fast to read.
- **The batcher is the postman** — takes a player's signed input and posts it onto
  the chain, and pays the gas.

A **node** runs all four plus a web server. A **client** (today, a browser)
submits inputs and reads state.

## Follow one move

You're in a match. You tap **"shoot left."**

1. **Your client signs it.** Your wallet signs a little message — "shoot left,
   match 42." That's just a signature; it costs nothing.
2. **The postman posts it.** That signed message goes to the **batcher** — a
   service with its own funded account. The batcher submits it to the chain by
   calling a tiny, game-agnostic contract whose entire job is to **emit an event**
   carrying your opaque blob. The batcher pays the gas. Your move is now entry
   #1,337 in the chain's ordered log — permanent, public, un-reorderable by you.
3. **The sync sees it.** A **sync** service is watching the chain. It notices the
   new event and pulls it in.
4. **The rules run.** The **state machine** takes "shoot left," finds the rule the
   developer wrote for it, and updates the match in the database —
   deterministically. No randomness, no clock, no calling out to an API; the same
   inputs always produce the same result.
5. **The cache updates.** The **database** now holds the new match state.
6. **Everyone sees it.** The **node** pushes the update to every connected client
   over a live socket, and serves it on demand over a normal web request. Your
   screen — and your opponent's — re-renders. The move "happened."

Notice what the chain did and didn't do. It recorded "this player submitted this
blob, in this position in the order." It did **not** run the game. The game ran
off-chain, in milliseconds. Anyone who doubts the result can replay the same log
and check.

## Why it's fast

The expensive, slow part of any blockchain — consensus, finality, and on Midnight
the proving — only happens to **log the input**, which is a cheap event emission.
The actual game logic is plain off-chain compute. Reading state is just reading a
database. So a move feels like "post a tiny event + run some code," not "wait for a
smart contract to execute on-chain."

## Why you can trust it

Two properties. **Determinism:** the rules can't use randomness, wall-clocks, or
external calls, so the log fully determines the state — the operator can't
fabricate an outcome, because anyone can replay and catch a lie. **A public,
ordered log:** what happened is on-chain for all to see. The residual trust is
narrow — a malicious operator can **censor or reorder** by withholding
submissions, but can't invent state. (That residual is exactly what the harder
pairing patterns in the Paima doc tighten up when money is at stake.)

## Multi-chain, in one sentence

The sync can watch **several chains at once** and merge their events into a
**single ordered stream** (ordered by wall-clock time). The state machine sees one
unified list of inputs, no matter which chain each came from. That's what lets a
game take cheap, frequent **moves from a fast chain** and **stakes from Midnight**,
and reconcile them in one rule set.

## Where Midnight fits

Midnight is one of the chains the sync watches. The Midnight contract is a tiny
opaque writer (write a blob to a ledger slot); the sync reads it through the
**Midnight indexer** — the public ledger only, not the shielded/ZK side — and when
a move must be posted to Midnight, the batcher's Midnight adapter drives the
**proof server** and submits the Compact call. Same external Midnight
infrastructure Kuira's native code already talks to.

## What a developer actually writes

Strikingly little on-chain. The contracts are game-agnostic (emit or store opaque
blobs). The real work is off-chain TypeScript:

- **State-transition functions** — the rules: "when a 'shoot' input arrives, do
  this to the match."
- **A grammar** — the names and shapes of the inputs.
- **Config** — which chains and which contract events feed which rules.
- **A few custom API routes and a frontend.**

Building a game is mostly writing game logic in TS plus thin contracts — not
writing complex on-chain programs.

## The seams where mobile plugs in (for the mapping later)

In this whole story the **phone is a client** — it signs inputs and reads state.
Two seams are where the mobile version gets interesting:

1. **The postman.** The batcher exists because a *browser* user has no funded
   wallet to pay gas, so a service pays it for them. A **Kuira phone already has a
   funded on-device wallet.** So on mobile the phone could be its **own** postman
   for the Midnight leg — sign and submit its own move, no batcher — removing an
   operator from the picture.
2. **The rules + cache.** The state machine and database are a server today, and
   EffectStream doesn't package them to run on a phone. But because the rules are
   deterministic and re-derivable, "could a phone run its own light state machine
   and trust no operator at all?" is a real, harder question to weigh later —
   versus the simpler "the phone is a thin client to a node we run."

That's the whole machine. The mobile plan is just mapping each role — postman,
rules, cache, client — onto "a server we run," "native Kuira," or "on-device."
