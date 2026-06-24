# Kuira Dev Hangout — Speaking Script

*Read-aloud lines only. The full deck — slides, diagrams, and the IF-ASKED Q&A answers — is in `KUIRA_DEV_HANGOUT.md`. Read it loose and conversational, like you're talking to people you like — not reading a doc. First person: this is my conviction.*

---

# Beat 1 — What is it (0:00–8:00)

## 1.1 — The thing everyone copied

Okay, so every chain copied the same shape. You install a wallet, and then you "connect wallet" to everything you do. The wallet's this separate app standing at the door, and you walk over to it every single time you need to sign something. And look — today's wallets are good at that. But it's still that shape, right? One wallet in the middle, and a little "Connect Wallet" popup in front of everything.

Here's what I believe — and this is the seed of the whole thing. You shouldn't need a wallet. You need an identity. That's the bet I'm making. And I'm not the only one — plenty of people in the Midnight world feel the same way. But the conviction is mine. And so is the opinion about where that identity actually lives.

## 1.2 — What is a Sigil?

So what's a Sigil? If you've seen Game of Thrones, you already know the word — first episode, five direwolf pups, one for each Stark kid, "the direwolf is the sigil of your house." A sigil wasn't decoration. It was who you were. Go back even further and it's a personal seal — the stamp you pressed into wax on a letter. And that seal said three things at once: I am this person, I authorize this, and I vouch that it's real.

That's already way more than a wallet does. A wallet just holds your stuff. A Sigil proves who you are, says what apps can do for you, and protects your private data. Balance, send, receive — honestly, that's one corner of it. The product is the seal, not the coin purse.

*(define passkey):* Quick definition, because this one comes up a lot: a passkey is the fingerprint-or-face login your phone already uses for apps and websites. No password, no phrase. That's the root of the whole thing.

## 1.3 — The Sigil is in the code

And I want to be clear here — this isn't a marketing metaphor. The Sigil is right there in the code. Apps build against an interface called SigilIdentityProvider. Your identity comes back as a SigilDerivation. It lives in a SigilStateStore. Even the errors talk like this — no identity yet, you get a SigilRequiredException, which is just "hey, forge a passkey first."

And I made that interface swappable on purpose. Identity standards change over the years, so the thing behind the Sigil has to be swappable — without anyone rewriting the apps on top of it. That's why a future Midnight Passport, if that happens, is a swap for Kuira, not a rebuild.

*(closing):* One fingerprint tap, one object back. That's everything an app needs to act under your seal.

---

# Beat 2 — Why I built it (8:00–15:00)

## 2.1 — Identity, not custody

So the question I get the most: isn't this just one of those custody or social-login wallets with extra steps? Nope. And here's the difference.

Those services are custody — you call out to them, and they hold or co-hold your keys on their servers. The Sigil isn't custody. The wallet lives inside the app itself, backed by your passkey and by proving stuff right there on your phone. And it's more than a wallet — it's identity, it's per-app permissions, it's your private state. The cloud only ever sees scrambled, encrypted blobs. It never sees what your data actually is.

## 2.2 — The privacy question

Now here's the deeper one — the question that makes people sit up. If Sigils are these privacy-preserving identities, where does social login even fit? Wouldn't making your social account your identity just break Midnight's whole privacy model?

Yeah, it would. So I don't. The trick is to keep two things apart: identity, and login-and-recovery.

The root of your Sigil is a passkey, sitting in your phone's secure hardware. Your seed comes from that passkey. Your social accounts? They never touch that math. They're not part of how your identity gets built. All the platform does is sync and back up the passkey itself — and that's just a convenience at the account level, off to the side, off-chain, never part of your public identity.

*(land the point):* So, straight at the privacy question: social is never in your public footprint by default. The only time it shows up is if you want it to — like flashing a credential that says "this Sigil controls this handle," for reputation. And even that can be a zero-knowledge proof, so the handle stays hidden. That's disclosure, not identity. The one-liner: social sits behind the Sigil — login and recovery — never as the identity.

## 2.3 — Apps carry the wallet

One more "why," and this one's about shape. The normal model is centralized — one wallet app owns your account, and everybody else has to knock on its door. Kuira goes modular instead.

