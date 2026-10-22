#!/usr/bin/env bash
#
# One-shot prep for EVERY localnet-dependent instrumented test — so they RUN instead of
# assumeTrue-skipping. Idempotent; safe to call from CI or the SDK test gate before the run.
#
# The instrumented e2e suite depends on four things the test APKs can't provision themselves:
#
#   1. A fully-up localnet: node (:9944), indexer (:8088), proof-server (:6300). Deployed
#      automatically via `mn localnet up` when the ports aren't answering. Health is judged by
#      live port probes, never `mn localnet status` (which reports stale "running" state).
#   2. The canonical "abandon … art" wallet, funded + dust-registered (fund-localnet.sh).
#      Unblocks the core:ledger e2e suite (RealDustFeePaymentTest, DustPaymentE2ETest,
#      EndToEndTransactionTest) — all derive the same FUNDED_ADDRESS.
#   3. Wallet proving keys (zswap + dust + BLS params) adb-pushed to the device, so the
#      LOCAL-proving SdkRegistrationE2ETest can prove on-device (installFromLocalTmp reads
#      /data/local/tmp/wallet_keys/{zswap,dust} + /data/local/tmp/bboard_keys/bls_*).
#   4. An attached emulator/device.
#
# What each test still needs beyond this script:
#   - SdkSendNightIsolatedE2ETest (REMOTE proving): per-test funding servicer — run via
#     sdk/midnight-sdk/run-sdk-e2e.sh (fresh wallet per @Test, funded on demand).
#   - core:compact-engine bboard tests: per-test contract deploys — the Gradle `bboardSetup`
#     task (wired as a connected-test dependsOn) handles those.
#
# Usage:
#   scripts/e2e/prepare-localnet-e2e.sh [emulator-serial]
#     serial defaults to the first `adb devices` entry.
#   WALLET_KEYS_DIR=/path/to/wallet-keys  scripts/e2e/prepare-localnet-e2e.sh   # override key source
#
# Prereqs: Docker running, `mn` + `adb` on PATH, an emulator/device attached. The localnet
# itself is brought up automatically (`mn localnet up`) when its ports aren't answering.

set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

SERIAL="${1:-$(adb devices | awk '/\tdevice$/{print $1; exit}')}"
# The wallet proving keys are vendored in the Kicks example (the offline bundle, ~33MB).
WALLET_KEYS_DIR="${WALLET_KEYS_DIR:-examples/midnight-kicks/app/src/main/assets/wallet-keys}"
# Must match WALLET in fund-localnet.sh — that script creates it, this one checks its dust.
E2E_WALLET="kuiratest"

# Host ports map 1:1 to what the emulator reaches at 10.0.2.2:<port>.
NODE_URL="http://localhost:9944"
INDEXER_URL="http://localhost:8088/api/v4/graphql"
PROOF_SERVER_URL="http://localhost:6300"

step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m⚠\033[0m %s\n' "$1"; }
die()  { printf '\033[31mPREP FAILED: %s\033[0m\n' "$1" >&2; exit 1; }

# ── 1. Localnet: detect on launch, and deploy if not properly running ─────────
step "1/4 — localnet up + healthy (node :9944, indexer :8088, proof-server :6300)"

# Probe the REAL ports, never `mn localnet status` — it reports a stale "running" even when
# the containers are gone (observed: status said running with an empty `docker ps`). A live
# RPC call is the only truth. These are exactly the endpoints the emulator hits at 10.0.2.2.
probe_node()    { curl -sf -m 5 -X POST "$NODE_URL"    -H 'Content-Type: application/json' \
                    -d '{"jsonrpc":"2.0","id":1,"method":"system_health","params":[]}' >/dev/null 2>&1; }
probe_indexer() { curl -sf -m 5 -X POST "$INDEXER_URL" -H 'Content-Type: application/json' \
                    -d '{"query":"query { block { height } }"}' >/dev/null 2>&1; }
probe_proof()   {
  # This used to be `... -w '%{http_code}' | grep -qE '^[0-9]'`, which 000 satisfies -- and 000
  # is exactly what curl reports when nothing answers at all. So the probe returned success for
  # a dead proof server and the warning below could never fire. Verified against a closed port.
  local code
  code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$PROOF_SERVER_URL" 2>/dev/null || true)"
  case "$code" in ""|000) return 1 ;; esac
  return 0
}

# node + indexer are the hard requirements for the on-chain suite.
localnet_up() { probe_node && probe_indexer; }

if localnet_up; then
  ok "localnet already up + healthy"
else
  warn "localnet not fully up — deploying ('mn localnet up' brings up node + indexer + proof-server)…"
  if ! mn localnet up >/tmp/prep-localnet-up.log 2>&1; then
    warn "'mn localnet up' failed — removing conflicting containers and retrying…"
    mn localnet clean >>/tmp/prep-localnet-up.log 2>&1 || true
    mn localnet up    >>/tmp/prep-localnet-up.log 2>&1 \
      || die "'mn localnet up' failed even after clean — see /tmp/prep-localnet-up.log (and 'mn localnet logs')."
  fi
  # `up` blocks until the first block, but re-probe to CONFIRM the ports actually answer
  # before we depend on them (a container can be 'started' seconds before it serves).
  for _ in $(seq 1 30); do localnet_up && break; sleep 2; done
  localnet_up || die "localnet deployed but node/indexer not answering after 60s — see 'mn localnet logs'."
  ok "localnet deployed + healthy"
