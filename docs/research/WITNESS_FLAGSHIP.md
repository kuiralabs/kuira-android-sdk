# Witness — Flagship Product Concept

Flagship app and SDK concept for Midnight DevRel growth. Targets Web2 mobile developers, not crypto-native developers. Substrate is Midnight; the substrate is invisible to the developer and to the end user.

Companion docs: [PAIMA_DUAL_CHAIN_PAIRING.md](./PAIMA_DUAL_CHAIN_PAIRING.md), [FEE_ABSTRACTION_MODELS.md](./FEE_ABSTRACTION_MODELS.md).

---

## Strategic frame

DevRel growth bottleneck for Midnight is not "supply of use cases" — it is **conversion of unaware developers into evangelists**. The Web3-native developer market is small, saturated, and already aligned with other chains. The market that is large, unsaturated, and reachable is **Web2 mobile, AI, and indie developers**.

These developers will not adopt a "privacy blockchain." They will adopt a **drop-in SDK that solves a concrete problem in their stack**, the way they adopted Stripe, Firebase, Twilio, Auth0, and Algolia. In every one of those cases, the underlying substrate is invisible to the customer. Stripe customers do not learn about interchange. Firebase customers do not learn about Spanner. Midnight is a substrate. The flagship is the product that hides it.

**Core narrative for the new audience:**

> Your phone already knows everything about you. On-device AI can derive any claim about you. Selective disclosure lets you prove just that claim to anyone — with nothing else leaking.

No Web3 vocabulary. No "blockchain", "ZK", "wallet", "trustless", "decentralized", "on-chain" anywhere above the fold on any customer-facing surface. Those words appear only deep in the "how it works" section of the docs, the way Stripe says "we run on AWS."

## What we are building

**Witness** — a consumer Android app, plus an Android SDK that drops the same capability into any other app.

**Consumer app (Witness):**

- Open the app, take a photo
- The photo is captured under secure-enclave attestation (raw sensor data, time, optional location, all signed by phone hardware)
- On-device AI extracts content claims from the capture (subject inference, no-obvious-tampering check, scene classification)
- A selective-disclosure credential is minted on Midnight binding the capture to the claims
- The user gets a shareable photo with a verifiable "real" badge
- Anyone receiving the photo can verify the badge with one tap and sees only the fields the photographer chose to disclose

**Developer SDK (working name: Attest):**

- Three-line integration in any Android app
- Replaces existing photo-upload flow with a verifiable-photo flow
- Server-side: receives the credential instead of (or alongside) the raw bytes
- Drop-in verifier UI components for the receiving side

## Why this concept wins

- **Universal pain.** Generative AI broke trust in photos in 2024–2026. Every dating, marketplace, news, insurance, real-estate, and social app has this problem today.
- **Visceral demo.** Side-by-side: real photo with badge vs. AI-generated photo without badge. Anyone gets it in 5 seconds. The 30-second video writes itself.
- **Maps to existing investment.** Native Android (Camera2 + Keystore attestation), on-device AI (Gemini Nano / MediaPipe), Midnight SDK already underway. No new platform commitments.
- **Defensible composition.** C2PA cannot do selective disclosure. Apple/Google wallets are vendor-locked. Pure ZK chains have no phone-hardware story. The stack requires all three layers and only Midnight has the disclosure primitive native.
- **Multiple revenue lanes.** The same SDK serves dating apps, marketplaces, insurance claims, journalism, real estate, e-commerce, social, legal e-sign — each a separate go-to-market.
- **Zero crypto vocabulary required.** Reachable by developers who will never touch a wallet.

## The binding problem (technical core)

The fundamental question is: given a photo, how does any verifier find the right credential on Midnight? EXIF metadata alone is insufficient — it is stripped by Twitter, Instagram, screenshots, and most re-upload paths.

Witness solves this with **three binding layers**, used in priority order. As long as the photo has not been substantially altered, at least one layer survives.

### Layer 1 — EXIF/XMP pointer (fast path)

A short identifier written into the image metadata at capture. Carries the credential ID and a verification URL.

- Survives: in-SDK sharing, AirDrop, iMessage, WhatsApp, metadata-preserving channels
- Lost: Twitter, Instagram, screenshots, save-then-reupload

### Layer 2 — Perceptual watermark (screenshot fallback)

A low-bit-rate, ML-decodable signal embedded in the pixels at capture. Invisible to humans, recoverable by a small extraction model.

- Survives: screenshots, mild compression, light crops, format conversion
- Lost: heavy filters, aggressive cropping, AI upscaling

### Layer 3 — Perceptual hash registry on Midnight (last-resort lookup)

A perceptual hash of the original image is registered on Midnight as part of the credential. Verifier computes the pHash of the unknown photo and queries the registry for nearby matches.

- Survives: nearly everything short of substantial editing
- Lost: heavy editing, generative reupscale — which is the correct failure mode

The verifier tries layers in order: EXIF first (fast), watermark next (medium), pHash registry last (slower but most robust). The mental model: **the photo is not the credential; the photo is a query key into a registry of credentials, and the SDK uses three different query keys layered for robustness.**

## Selective disclosure as the differentiator

The credential on Midnight carries:

- Hash of original bytes (exact-match verification)
- Perceptual hash (content-addressed lookup)
- Secure-enclave signature
- Claim fields (timestamp, optional location, AI-derived claims)
- Revocation status

The photographer chooses which claim fields are public, which stay hidden, and which are revealed only to specific verifiers under specific conditions. This is the property no incumbent matches:

- C2PA: all-or-nothing metadata, no selective disclosure
- Apple/Google Wallet: vendor-locked, only issuer-signed credentials
- Pure ZK chains: cryptography exists, but no phone hardware integration, no AI extraction
- Onfido/Persona/Veriff: store the raw data, transfer liability rather than eliminating it

## Privacy of the registry itself

A naive `pHash → credentialId` registry is a surveillance vector — anyone with a photo could query whether it is in the registry, leaking usage. Midnight's selective disclosure means the registry can be implemented as **private set membership**: a verifier proves they hold a photo whose pHash is in the registry without revealing which photo, and the registry returns the matching credential only to authorized requesters. A normal developer never sees this layer — `Attest.verify(photo)` just works — but it is the reason this architecture is uncopyable on chains without programmable selective disclosure.

## Honest limitations

To preserve credibility we name these out loud in materials:

- Heavy editing defeats all three binding layers. This is the correct failure mode — a substantially altered photo should lose its badge.
- Perceptual-hash threshold has a tuning tradeoff: too tight loses badges to innocent compression, too loose admits adversarial collisions.
- Watermarks add small capture-time CPU cost.
- Trust root is the phone's secure enclave; a compromised device key can produce false attestations until revoked.
- The badge proves "this photo was captured on a real device at this time." It does not prove the *subject* of the photo is what the photographer claims it is.
- Channels that strip EXIF and compress aggressively still lose Layer 1 and may degrade Layer 2.

## SDK shape (concept, not implementation)

Three primitives, each with one-line developer ergonomics:

- **Capture-time attestation** — produce a verifiable credential alongside a photo
- **Verify-time check** — given any photo, return the credential (or absence) and the disclosed claims
- **Disclosure control** — let the user adjust which claim fields are public after the fact

Plus drop-in UI components (Compose for Android, SwiftUI for iOS later) for the badge display and the verify modal.

The SDK never surfaces Midnight, wallets, keys, gas, or any blockchain concept to the integrating developer. Setup is an API key, a Gradle dependency, and three lines.

## Branding and positioning

Brand the SDK separately from Midnight. Candidate names: **Attest**, **Witness**, **Vouch**, **Stamps**, **ClaimKit**. The domain reads like a Web2 SaaS landing page:

```
Prove things about your users without storing their data.

A drop-in mobile SDK for verifiable photos, age gates,
anti-bot detection, and identity claims. On-device by
default. GDPR-safe by construction.
```

The Midnight reference is one sentence in the "how it works" page, three clicks deep.

## What ships in the first cycle

In dependency order:

1. **`attest.dev` landing page** (or equivalent) — Web2-native copy, zero crypto vocabulary, three concrete use case examples, free tier signup
2. **Attest SDK for Android** — the three primitives, drop-in integration, real docs
3. **Witness consumer app on Play Store** — the flagship demonstration, downloadable by anyone, normal-user UX
4. **Verifier web page** — paste a Witness badge URL, see what was attested

## Adjacent product lanes the same SDK enables

The same Attest primitive serves a long list of verticals once the photo-provenance flagship establishes the brand:

- Anti-deepfake liveness for video calls and DMs
- Age and jurisdiction gates without storing date-of-birth
- Anti-bot verification stronger than CAPTCHA
- Verified marketplace listings
- Verified insurance claim photos
- Verified journalism sources
- Verified real-estate property photos
- Verified user-generated reviews

Each is its own go-to-market lane. None requires a new product — only a new docs page and a new sample app.

## What this implies for the DevRel operation

If Witness is the flagship and Web2 developers are the target audience, the DevRel operation shifts shape:

- Sponsor mobile developer conferences (droidcon, Mobile World Congress), not crypto hackathons
- Publish on Hacker News, Indie Hackers, r/androiddev, not crypto Twitter
- Measure SDK installs and Play Store apps shipped, not GitHub stars on Compact
- Hire DevRel from Stripe / Firebase / Twilio / Auth0 lineage, not from crypto DevRel
- Compare in materials against Onfido / Persona / reCAPTCHA / AWS Rekognition, not against Aztec / Aleo
- Build docs that demonstrate "five-line replacement for Onfido," not docs that explain zero-knowledge proofs

## Open questions

- Which brand name and domain to commit to before any public materials
- Pricing model: free tier shape, paid tier triggers, whether Midnight transaction costs are passed through or absorbed
- Whether iOS ships in parallel or follows after Android validates the concept
- Which adjacent vertical to chase as the second SDK lane after photo provenance (anti-deepfake liveness and age gates are the leading candidates)
- Trust-root strategy when the phone's secure enclave is compromised — what revocation looks like at scale
- Regulatory framing per jurisdiction — selective disclosure of identity-adjacent claims may carry different obligations in EU vs US vs APAC
- Relationship to [[project_ows_alignment]] — whether Attest credentials align with Open Wallet Standards verifiable credential formats

## References

- [PAIMA_DUAL_CHAIN_PAIRING.md](./PAIMA_DUAL_CHAIN_PAIRING.md)
- [FEE_ABSTRACTION_MODELS.md](./FEE_ABSTRACTION_MODELS.md)
- C2PA (Coalition for Content Provenance and Authenticity) — incumbent comparison
- Apple Wallet mDL, Google Wallet mDL — incumbent comparison
- Onfido, Persona, Veriff, reCAPTCHA, AWS Rekognition — incumbents the SDK competes with
- Project memory: [[project_ows_alignment]], [[project_midnight_kicks]]