Step one: an app on its own. A developer drops in my SDK, and just like that, the app can spin up and use a Midnight identity all by itself. No external wallet. The app provides the account.

Step two: now the user installs Kuira. And Kuira doesn't show up as a gatekeeper — it shows up as a provider. It takes that same identity, upgrades it to phone-hardware-grade security, and puts it in one spot, "My Sigil," right next to every other app you've sealed.

Every Midnight wallet comparison chart has this "account abstraction" row marked "not yet." That's my answer to it — and it's lighter, not heavier. The app provides the account; the Sigil makes it trustworthy. Not a better wallet. A different shape.

---

# Beat 3 — What's out of the box (15:00–32:00)

## 3.0 — One Gradle line, then the starter

Okay — what can you actually grab and use today? It's on Maven Central, so it's one line in your build file. Three pieces.

*(the starter):* And if you just want to see the thing run, there's a starter repo — kuira-starter-android. Clone it, build it, run it, five minutes. It's the whole loop in a tiny app: a Sigil identity from your fingerprint, an embedded wallet with balance, a receive QR, a network switch, and a little six-line smart contract with live state. And my floating panel drops two chips right over your app — an identity chip and a wallet chip — so identity and balance are one tap away, and you didn't build a single wallet screen. Four config steps and it's your own dApp.

## 3.0b — The module map

Okay — before we dig into the fun stuff, let me show you what's under the hood. Remember that one import line from a second ago? This is everything underneath it. And it's a real engine, not a demo.

On the left, that's the core — eleven modules, pure Kotlin and Rust, no UI at all. Crypto, identity, the ledger, the connector — that's the machinery.

On the right is the SDK, and that's the part you actually import. One line, and it wires all eleven of those core modules together for you — you never touch them one at a time. Want it headless? That's midnight-sdk. Want the screens already built? That's dapp-ui.

I'll walk you through six of these as flowcharts in a sec — the rest are up here so you can see the real shape of the thing.

*(the feature layer):* Oh — and down at the bottom there's a feature layer: balance, dust, onboarding, send, settings. Those are the screens of the consumer Kuira app, the one I'm still building. Everything above it ships in the SDK today.

## 3.1 — Identity: one tap becomes your whole key world

This is the heart of it. One fingerprint, and you get three separate secrets out of the same passkey: your identity, your wallet seed, your backup keys. Nothing written on paper.

How? A passkey feature called PRF. Here's why I need it: a normal passkey just logs you in — it won't hand you a secret to build keys from. PRF will. Your phone's secure hardware takes your fingerprint plus a label and gives back the exact same secret every time — and the key behind it never leaves the chip. So I feed it three labels — identity, seed, backup — and get three independent secrets back.

*(the one durable idea):* The key idea here: your seed comes from your passkey. Same on every device — and never from anything social.

## 3.2 — On-device proving: proofs in seconds, no server

So Midnight transactions need a zero-knowledge proof — that's math that proves something's true without giving away the secret behind it. Most setups make that proof on a server you have to stand up and run. I make it right on the phone. The way I pulled that off: I took Midnight's Rust proving engine — midnight-zkir — and wired it straight into the app over JNI, compiled native for Android instead of WASM, so it's actually fast on a phone. The secret never leaves the device, and there's no proof server to babysit.

On the phone, in seconds, and the private part never goes anywhere.

## 3.3 — Crypto: your keys and addresses


the crypto itself is the same as the original Midnight SDK. Same derivation, same curves, same address format. I didn't reinvent any of that. What I had to do was bring it to mobile: reimplement it in Kotlin, push the heavy curve math down into native Rust instead of the WASM the web SDK leans on, and wipe every private key from memory the second it's done.



## 3.4 — Transactions: public and private, one model


The model itself is straight from the original Midnight SDK — same public and shielded builders, same shapes. What I had to do for mobile was port all of it to Kotlin and move the signing and the proving onto the phone, so a transaction goes out without ever calling home to a server.

## 3.5 — dApp connector: how apps talk to the Sigil

Okay, this is how outside apps actually talk to your Sigil. The rule's dead simple: reading your state is automatic, but anything that spends money needs your explicit yes.