fi

# The proof server is required, not optional. It used to be a warning because the
# REMOTE-proving tier would assumeTrue-skip without it; nothing skips any more, so an absent
# proof server means that tier FAILS, and letting prep report success guarantees a red run
# ~30 minutes later with the cause this far behind it.
probe_proof && ok "proof-server reachable" \
  || die "proof-server not answering at $PROOF_SERVER_URL. The REMOTE-proving tier will FAIL without it (it no longer skips). Start it, or point PROOF_SERVER_URL at a live one."

# ── 2. Emulator connected ─────────────────────────────────────────────────────
step "2/4 — emulator/device connected"
[ -n "$SERIAL" ] || die "no emulator/device connected (adb devices)."
ok "using $SERIAL"

# ── 3. Canonical wallet funded + dust ─────────────────────────────────────────
step "3/4 — fund the canonical e2e wallet + register dust"
scripts/e2e/fund-localnet.sh >/tmp/prep-fund.log 2>&1 \
  || die "fund-localnet.sh failed — see /tmp/prep-fund.log"
# mn colorizes output; strip ANSI before matching the dust line.
dust_available() {
  mn dust status --wallet "$E2E_WALLET" 2>/dev/null \
    | sed $'s/\033\\[[0-9;]*m//g' | grep -iqE "Dust Available: *yes"
}
# Dust registration takes a few blocks to become spendable, so this polls rather than checking
# once. It used to warn and carry on, on the reasoning that dust-dependent tests would skip --
# they now FAIL, so an unmet precondition here is a guaranteed red run and belongs as an abort.
if dust_available; then
  ok "canonical wallet funded, dust available"
else
  echo "  dust registered but not spendable yet — waiting (up to 180s)…"
  for _ in $(seq 1 60); do dust_available && break; sleep 3; done
  dust_available \
    || die "dust still not available for '$E2E_WALLET' after 180s. Dust-dependent e2e tests will FAIL (they no longer skip) — see /tmp/prep-fund.log and 'mn dust status --wallet $E2E_WALLET'."
  ok "canonical wallet funded, dust available"
fi

# ── 4. Wallet proving keys on device (LOCAL-proving SdkRegistrationE2ETest) ────
step "4/4 — wallet proving keys → device (LOCAL proving)"
[ -d "$WALLET_KEYS_DIR/zswap" ] && [ -d "$WALLET_KEYS_DIR/dust" ] \
  || die "wallet keys not found at $WALLET_KEYS_DIR (expected zswap/ + dust/). Set WALLET_KEYS_DIR."

# Idempotent: skip the 33MB push if the marker key is already staged on the device.
if adb -s "$SERIAL" shell ls /data/local/tmp/wallet_keys/dust/spend.prover >/dev/null 2>&1; then
  ok "wallet keys already staged on $SERIAL — skipping push"
else
  echo "  pushing $(du -sh "$WALLET_KEYS_DIR" | cut -f1) of proving keys to $SERIAL (one-time)…"
  adb -s "$SERIAL" shell mkdir -p /data/local/tmp/wallet_keys /data/local/tmp/bboard_keys
  # zswap/* + dust/* under wallet_keys/; BLS params under bboard_keys/ (installFromLocalTmp's contract).
  adb -s "$SERIAL" push "$WALLET_KEYS_DIR/zswap" /data/local/tmp/wallet_keys/ >/dev/null
  adb -s "$SERIAL" push "$WALLET_KEYS_DIR/dust"  /data/local/tmp/wallet_keys/ >/dev/null
  [ -f "$WALLET_KEYS_DIR/version.txt" ] && \
    adb -s "$SERIAL" push "$WALLET_KEYS_DIR/version.txt" /data/local/tmp/wallet_keys/ >/dev/null
  bls_pushed=0
  for bls in "$WALLET_KEYS_DIR"/bls_*; do
    if [ -f "$bls" ]; then
      adb -s "$SERIAL" push "$bls" /data/local/tmp/bboard_keys/ >/dev/null
      bls_pushed=$((bls_pushed + 1))
    fi
  done
  # An unmatched glob leaves the literal pattern, so this loop could push NOTHING and still
  # report "staged". installFromLocalTmp reads these, so local proving would fail later with
  # nothing pointing back here.
  [ "$bls_pushed" -gt 0 ] \
    || die "no bls_* params found in $WALLET_KEYS_DIR. installFromLocalTmp needs them for LOCAL proving."
  ok "wallet proving keys staged ($bls_pushed BLS param files)"
fi

printf '\n\033[32m════ localnet e2e prep complete — dependent tests will RUN, not skip ════\033[0m\n'
