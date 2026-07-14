# Dust Backup Restore Continuity (fresh install / cross-device / cross-dApp)

## Problem

A wallet with dust backup enabled loses that fact on any fresh install: the toggle lives in
package-local prefs, and the checkpoint restore is silently best-effort — a fresh app has no
Drive grant for its OAuth client, so `silentTokenOrThrow` throws, the fetch is swallowed, and
the first sync replays from genesis (up to hours on a real chain) even though a one-tap
consent would have restored the checkpoint in seconds. Nothing in the UI tells the user a
backup exists, and the toggle misleadingly shows OFF.

Constraints discovered (already true, keep them):
- The dust Drive blob is an encrypted **per-address bundle**; addresses embed the network
  (`mn_addr_preprod…`), uploads merge per-address, fetch selects by address → network
  correctness is inherent. Pin it with a test, don't redesign it.
- Block Store is **per-app-package** (cloud-synced per Google account) → carries state across
  installs/devices of the SAME app, never between dApps.
- Drive `appDataFolder` is scoped per GCP project; all our dApps share one project → cross-dApp
  checkpoint fetch works once the new dApp has its own consent.

## Goals

1. **The backup preference travels with the wallet, not the install.** Wallet-level backup
   prefs (dust backup enabled / explicitly opted out) ride in the seed-keyed app-state blob
   (Block Store, silent, no consent). The blob gains a small versioned SDK envelope wrapping
   the host's existing payload; a blob without the envelope decodes as legacy host-only. The
   host payload API is unchanged for dApps.

2. **First sync prefers restore over genesis.** Before the first dust sync when no local
   checkpoint exists, a restore gate runs:
   - Try the silent Drive fetch first (covers same-app reinstall / new device — the Drive
     grant is account-level, so this usually just works with zero UX).
   - If consent is missing AND there is a reason to believe a backup exists (restored prefs
     say enabled, or the identity was restored-not-freshly-forged — which is exactly the
     cross-dApp first-run signal), surface the existing consent flow once, framed as
     "restore your synced state". Grant → fetch checkpoint → first sync is a delta.
     Decline / unavailable → genesis as today, and the decline is remembered so the user is
     never nagged.
   - A wallet whose prefs say explicitly opted-out is never prompted.

3. **The SDK's own sync waits for the gate.** The proactive dust sync starts at SDK build and
   would otherwise race past the prompt into genesis. The SDK exposes a host-provided restore
   gate awaited before its first no-checkpoint dust sync; hosts that don't set one keep
   today's behavior exactly.

4. **The toggle reflects wallet truth.** On bootstrap the toggle derives from restored prefs +
   consent state: enabled-and-granted → ON; enabled-but-needs-access → ON with a
   needs-access affordance that launches consent; opted-out → OFF.

5. **Network switches re-run the gate.** The gate keys off the CURRENT network's address
   (live provider SDK, never a cached boot-network handle). Switching to a network with no
   local checkpoint takes the same restore-first path.

## Non-goals

- No change to the bundle format, encryption, or digest guards.
- No cross-dApp Block Store sharing (platform limitation); cross-dApp continuity rides the
  shared-GCP-project Drive fetch + one consent in the new dApp.
- No new consent UI — reuse the existing enable-backup consent flow.

## Verification

- Envelope codec: legacy blob (raw host bytes) round-trips untouched; enveloped blob restores
  prefs + host payload; oversized-for-Block-Store rejected loudly.
- Restore gate ordering pinned by tests: no-checkpoint + silent-grant → delta, no prompt;
  no-checkpoint + needs-consent + enabled-prefs → gate waits, grant path seeds checkpoint,
  decline path falls to genesis once and is remembered; opted-out → no prompt, no fetch.
- Network pinning test: bundle with entries for two networks, fetch with the preprod address
  returns only the preprod entry.
- On-device: fresh install of a second dApp (shared GCP project) with a wallet backed up by
  the first → consent prompt → delta restore, no genesis. Same-app uninstall/reinstall →
  silent restore, no prompt, no genesis.
- Full SDK gate (all modules, unit + instrumented) before commit.