The API itself I didn't reinvent — it's the exact same ConnectedAPI standard the original Midnight wallets speak, so anything built for a Midnight wallet already understands Kuira. What I had to build for mobile was the way in: a web dApp connects over a WebSocket, a native Android app connects over Binder — the system's own app-to-app channel — and a WebView connects over a bridge I inject. Same standard, three doors.

*(forward-looking):* And that "confirm before you spend" step? That's the same safety idea that scales up to AI agents later — which I'll come back to at the end.

## 3.6 — Recovery: zero words

And this is the payoff of rooting everything in a passkey. New phone. Your passkey syncs over, your fingerprint unlocks it, and because the secret comes from that same passkey, the exact same backup key comes right back. Your encrypted state restores with zero words and zero passwords. No phrase to lose, and no company sitting in the middle who can lock you out.

*(the hard question, head-on):* Now the scary one — what if I lose my phone? Honest answer: you're fine, and it works today. Your passkey syncs to the new phone, your fingerprint unlocks your backup, and you're back in. No company in the middle who can lock you out. And the worse case — losing the account your passkey lives in, too — that's where a handful of trusted friends can help you back in. I can add that later as an option — a backup way in, never your identity.

---

# Beat 4 — Midnight Kicks (32:00–43:00)

## 4.1 — Midnight Kicks

So this is the proof the whole stack holds up under a real app. Midnight Kicks — it's a zero-knowledge penalty shootout. Unity 3D for the game, Kotlin for the wallet and identity, and a Midnight smart contract playing referee. Two players, five rounds, commit-then-reveal so nobody can cheat by peeking. The contract's the impartial ref, and every proof gets made on the players' own phones.

## 4.2 — What it exercises

And it's not a toy. It hits every single module we just walked through. Players get matched by their Sigil, and matches survive the app getting killed and reopened. The contract runs its proofs locally, no server. The contract itself gets deployed at runtime as the ref. And the match state is encrypted and travels across devices — same recovery path I showed you a minute ago.

## 4.3 — Live demo

*(lead-in):* Alright, let me just show you.

*Narrate each step as you do it:*
1. Forge a Sigil with a fingerprint — no password, no phrase.
2. Join a match by deep-link — matched by identity.
3. Commit your move, then reveal one round — the contract's the ref.
4. Show the on-chain result — it's real, it settled on Midnight.

---

# Beat 5 — Call to action (43:00–47:00)

## 5.1 — Where this goes, and the ask

Last thing — and this is where I get most fired up. The Sigil is built for what's coming next: agents. A world of agents moving money around needs the same two things we humans take for granted — a private identity, and a safe way to approve spending. And that's exactly what I built the Sigil for. "Approve this payment" becomes a fingerprint, the proof gets made on the device, and you can tell an agent "spend up to this much a day, and check with me past that." Midnight's leadership talks about this too — trillions of agents, proofs as their language. I'm building the identity layer all of that needs.

So here's my ask. The whole design hangs on one seam — that SigilIdentityProvider interface. Right now it's passkey plus fingerprint. Tomorrow it could be social login, or Midnight Passport, or guardian recovery — all behind the same interface, none of it touching the apps on top. Getting that interface right, so anybody can plug in their own backend — that's where I want help. If you've thought about social auth, recovery, or open wallet standards, come find me, let's compare notes.

## 5.2 — Where I honestly am

really proud of what I built. I am really happy that I did the first thing: Just shipped it.
*(close):* Kuira 1.0 ships as a reference Sigil for the ecosystem — technical folks first, with the full consumer vision right behind it.

---

## Recap — the one-paragraph version (if someone walks in late)

Most chains make you install a wallet and "connect" it to everything. I think you shouldn't need a wallet — you need an identity. Kuira is that: a Sigil — "I am, I authorize, I protect" — that lives inside apps instead of in front of them, signs with a fingerprint instead of a seed phrase, roots in a passkey (never in your social accounts — those just sit behind it for sync and recovery), and proves things right on your phone. Drop in the SDK with one line, clone kuira-starter-android to watch it run, and play Midnight Kicks to see it hold up under a real zero-knowledge app. The seam that makes it future-proof, SigilIdentityProvider, is where I'm asking the community to build with me.
